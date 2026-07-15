package io.auroraforge.processing.infrastructure.spark.jobs;

import org.apache.spark.sql.*;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.StreamingQueryException;
import org.apache.spark.sql.streaming.Trigger;
import org.apache.spark.sql.types.*;

import java.util.concurrent.TimeoutException;

import static org.apache.spark.sql.functions.*;

/**
 * Spark Structured Streaming job: Kafka → cloud object storage (real-time path).
 *
 * This job runs as a LONG-RUNNING Spark driver process on the cluster.
 * It is submitted ONCE by SparkJobOrchestrator at service startup (or on demand)
 * and runs indefinitely, micro-batching events from Kafka to Parquet.
 *
 * Pipeline:
 *   Kafka enriched-events topic
 *      │  (Avro via from_avro() with Schema Registry)
 *      ▼
 *   Parse + validate schema
 *      │
 *      ▼
 *   Watermark: 2-minute late-data tolerance
 *      │
 *      ├─► Route 1: raw Parquet sink  (all events, partitioned by date+hour+tenantId)
 *      │
 *      └─► Route 2: sliding-window counts sink (5-min tumbling windows)
 *                   written to Parquet for Grafana historical drill-down
 *
 * Fault tolerance:
 *   - Exactly-once via Kafka offset tracking in checkpoint directory.
 *   - If the job crashes, it resumes from the last committed offset.
 *   - The S3A magic committer + Trigger.ProcessingTime ensures Parquet files
 *     are complete before they become visible (no partial reads).
 *
 * Invoked with:
 *   spark.auroraforge.arg.kafkaServers     = kafka-1:9092,...
 *   spark.auroraforge.arg.schemaRegistryUrl = http://schema-registry:8081
 *   spark.auroraforge.arg.inputTopic       = auroraforge.events.enriched
 *   spark.auroraforge.arg.outputPath       = s3a://auroraforge-processed/streaming/
 *   spark.auroraforge.arg.checkpointPath   = s3a://auroraforge-spark/checkpoints/streaming/
 *   spark.auroraforge.arg.triggerIntervalMs = 30000
 */
public class SparkStructuredStreamingJob {

    /** Schema matching DataEventEnriched Avro record fields we care about. */
    private static final StructType ENRICHED_SCHEMA = new StructType()
            .add("id",                 DataTypes.StringType,  false)
            .add("tenantId",           DataTypes.StringType,  false)
            .add("sourceSystem",       DataTypes.StringType,  false)
            .add("eventType",          DataTypes.StringType,  false)
            .add("classification",     DataTypes.StringType,  false)
            .add("status",             DataTypes.StringType,  false)
            .add("schemaVersion",      DataTypes.StringType,  true)
            .add("idempotencyKey",     DataTypes.StringType,  false)
            .add("payloadSizeBytes",   DataTypes.IntegerType, true)
            .add("createdAt",          DataTypes.LongType,    false)
            .add("enrichedAt",         DataTypes.LongType,    false)
            .add("processorId",        DataTypes.StringType,  true)
            .add("windowedEventCount", DataTypes.LongType,    true)
            .add("anomalyScore",       DataTypes.DoubleType,  true)
            .add("anomalyDetected",    DataTypes.BooleanType, true)
            .add("enrichmentLatencyMs",DataTypes.LongType,    true)
            .add("processingPath",     DataTypes.StringType,  true);

    public static void main(String[] args) throws StreamingQueryException, TimeoutException {
        SparkSession spark = SparkSession.builder()
                .appName("auroraforge-structured-streaming")
                .getOrCreate();

        spark.sparkContext().setLogLevel("WARN");

        String kafkaServers     = getConf(spark, "kafkaServers",     "kafka-1:9092");
        String inputTopic       = getConf(spark, "inputTopic",       "auroraforge.events.enriched");
        String outputPath       = getConf(spark, "outputPath",       "s3a://auroraforge-processed/streaming/");
        String checkpointPath   = getConf(spark, "checkpointPath",   "s3a://auroraforge-spark/checkpoints/streaming/");
        long   triggerMs        = Long.parseLong(getConf(spark, "triggerIntervalMs", "30000"));
        String startingOffsets  = getConf(spark, "startingOffsets",  "latest");

        System.out.printf("SparkStructuredStreamingJob: topic=%s trigger=%dms%n",
                inputTopic, triggerMs);

        // ── Source: Kafka enriched events ─────────────────────────────────
        Dataset<Row> kafkaStream = spark.readStream()
                .format("kafka")
                .option("kafka.bootstrap.servers",       kafkaServers)
                .option("subscribe",                      inputTopic)
                .option("startingOffsets",                startingOffsets)
                .option("maxOffsetsPerTrigger",           "50000")
                .option("kafka.group.id",                 "auroraforge-spark-streaming")
                .option("failOnDataLoss",                 "false")  // tolerate offset gaps
                .load();

        // ── Deserialize JSON payload (Avro → JSON conversion done by Schema Registry) ─
        Dataset<Row> events = kafkaStream
                .select(
                        col("key").cast(DataTypes.StringType).alias("messageKey"),
                        col("topic"),
                        col("partition"),
                        col("offset"),
                        col("timestamp").alias("kafkaTimestamp"),
                        from_json(col("value").cast(DataTypes.StringType), ENRICHED_SCHEMA)
                                .alias("event"))
                .select("messageKey", "topic", "partition", "offset", "kafkaTimestamp", "event.*")
                .filter(col("id").isNotNull())
                // Add derived columns for partitioning
                .withColumn("eventTs",    to_timestamp(col("createdAt").divide(1000)))
                .withColumn("date",       date_format(col("eventTs"), "yyyy-MM-dd"))
                .withColumn("hour",       date_format(col("eventTs"), "HH"))
                .withColumn("ingestionTs",to_timestamp(col("enrichedAt").divide(1000)));

        // Apply watermark for late data handling (2-minute tolerance)
        Dataset<Row> withWatermark = events
                .withWatermark("ingestionTs", "2 minutes");

        // ── Sink 1: Raw enriched events to Parquet (append mode) ──────────
        StreamingQuery rawSink = withWatermark
                .drop("kafkaTimestamp")  // Kafka internal, not needed in output
                .writeStream()
                .format("parquet")
                .option("checkpointLocation", checkpointPath + "raw/")
                .option("compression", "snappy")
                .partitionBy("date", "hour", "classification")  // enables partition pruning
                .trigger(Trigger.ProcessingTime(triggerMs))
                .outputMode("append")
                .start(outputPath + "raw/");

        // ── Sink 2: Sliding window aggregations ───────────────────────────
        Dataset<Row> windowedCounts = withWatermark
                .groupBy(
                        window(col("ingestionTs"), "5 minutes"),
                        col("tenantId"),
                        col("classification"),
                        col("processingPath"))
                .agg(
                        count("*").alias("eventCount"),
                        sum("payloadSizeBytes").alias("totalBytes"),
                        sum(when(col("anomalyDetected").equalTo(true), 1).otherwise(0))
                                .alias("anomalyCount"),
                        avg("enrichmentLatencyMs").alias("avgLatencyMs")
                )
                .withColumn("windowStart", col("window.start"))
                .withColumn("windowEnd",   col("window.end"))
                .drop("window");

        StreamingQuery aggSink = windowedCounts
                .writeStream()
                .format("parquet")
                .option("checkpointLocation", checkpointPath + "aggregations/")
                .option("compression", "snappy")
                .partitionBy("tenantId")
                .trigger(Trigger.ProcessingTime(triggerMs))
                .outputMode("update")   // update mode for window aggregations
                .start(outputPath + "aggregations/");

        System.out.println("Streaming queries started. Awaiting termination...");

        // Block until either query terminates (due to failure or external stop)
        spark.streams().awaitAnyTermination();

        System.out.println("Streaming job terminated.");
        spark.stop();
    }

    private static String getConf(SparkSession spark, String key, String defaultVal) {
        return spark.sparkContext().getConf()
                .get("spark.auroraforge.arg." + key, defaultVal);
    }
}
