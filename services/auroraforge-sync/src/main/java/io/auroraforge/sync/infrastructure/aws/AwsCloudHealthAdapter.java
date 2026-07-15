package io.auroraforge.sync.infrastructure.aws;

import io.auroraforge.sync.domain.model.CloudHealth;
import io.auroraforge.sync.domain.port.CloudHealthPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

/**
 * AWS cloud health probe.
 *
 * Performs a HeadBucket call on the processed-data S3 bucket.
 * A successful response confirms: IAM credential validity, VPC/network routing to S3,
 * and bucket existence. Does not transfer data, so cost is negligible.
 *
 * Latency threshold and error-rate threshold for "degraded" determination are
 * defined in {@link CloudHealth#isDegraded()}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwsCloudHealthAdapter implements CloudHealthPort {

    private final S3Client s3Client;

    @Value("${auroraforge.aws.s3.processed-bucket}")
    private String processedBucket;

    @Override
    public String cloudProvider() {
        return "aws";
    }

    @Override
    public CloudHealth probe() {
        long start = System.currentTimeMillis();
        try {
            s3Client.headBucket(HeadBucketRequest.builder()
                    .bucket(processedBucket)
                    .build());
            long latencyMs = System.currentTimeMillis() - start;
            log.debug("AWS health OK: latencyMs={}", latencyMs);
            return CloudHealth.healthy("aws", latencyMs);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            log.warn("AWS health check FAILED after {}ms: {}", latencyMs, e.getMessage());
            return CloudHealth.unreachable("aws", e.getMessage());
        }
    }
}
