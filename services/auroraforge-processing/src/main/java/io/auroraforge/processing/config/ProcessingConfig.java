package io.auroraforge.processing.config;

import io.auroraforge.core.config.cloud.AwsProperties;
import io.auroraforge.core.config.cloud.AzureProperties;
import io.auroraforge.core.config.cloud.CloudProperties;
import io.auroraforge.core.config.kafka.KafkaTopicProperties;
import io.auroraforge.core.config.retention.DataRetentionProperties;
import io.auroraforge.processing.infrastructure.spark.SparkJobOrchestrator;
import io.auroraforge.processing.infrastructure.spark.jobs.SparkStructuredStreamingJob;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Map;

/**
 * Root configuration for the auroraforge-processing module.
 *
 * Responsibilities:
 *  1. Enables all cross-cutting @ConfigurationProperties via @EnableConfigurationProperties.
 *  2. Enables @Scheduled (used by SparkJobOrchestrator.cleanupStalledJobs).
 *  3. Auto-submits the Spark Structured Streaming job on startup if configured
 *     (auroraforge.spark.structured-streaming.auto-submit-on-startup=true).
 *
 * The Kafka Streams topology (EventStreamTopology) is wired by Spring automatically
 * via the @Bean-annotated StreamsBuilder configuration. No explicit wiring needed.
 */
@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@EnableConfigurationProperties({
        CloudProperties.class,
        AwsProperties.class,
        AzureProperties.class,
        KafkaTopicProperties.class,
        DataRetentionProperties.class,
        SparkProperties.class
})
public class ProcessingConfig {

    private final SparkProperties     sparkProperties;
    private final SparkJobOrchestrator sparkJobOrchestrator;

    /**
     * Conditionally submits the Spark Structured Streaming job at startup.
     *
     * This is disabled by default (auto-submit-on-startup=false) to prevent
     * accidental double-submission across rolling deployments. Enable in
     * application-aws.yml / application-azure.yml for the designated primary replica.
     *
     * In production, job submission is typically triggered by a K8s CronJob or
     * the Spark Operator, not from within the Spring Boot application.
     */
    @PostConstruct
    void maybeAutoSubmitStreamingJob() {
        if (sparkProperties.structuredStreaming().autoSubmitOnStartup()) {
            log.info("Auto-submitting Spark Structured Streaming job...");

            String jobId = sparkJobOrchestrator.submit(
                    SparkStructuredStreamingJob.class.getName(),
                    Map.of(
                            "inputTopic",
                                sparkProperties.config().getOrDefault(
                                        "spark.auroraforge.streaming.input.topic",
                                        "auroraforge.events.enriched"),
                            "outputPath",
                                sparkProperties.config().getOrDefault(
                                        "spark.auroraforge.streaming.output.path",
                                        "s3a://auroraforge-processed/streaming/"),
                            "checkpointPath",
                                sparkProperties.checkpoint().baseLocation() + "/streaming/",
                            "triggerIntervalMs",
                                sparkProperties.structuredStreaming().triggerIntervalMs()
                    )
            );

            log.info("Spark Structured Streaming job submitted: jobId={}", jobId);
        } else {
            log.info("Spark Structured Streaming auto-submit disabled " +
                    "(auroraforge.spark.structured-streaming.auto-submit-on-startup=false)");
        }
    }
}
