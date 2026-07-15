package io.auroraforge.sync.domain.port;

import io.auroraforge.sync.domain.model.CloudHealth;

/**
 * Output port: probes the availability and latency of a cloud provider.
 *
 * Implementations:
 *  - {@link io.auroraforge.sync.infrastructure.aws.AwsCloudHealthAdapter} — HeadBucket on S3
 *  - {@link io.auroraforge.sync.infrastructure.azure.AzureCloudHealthAdapter} — Cosmos DB read
 */
public interface CloudHealthPort {

    /** Returns the cloud provider identifier (e.g., "aws", "azure"). */
    String cloudProvider();

    /** Performs a live connectivity probe and returns the health snapshot. */
    CloudHealth probe();
}
