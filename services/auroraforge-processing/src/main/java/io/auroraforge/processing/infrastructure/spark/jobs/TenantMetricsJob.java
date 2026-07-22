package io.auroraforge.processing.infrastructure.spark.jobs;

import org.apache.spark.sql.*;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.apache.spark.sql.functions.*;

/**
 * Per-tenant daily metrics computation job.
 *
 * Reads from the aggregation output of {@link EventAggregationJob}
 * (not raw events — avoids re-scanning large input) and computes
 * tenant-level business metrics for the operational dashboard.
 *
 * Invoked with:
 *   spark.auroraforge.arg.date         = 2024-01-15
 *   spark.auroraforge.arg.aggPath      = s3a://auroraforge-processed/aggregations/
 *   spark.auroraforge.arg.outputPath   = s3a://auroraforge-processed/tenant-metrics/
 *   spark.auroraforge.arg.lookbackDays = 30 (rolling window for trend analysis)
 *
 * Output (one row per tenant per day):
 *  tenantId, date, totalEvents, totalPayloadMb, anomalyRate,
 *  dominantClassification, uniqueEventTypes, p99LatencyMs,
 *  weekOnWeekGrowth, dayOnDayGrowth
 *
 * Results are written in Parquet and also as a Cosmos DB-friendly JSON
 * for real-time API serving (the sync module reads from here).
 */
public class TenantMetricsJob {

    public static void main(String[] args) {
        SparkSession spark = SparkSession.builder()
                .appName("auroraforge-tenant-metrics")
                .getOrCreate();

        spark.sparkContext().setLogLevel("WARN");

        String processDate  = getConf(spark, "date",         yesterday());
        String aggPath      = getConf(spark, "aggPath",      "s3a://auroraforge-processed/aggregations/");
        String outputPath   = getConf(spark, "outputPath",   "s3a://auroraforge-processed/tenant-metrics/");
        int    lookbackDays = Integer.parseInt(getConf(spark, "lookbackDays", "30"));

        System.out.printf("TenantMetricsJob: date=%s lookback=%d days%n", processDate, lookbackDays);

        // ── Load aggregations for the target date ─────────────────────────
        Dataset<Row> eventCounts = spark.read()
                .parquet(aggPath + "event-counts/date=" + processDate + "/")
                .withColumn("date", lit(processDate));

        Dataset<Row> latencyStats = spark.read()
                .parquet(aggPath + "latency-stats/date=" + processDate + "/")
                .withColumn("date", lit(processDate));

        // ── Per-tenant summary for the target date ─────────────────────────
        Dataset<Row> dailySummary = eventCounts
                .groupBy("tenantId")
                .agg(
                        sum("eventCount").alias("totalEvents"),
                        (sum("totalPayloadBytes").divide(1_048_576)).alias("totalPayloadMb"),
                        sum("anomalyCount").alias("totalAnomalies"),
                        countDistinct("eventType").alias("uniqueEventTypes"),
                        countDistinct("classification").alias("activeClassifications")
                )
                .withColumn("anomalyRate",
                        col("totalAnomalies").divide(col("totalEvents")))
                .withColumn("date", lit(processDate));

        // ── Join with latency stats ────────────────────────────────────────
        Dataset<Row> withLatency = dailySummary.join(
                latencyStats.select("tenantId", "p50LatencyMs", "p95LatencyMs", "p99LatencyMs"),
                "tenantId", "left");

        // ── Dominant classification per tenant ────────────────────────────
        WindowSpec classRankWindow = Window.partitionBy("tenantId").orderBy(col("eventCount").desc());

        Dataset<Row> dominantClass = eventCounts
                .withColumn("classRank", rank().over(classRankWindow))
                .filter(col("classRank").equalTo(1))
                .select("tenantId", col("classification").alias("dominantClassification"));

        Dataset<Row> tenantMetrics = withLatency
                .join(dominantClass, "tenantId", "left")
                .withColumn("computedAt", current_timestamp());

        // ── Rolling 30-day lookback (WoW and DoD growth) ─────────────────
        LocalDate targetDate = LocalDate.parse(processDate);
        String prevDay  = targetDate.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String prevWeek = targetDate.minusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE);

        Dataset<Row> todayEvents = tenantMetrics.select(
                col("tenantId"), col("totalEvents").alias("todayEvents"));

        Dataset<Row> withGrowth = loadHistoricalAndComputeGrowth(
                spark, aggPath, todayEvents, prevDay, prevWeek);

        Dataset<Row> finalMetrics = tenantMetrics
                .join(withGrowth, "tenantId", "left");

        // ── Write Parquet output (partitioned by date) ─────────────────────
        finalMetrics
                .write()
                .mode(SaveMode.Overwrite)
                .partitionBy("date")
                .parquet(outputPath + "parquet/");

        // ── Write JSON for API serving (Cosmos DB / REST cache) ──────────
        finalMetrics
                .coalesce(1)
                .write()
                .mode(SaveMode.Overwrite)
                .option("multiLine", true)
                .json(outputPath + "json/date=" + processDate + "/");

        System.out.printf("TenantMetricsJob COMPLETED: date=%s tenants=%d%n",
                processDate, finalMetrics.count());

        spark.stop();
    }

    private static Dataset<Row> loadHistoricalAndComputeGrowth(
            SparkSession spark, String aggPath,
            Dataset<Row> todayEvents, String prevDay, String prevWeek) {

        try {
            Dataset<Row> prevDayData = spark.read()
                    .parquet(aggPath + "event-counts/date=" + prevDay + "/")
                    .groupBy("tenantId")
                    .agg(sum("eventCount").alias("prevDayEvents"));

            Dataset<Row> prevWeekData = spark.read()
                    .parquet(aggPath + "event-counts/date=" + prevWeek + "/")
                    .groupBy("tenantId")
                    .agg(sum("eventCount").alias("prevWeekEvents"));

            return todayEvents
                    .join(prevDayData,  "tenantId", "left")
                    .join(prevWeekData, "tenantId", "left")
                    .withColumn("dayOnDayGrowthPct",
                            when(col("prevDayEvents").gt(0),
                                    col("todayEvents").minus(col("prevDayEvents"))
                                            .divide(col("prevDayEvents"))
                                            .multiply(100))
                            .otherwise(lit(null)))
                    .withColumn("weekOnWeekGrowthPct",
                            when(col("prevWeekEvents").gt(0),
                                    col("todayEvents").minus(col("prevWeekEvents"))
                                            .divide(col("prevWeekEvents"))
                                            .multiply(100))
                            .otherwise(lit(null)))
                    .drop("todayEvents");

        } catch (Exception e) {
            System.err.println("Could not load historical data (first run?): " + e.getMessage());
            return todayEvents
                    .withColumn("dayOnDayGrowthPct",  lit(null).cast("double"))
                    .withColumn("weekOnWeekGrowthPct", lit(null).cast("double"))
                    .drop("todayEvents");
        }
    }

    private static String getConf(SparkSession spark, String key, String defaultVal) {
        return spark.sparkContext().getConf()
                .get("spark.auroraforge.arg." + key, defaultVal);
    }

    private static String yesterday() {
        return LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
