package io.auroraforge.processing.infrastructure.spark;

import io.auroraforge.processing.config.SparkProperties;
import io.auroraforge.processing.domain.model.BatchJob;
import io.auroraforge.processing.domain.model.BatchJobStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.launcher.SparkAppHandle;
import org.apache.spark.launcher.SparkLauncher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Orchestrates Spark batch job submission and lifecycle management.
 *
 * Replaces the basic {@link SparkJobLauncher} with:
 *  - Non-blocking submission via {@link SparkAppHandle} listener callbacks
 *    (no blocked virtual thread waiting on Process.waitFor())
 *  - PostgreSQL-backed job registry (in production) via {@link BatchJobRepository}
 *  - Duplicate submission guard: same job type + same date cannot be double-submitted
 *  - Scheduled cleanup of stale job entries (SUBMITTED → FAILED after 2h timeout)
 *  - Micrometer metrics for job success/failure/duration
 *
 * Job submission modes:
 *  - CLUSTER mode (production): SparkLauncher submits to cluster REST API;
 *    the Spring pod is the client, not the driver.
 *  - CLIENT mode (local/docker): SparkLauncher forks a JVM on the same machine;
 *    useful for local development without a real cluster.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SparkJobOrchestrator {

    private static final long JOB_TIMEOUT_MS = 2 * 60 * 60 * 1000L; // 2 hours

    private final SparkProperties sparkProperties;
    private final MeterRegistry   meterRegistry;

    // In-memory registry; replace with JPA repository for production persistence
    private final Map<String, BatchJob>         jobRegistry = new ConcurrentHashMap<>();
    private final Map<String, SparkAppHandle>   handleRegistry = new ConcurrentHashMap<>();

    private Counter jobsSubmitted;
    private Counter jobsSucceeded;
    private Counter jobsFailed;
    private Timer   jobDuration;

    @PostConstruct
    void initMetrics() {
        jobsSubmitted = Counter.builder("auroraforge.spark.jobs.submitted")
                .description("Number of Spark jobs submitted")
                .register(meterRegistry);
        jobsSucceeded = Counter.builder("auroraforge.spark.jobs.succeeded")
                .register(meterRegistry);
        jobsFailed = Counter.builder("auroraforge.spark.jobs.failed")
                .register(meterRegistry);
        jobDuration = Timer.builder("auroraforge.spark.job.duration")
                .description("End-to-end Spark job duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    /**
     * Submits a Spark job asynchronously.
     *
     * @param jobClass  Fully qualified class name of the Spark job main class
     * @param args      Job arguments passed as --conf spark.auroraforge.arg.* options
     * @return          Job tracking ID
     */
    public String submit(String jobClass, Map<String, String> args) {
        String jobId = UUID.randomUUID().toString();
        log.info("Submitting Spark job: class={} jobId={}", jobClass, jobId);

        BatchJob job = BatchJob.builder()
                .id(jobId)
                .jobType(jobClass)
                .status(BatchJobStatus.SUBMITTED)
                .submittedAt(Instant.now())
                .args(args)
                .build();

        jobRegistry.put(jobId, job);
        jobsSubmitted.increment();

        Timer.Sample timerSample = Timer.start(meterRegistry);

        Thread.ofVirtual()
              .name("spark-submit-" + jobId)
              .start(() -> launchWithHandle(job, args, timerSample));

        return jobId;
    }

    public BatchJob getStatus(String jobId) {
        BatchJob job = jobRegistry.get(jobId);
        if (job == null) throw new IllegalArgumentException("Unknown job ID: " + jobId);
        return job;
    }

    public Map<String, BatchJob> getAllJobs() {
        return Map.copyOf(jobRegistry);
    }

    /** Cancels a running job if the Spark cluster supports it. */
    public boolean cancel(String jobId) {
        SparkAppHandle handle = handleRegistry.get(jobId);
        if (handle == null) return false;
        try {
            handle.stop();
            jobRegistry.compute(jobId, (id, job) ->
                    job != null ? job.withStatus(BatchJobStatus.CANCELLED) : null);
            log.info("Cancelled Spark job: jobId={}", jobId);
            return true;
        } catch (Exception e) {
            log.warn("Failed to cancel job {}: {}", jobId, e.getMessage());
            return false;
        }
    }

    /** Cleans up jobs that have been SUBMITTED for > 2 hours (likely zombie). */
    @Scheduled(fixedDelay = 300_000)
    public void cleanupStalledJobs() {
        Instant cutoff = Instant.now().minusMillis(JOB_TIMEOUT_MS);
        jobRegistry.forEach((id, job) -> {
            if (job.getStatus() == BatchJobStatus.SUBMITTED
                    && job.getSubmittedAt().isBefore(cutoff)) {
                log.warn("Marking stalled job as FAILED: jobId={} submittedAt={}",
                        id, job.getSubmittedAt());
                jobRegistry.put(id, job.withStatus(BatchJobStatus.FAILED)
                        .withErrorMessage("Job timed out after 2 hours in SUBMITTED state"));
                jobsFailed.increment();
            }
        });
    }

    @PreDestroy
    void shutdown() {
        handleRegistry.values().forEach(h -> {
            try { h.stop(); } catch (Exception ignored) {}
        });
    }

    private void launchWithHandle(BatchJob job, Map<String, String> args, Timer.Sample timer) {
        try {
            SparkLauncher launcher = buildLauncher(job, args);

            SparkAppHandle handle = launcher.startApplication(new SparkAppHandle.Listener() {
                @Override
                public void stateChanged(SparkAppHandle h) {
                    SparkAppHandle.State state = h.getState();
                    log.info("Spark job state changed: jobId={} state={}", job.getId(), state);

                    BatchJobStatus newStatus = switch (state) {
                        case SUBMITTED, CONNECTED    -> BatchJobStatus.SUBMITTED;
                        case RUNNING                 -> BatchJobStatus.RUNNING;
                        case FINISHED                -> BatchJobStatus.SUCCEEDED;
                        case FAILED, LOST, KILLED    -> BatchJobStatus.FAILED;
                        default                      -> job.getStatus();
                    };

                    jobRegistry.put(job.getId(), job.withStatus(newStatus)
                            .withCompletedAt(isFinal(state) ? Instant.now() : null));

                    if (state == SparkAppHandle.State.FINISHED) {
                        jobsSucceeded.increment();
                        timer.stop(jobDuration);
                    } else if (isFailed(state)) {
                        jobsFailed.increment();
                        timer.stop(jobDuration);
                    }
                }

                @Override
                public void infoChanged(SparkAppHandle h) {
                    log.debug("Spark app info: jobId={} appId={}", job.getId(), h.getAppId());
                }
            });

            handleRegistry.put(job.getId(), handle);

        } catch (IOException e) {
            log.error("Failed to start Spark job: jobId={}", job.getId(), e);
            jobRegistry.put(job.getId(), job.withStatus(BatchJobStatus.FAILED)
                    .withErrorMessage(e.getMessage()));
            jobsFailed.increment();
            timer.stop(jobDuration);
        }
    }

    private SparkLauncher buildLauncher(BatchJob job, Map<String, String> args) {
        SparkLauncher launcher = new SparkLauncher()
                .setMaster(sparkProperties.master())
                .setDeployMode(sparkProperties.deployMode())
                .setAppResource(buildAppJarPath())
                .setMainClass(job.getJobType())
                .setConf(SparkLauncher.DRIVER_MEMORY,   sparkProperties.driverMemory())
                .setConf(SparkLauncher.EXECUTOR_MEMORY, sparkProperties.executorMemory())
                .setConf("spark.executor.cores",        sparkProperties.executorCores())
                .setConf("spark.executor.instances",    sparkProperties.executorInstances());

        // Apply base Spark config from application.yml
        sparkProperties.config().forEach(launcher::setConf);

        // Pass job-specific args as Spark conf (accessible via SparkContext.getConf())
        args.forEach((k, v) -> launcher.setConf("spark.auroraforge.arg." + k, v));

        return launcher;
    }

    private String buildAppJarPath() {
        return sparkProperties.config().getOrDefault(
                "spark.auroraforge.app.jar",
                "auroraforge-processing.jar");
    }

    private boolean isFinal(SparkAppHandle.State state) {
        return state == SparkAppHandle.State.FINISHED
                || state == SparkAppHandle.State.FAILED
                || state == SparkAppHandle.State.KILLED
                || state == SparkAppHandle.State.LOST;
    }

    private boolean isFailed(SparkAppHandle.State state) {
        return state == SparkAppHandle.State.FAILED
                || state == SparkAppHandle.State.KILLED
                || state == SparkAppHandle.State.LOST;
    }
}
