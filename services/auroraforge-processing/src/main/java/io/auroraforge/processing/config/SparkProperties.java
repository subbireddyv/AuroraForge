package io.auroraforge.processing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * Spark cluster and job configuration.
 *
 * Bound from: auroraforge.spark.*
 *
 * In production on K8s, jobs are submitted in cluster mode via the Spark REST
 * submission API (Spark Standalone) or via spark-submit → Kubernetes. The
 * Spring Boot process (the Spring app) acts as the client that initiates
 * submission; it is NOT the Spark driver.
 *
 * For Structured Streaming jobs, the job is a long-running driver process on
 * the Spark cluster. The Spring Boot app submits it once at startup and tracks
 * the driver process ID via the cluster REST API.
 */
@Validated
@ConfigurationProperties(prefix = "auroraforge.spark")
public record SparkProperties(

        String master,

        String appName,

        String deployMode,

        String driverMemory,

        String executorMemory,

        String executorCores,

        String executorInstances,

        String sparkHome,

        Map<String, String> config,

        CheckpointConfig checkpoint,

        StructuredStreamingConfig structuredStreaming

) {
    public SparkProperties {
        if (master == null) master = "spark://spark-master:7077";
        if (appName == null) appName = "auroraforge-processing";
        if (deployMode == null) deployMode = "client";
        if (driverMemory == null) driverMemory = "1g";
        if (executorMemory == null) executorMemory = "2g";
        if (executorCores == null) executorCores = "2";
        if (executorInstances == null) executorInstances = "4";
        if (config == null) config = Map.of();
        if (checkpoint == null) checkpoint = CheckpointConfig.defaults();
        if (structuredStreaming == null) structuredStreaming = StructuredStreamingConfig.defaults();
    }

    public record CheckpointConfig(
            String baseLocation,
            int maxCheckpointIntervalSecs
    ) {
        static CheckpointConfig defaults() {
            return new CheckpointConfig("/tmp/spark-checkpoints", 60);
        }
    }

    public record StructuredStreamingConfig(
            String triggerIntervalMs,
            int    outputBatchSize,
            String outputFormat,
            String outputMode,
            boolean autoSubmitOnStartup
    ) {
        static StructuredStreamingConfig defaults() {
            return new StructuredStreamingConfig("30000", 1000, "parquet", "append", false);
        }
    }
}
