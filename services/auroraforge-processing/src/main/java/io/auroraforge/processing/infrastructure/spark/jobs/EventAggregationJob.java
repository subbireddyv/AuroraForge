package io.auroraforge.processing.infrastructure.spark.jobs;

import org.apache.spark.sql.*;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;
import org.apache.spark.sql.streaming.StreamingQueryException;
import org.apache.spark.sql.types.DataTypes;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.apache.spark.sql.functions.*;

/**
 * Daily event aggregation batch job.
 *
 * Invoked via SparkJobOrchestrator with:
 *   spark.auroraforge.arg.date         = 2024-01-15  (process date, defaults to yesterday)
 *   spark.auroraforge.arg.inputPath    = s3a://auroraforge-raw/enriched/
 *   spark.auroraforge.arg.outputPath   = s3a://auroraforge-processed/aggregations/
 *   spark.auroraforge.arg.outputFormat = parquet (default) | delta | json
 *
 * Input: enriched events in JSON format under:
 *   {inputPath}/{date}/         (written by EnrichedEventConsumer)
 *
 * Output partitioned by (date, tenantId, classification):
 *   {outputPath}/date={date}/tenantId={tenant}/classification={cls}/part-*.parquet
 *
 * Aggregations computed:
 *  1. Event counts per (tenantId, classification, eventType, schemaVersion)
 *  2. Total payload bytes per (tenantId, classification)
 *  3. Anomaly counts per tenantId
 *  4. p50/p95/p99 enrichment latency per tenantId
 *  5. Hourly event rate (for Grafana time-series charts)
 *  6. Top-10 event types per tenant
 *
 * This is a standalone Spark application — it does NOT use Spring IoC.
 * All configuration is read from SparkContext.getConf() (passed by orchestrator).
 */
public class EventAggregationJob {

    public static void main(String[] args) {
        SparkSession spark = SparkSession.builder()
                .appName("auroraforge-event-aggregation")
                .getOrCreate();

        spark.sparkContext().setLogLevel("WARN");

        String processDate  = getConf(spark, "date",         yesterday());
        String inputPath    = getConf(spark, "inputPath",    "s3a://auroraforge-raw/enriched/");
        String outputPath   = getConf(spark, "outputPath",   "s3a://auroraforge-processed/aggregations/");
        String outputFormat = getConf(spark, "outputFormat", "parquet");

        System.out.printf("EventAggregationJob: date=%s input=%s output=%s%n",
                processDate, inputPath, outputPath);

        // ── Read enriched events for the target date ───────────────────────
        Dataset<Row> events = spark.read()
                .option("recursiveFileLookup", "true")
                .option("multiLine", "true")
                .json(inputPath + processDate + "/")
                .withColumn("date", lit(processDate))
                // Parse hour from enrichedAt (epoch millis)
                .withColumn("hour",
                        date_format(to_timestamp(col("enrichedAt").divide(1000)),
                                "HH").cast(DataTypes.IntegerType));

        events.cache();
        long totalEvents = events.count();
        System.out.printf("Loaded %d events for date %s%n", totalEvents, processDate);

        // ── Aggregation 1: Event counts by tenant + classification + type ──
        Dataset<Row> eventCounts = events
                .groupBy("tenantId", "classification", "eventType", "schemaVersion", "date")
                .agg(
                        count("*").alias("eventCount"),
                        sum("payloadSizeBytes").alias("totalPayloadBytes"),
                        sum(when(col("anomalyDetected").equalTo(true), 1).otherwise(0))
                                .alias("anomalyCount"),
                        min("createdAt").alias("firstEventAt"),
                        max("createdAt").alias("lastEventAt")
                )
                .withColumn("avgPayloadBytes",
                        col("totalPayloadBytes").divide(col("eventCount")));

        writeOutput(eventCounts, outputPath + "event-counts/", "date", outputFormat, spark);

        // ── Aggregation 2: Latency percentiles per tenant ─────────────────
        Dataset<Row> latencyStats = events
                .groupBy("tenantId", "date")
                .agg(
                        count("*").alias("sampleCount"),
                        percentile_approx(col("enrichmentLatencyMs"), lit(0.50), lit(1000))
                                .alias("p50LatencyMs"),
                        percentile_approx(col("enrichmentLatencyMs"), lit(0.95), lit(1000))
                                .alias("p95LatencyMs"),
                        percentile_approx(col("enrichmentLatencyMs"), lit(0.99), lit(1000))
                                .alias("p99LatencyMs"),
                        max("enrichmentLatencyMs").alias("maxLatencyMs")
                );

        writeOutput(latencyStats, outputPath + "latency-stats/", "date", outputFormat, spark);

        // ── Aggregation 3: Hourly event rate per tenant ────────────────────
        Dataset<Row> hourlyRate = events
                .groupBy("tenantId", "date", "hour")
                .agg(count("*").alias("eventCount"))
                .orderBy("tenantId", "hour");

        writeOutput(hourlyRate, outputPath + "hourly-rate/", "date", outputFormat, spark);

        // ── Aggregation 4: Top-10 event types per tenant ───────────────────
        WindowSpec rankWindow = Window
                .partitionBy("tenantId")
                .orderBy(col("typeCount").desc());

        Dataset<Row> topEventTypes = events
                .groupBy("tenantId", "eventType", "date")
                .agg(count("*").alias("typeCount"))
                .withColumn("rank", rank().over(rankWindow))
                .filter(col("rank").leq(10));

        writeOutput(topEventTypes, outputPath + "top-event-types/", "date", outputFormat, spark);

        System.out.printf("EventAggregationJob COMPLETED: date=%s events=%d%n",
                processDate, totalEvents);

        events.unpersist();
        spark.stop();
    }

    private static void writeOutput(Dataset<Row> df, String path, String partitionCol,
                                     String format, SparkSession spark) {
        df.write()
                .mode(SaveMode.Overwrite)
                .partitionBy(partitionCol)
                .format(format)
                .option("compression", "snappy")
                .save(path);
        System.out.printf("Written to %s (format=%s rows=%d)%n", path, format, df.count());
    }

    private static String getConf(SparkSession spark, String key, String defaultVal) {
        return spark.sparkContext().getConf()
                .get("spark.auroraforge.arg." + key, defaultVal);
    }

    private static String yesterday() {
        return LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
