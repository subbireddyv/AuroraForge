package io.auroraforge.processing.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.With;

import java.time.Instant;
import java.util.Map;

/** Tracks a Spark batch job submitted to the cluster. */
@Getter
@Builder
@With
public class BatchJob {
    private final String          id;
    private final String          jobType;
    private final BatchJobStatus  status;
    private final Instant         submittedAt;
    private final Instant         completedAt;
    private final Map<String, String> args;
    private final String          errorMessage;
}
