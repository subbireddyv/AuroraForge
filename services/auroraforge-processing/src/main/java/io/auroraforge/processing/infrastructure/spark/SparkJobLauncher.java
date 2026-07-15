package io.auroraforge.processing.infrastructure.spark;

import io.auroraforge.processing.domain.model.BatchJob;
import io.auroraforge.processing.domain.model.BatchJobStatus;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.launcher.SparkLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Launches Spark batch jobs against the external Spark cluster.
 *
 * Jobs are submitted via SparkLauncher (client mode → REST submission API
 * for cluster mode in production). Job status is tracked in-memory for local dev
 * and via a dedicated jobs table in PostgreSQL in production.
 *
 * Supported job types:
 *  - DAILY_AGGREGATION  : tenant event aggregation for the previous day
 *  - SCHEMA_EVOLUTION   : re-processes events after a schema schema version bump
 *  - CROSS_CLOUD_BACKFILL: syncs historical data to the secondary cloud
 */
@Slf4j
@Component
public class SparkJobLauncher {

    private final Map<String, BatchJob> jobRegistry = new ConcurrentHashMap<>();

    @Value("${auroraforge.spark.master:spark://spark-master:7077}")
    private String sparkMaster;

    @Value("${auroraforge.spark.app-jar:auroraforge-processing.jar}")
    private String appJar;

    @Value("${auroraforge.spark.driver-memory:512m}")
    private String driverMemory;

    @Value("${auroraforge.spark.executor-memory:1g}")
    private String executorMemory;

    @Value("${auroraforge.spark.executor-cores:2}")
    private String executorCores;

    /**
     * Submit a batch job asynchronously.
     *
     * @param jobType     class name of the Spark job entry point
     * @param jobArgs     key-value arguments passed to the Spark application
     * @return tracking ID for polling job status
     */
    public String submitJob(String jobType, Map<String, String> jobArgs) {
        String jobId = UUID.randomUUID().toString();
        log.info("Submitting Spark job: type={} jobId={}", jobType, jobId);

        BatchJob job = BatchJob.builder()
                .id(jobId)
                .jobType(jobType)
                .status(BatchJobStatus.SUBMITTED)
                .submittedAt(java.time.Instant.now())
                .args(jobArgs)
                .build();

        jobRegistry.put(jobId, job);

        // Run the submission in a virtual thread to avoid blocking the request thread
        Thread.ofVirtual()
              .name("spark-submit-" + jobId)
              .start(() -> doSubmit(job, jobArgs));

        return jobId;
    }

    private void doSubmit(BatchJob job, Map<String, String> jobArgs) {
        try {
            SparkLauncher launcher = new SparkLauncher()
                    .setMaster(sparkMaster)
                    .setAppResource(appJar)
                    .setMainClass("io.auroraforge.processing.spark." + job.getJobType())
                    .setConf(SparkLauncher.DRIVER_MEMORY,   driverMemory)
                    .setConf(SparkLauncher.EXECUTOR_MEMORY, executorMemory)
                    .setConf("spark.executor.cores",        executorCores)
                    .setConf("spark.sql.shuffle.partitions","50")
                    .setConf("spark.speculation",           "true")
                    // Pass job arguments as Spark configuration entries
                    ;

            jobArgs.forEach((k, v) -> launcher.setConf("spark.auroraforge." + k, v));

            Process sparkProcess = launcher.launch();
            int exitCode = sparkProcess.waitFor();

            if (exitCode == 0) {
                jobRegistry.put(job.getId(), job.withStatus(BatchJobStatus.SUCCEEDED));
                log.info("Spark job succeeded: jobId={}", job.getId());
            } else {
                String stderr = new String(sparkProcess.getErrorStream().readAllBytes());
                jobRegistry.put(job.getId(), job.withStatus(BatchJobStatus.FAILED)
                                                 .withErrorMessage(stderr));
                log.error("Spark job failed: jobId={} exitCode={}", job.getId(), exitCode);
            }

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            jobRegistry.put(job.getId(), job.withStatus(BatchJobStatus.FAILED)
                                             .withErrorMessage(e.getMessage()));
            log.error("Failed to launch Spark job: jobId={}", job.getId(), e);
        }
    }

    public BatchJob getJobStatus(String jobId) {
        BatchJob job = jobRegistry.get(jobId);
        if (job == null) throw new IllegalArgumentException("Unknown jobId: " + jobId);
        return job;
    }
}
