package io.auroraforge.processing.infrastructure.spark.jobs;

import org.apache.spark.sql.*;
import org.apache.spark.sql.types.DataTypes;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.apache.spark.sql.functions.*;

/**
 * Event archival job — moves processed events from hot storage to cold archive.
 *
 * Archival strategy by classification:
 *  - PUBLIC:       archive after 30 days   → Standard-IA / Cool → expire at 365 days
 *  - INTERNAL:     archive after 90 days   → Standard-IA / Cool → expire at 730 days
 *  - CONFIDENTIAL: archive after 180 days  → Glacier / Archive  → expire at 1825 days
 *  - RESTRICTED:   archive after 365 days  → Glacier Deep Archive → expire at 2555 days
 *
 * The archival is a COPY + MARK operation:
 *  1. Read events older than the retention threshold from the enriched path.
 *  2. Write to archive path in compressed Parquet (GZIP for cold storage).
 *  3. Output a manifest JSON listing archived event IDs.
 *  4. The physical deletion from hot storage is handled by S3/Blob lifecycle
 *     rules (Terraform-managed), not by this job. This job only writes the archive.
 *
 * Invoked with:
 *   spark.auroraforge.arg.inputPath     = s3a://auroraforge-raw/enriched/
 *   spark.auroraforge.arg.archivePath   = s3a://auroraforge-archive/events/
 *   spark.auroraforge.arg.manifestPath  = s3a://auroraforge-processed/manifests/
 *   spark.auroraforge.arg.cutoffDate    = 2024-01-01 (archive events before this date)
 *   spark.auroraforge.arg.classification = ALL | PUBLIC | INTERNAL | CONFIDENTIAL | RESTRICTED
 */
public class EventArchivalJob {

    // Retention thresholds in days by classification
    private static final Map<String, Integer> ARCHIVE_AFTER_DAYS = Map.of(
            "PUBLIC",        30,
            "INTERNAL",      90,
            "CONFIDENTIAL", 180,
            "RESTRICTED",   365
    );

    public static void main(String[] args) {
        SparkSession spark = SparkSession.builder()
                .appName("auroraforge-event-archival")
                .getOrCreate();

        spark.sparkContext().setLogLevel("WARN");

        String inputPath      = getConf(spark, "inputPath",     "s3a://auroraforge-raw/enriched/");
        String archivePath    = getConf(spark, "archivePath",   "s3a://auroraforge-archive/events/");
        String manifestPath   = getConf(spark, "manifestPath",  "s3a://auroraforge-processed/manifests/");
        String cutoffDateStr  = getConf(spark, "cutoffDate",    LocalDate.now().minusDays(30).toString());
        String classification = getConf(spark, "classification","ALL");

        System.out.printf("EventArchivalJob: cutoff=%s classification=%s%n",
                cutoffDateStr, classification);

        LocalDate cutoff = LocalDate.parse(cutoffDateStr, DateTimeFormatter.ISO_LOCAL_DATE);

        // ── Read all enriched events up to the cutoff date ────────────────
        Dataset<Row> allEvents = spark.read()
                .option("recursiveFileLookup", "true")
                .option("multiLine", "true")
                .json(inputPath)
                .withColumn("eventDate",
                        to_date(to_timestamp(col("createdAt").divide(1000))))
                .filter(col("eventDate").lt(lit(cutoffDateStr)));

        if (!"ALL".equals(classification)) {
            allEvents = allEvents.filter(col("classification").equalTo(classification));
        }

        // ── Apply per-classification retention thresholds ─────────────────
        Dataset<Row> archivable = allEvents.filter(
                col("classification").equalTo("PUBLIC")
                        .and(col("eventDate").lt(lit(cutoff.minusDays(ARCHIVE_AFTER_DAYS.get("PUBLIC")).toString())))
                .or(col("classification").equalTo("INTERNAL")
                        .and(col("eventDate").lt(lit(cutoff.minusDays(ARCHIVE_AFTER_DAYS.get("INTERNAL")).toString()))))
                .or(col("classification").equalTo("CONFIDENTIAL")
                        .and(col("eventDate").lt(lit(cutoff.minusDays(ARCHIVE_AFTER_DAYS.get("CONFIDENTIAL")).toString()))))
                .or(col("classification").equalTo("RESTRICTED")
                        .and(col("eventDate").lt(lit(cutoff.minusDays(ARCHIVE_AFTER_DAYS.get("RESTRICTED")).toString()))))
        );

        archivable.cache();
        long archiveCount = archivable.count();
        System.out.printf("Events eligible for archival: %d%n", archiveCount);

        if (archiveCount == 0) {
            System.out.println("No events to archive. Job complete.");
            spark.stop();
            return;
        }

        // ── Write to archive in GZIP Parquet (cold storage optimised) ─────
        // Partitioned by (year, month, classification) for efficient lifecycle queries
        archivable
                .withColumn("year",  year(col("eventDate")).cast(DataTypes.StringType))
                .withColumn("month", lpad(month(col("eventDate")).cast(DataTypes.StringType), 2, "0"))
                .write()
                .mode(SaveMode.Append)
                .partitionBy("year", "month", "classification")
                .option("compression", "gzip")  // best compression ratio for cold data
                .parquet(archivePath);

        // ── Write manifest: list of archived event IDs + metadata ─────────
        Dataset<Row> manifest = archivable
                .select(
                        col("id").alias("eventId"),
                        col("tenantId"),
                        col("classification"),
                        col("eventDate"),
                        col("createdAt"),
                        current_timestamp().alias("archivedAt"),
                        lit(archivePath).alias("archiveLocation")
                );

        String manifestDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        manifest
                .coalesce(1)
                .write()
                .mode(SaveMode.Overwrite)
                .option("multiLine", true)
                .json(manifestPath + "date=" + manifestDate + "/");

        System.out.printf("EventArchivalJob COMPLETED: archived=%d manifest=%s%n",
                archiveCount, manifestPath);

        archivable.unpersist();
        spark.stop();
    }

    private static String getConf(SparkSession spark, String key, String defaultVal) {
        return spark.sparkContext().getConf()
                .get("spark.auroraforge.arg." + key, defaultVal);
    }
}
