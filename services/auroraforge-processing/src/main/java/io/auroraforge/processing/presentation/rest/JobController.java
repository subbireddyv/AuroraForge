package io.auroraforge.processing.presentation.rest;

import io.auroraforge.processing.domain.model.BatchJob;
import io.auroraforge.processing.infrastructure.spark.SparkJobLauncher;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

/**
 * Presentation layer: REST API for submitting and monitoring Spark batch jobs.
 *
 * POST /api/v1/jobs          → submit a batch job
 * GET  /api/v1/jobs/{jobId}  → poll job status
 */
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {

    private final SparkJobLauncher sparkJobLauncher;

    public record SubmitJobRequest(
            @NotBlank String jobType,
            Map<String, String> args
    ) {}

    public record JobStatusResponse(
            String jobId,
            String jobType,
            String status,
            String submittedAt,
            String completedAt,
            String errorMessage
    ) {}

    @PostMapping
    public ResponseEntity<JobStatusResponse> submitJob(@RequestBody SubmitJobRequest request) {
        String jobId = sparkJobLauncher.submitJob(
                request.jobType(),
                request.args() != null ? request.args() : Map.of());

        return ResponseEntity
                .created(URI.create("/api/v1/jobs/" + jobId))
                .body(toResponse(sparkJobLauncher.getJobStatus(jobId)));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<JobStatusResponse> getJobStatus(@PathVariable String jobId) {
        BatchJob job = sparkJobLauncher.getJobStatus(jobId);
        return ResponseEntity.ok(toResponse(job));
    }

    private JobStatusResponse toResponse(BatchJob job) {
        return new JobStatusResponse(
                job.getId(), job.getJobType(), job.getStatus().name(),
                job.getSubmittedAt() != null ? job.getSubmittedAt().toString() : null,
                job.getCompletedAt() != null ? job.getCompletedAt().toString() : null,
                job.getErrorMessage());
    }
}
