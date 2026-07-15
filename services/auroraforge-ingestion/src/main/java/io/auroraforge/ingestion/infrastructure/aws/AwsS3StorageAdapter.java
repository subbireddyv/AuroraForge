package io.auroraforge.ingestion.infrastructure.aws;

import io.auroraforge.core.application.port.out.CloudObjectStoragePort;
import io.auroraforge.core.config.cloud.AwsProperties;
import io.auroraforge.core.domain.model.TenantId;
import io.auroraforge.ingestion.infrastructure.CloudStorageUnavailableException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * S3 implementation of the cloud-agnostic {@link CloudObjectStoragePort}.
 *
 * SSE-KMS is enforced on every PutObject request using the app-data CMK alias.
 * The S3 bucket policy (Terraform) independently denies any unencrypted uploads,
 * so this is defence-in-depth, not the only enforcement point.
 *
 * Tenant isolation: all keys are prefixed with {@code tenants/{tenantId}/} so
 * bucket policies and lifecycle rules can be scoped by prefix.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "auroraforge.cloud.provider", havingValue = "AWS")
public class AwsS3StorageAdapter implements CloudObjectStoragePort {

    private static final String TENANT_PREFIX = "tenants/";

    private final S3Client       s3Client;
    private final S3Presigner    s3Presigner;
    private final AwsProperties  awsProps;

    @Override
    @CircuitBreaker(name = "s3", fallbackMethod = "uploadFallback")
    @Bulkhead(name = "s3", fallbackMethod = "uploadFallback")
    @Retry(name = "s3")
    public String upload(TenantId tenantId, String key, byte[] data, Map<String, String> metadata) {
        String fullKey = buildKey(tenantId, key);
        String bucket  = awsProps.s3().rawBucket();

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(fullKey)
                .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                .ssekmsKeyId(awsProps.kms().appDataKeyAlias())
                .contentLength((long) data.length)
                .metadata(metadata)
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(data));
        log.debug("Uploaded {} bytes to s3://{}/{}", data.length, bucket, fullKey);
        return fullKey;
    }

    @Override
    @CircuitBreaker(name = "s3", fallbackMethod = "downloadFallback")
    @Bulkhead(name = "s3", fallbackMethod = "downloadFallback")
    @Retry(name = "s3")
    public Optional<InputStream> download(TenantId tenantId, String key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(awsProps.s3().rawBucket())
                    .key(buildKey(tenantId, key))
                    .build();
            return Optional.of(s3Client.getObject(request));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    @CircuitBreaker(name = "s3")
    public void delete(TenantId tenantId, String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(awsProps.s3().rawBucket())
                .key(buildKey(tenantId, key))
                .build());
    }

    @Override
    @CircuitBreaker(name = "s3")
    public boolean exists(TenantId tenantId, String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(awsProps.s3().rawBucket())
                    .key(buildKey(tenantId, key))
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    @CircuitBreaker(name = "s3")
    public Optional<Map<String, String>> getMetadata(TenantId tenantId, String key) {
        try {
            HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(awsProps.s3().rawBucket())
                    .key(buildKey(tenantId, key))
                    .build());
            return Optional.of(head.metadata());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public String generatePresignedUrl(TenantId tenantId, String key, int ttlSeconds) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(ttlSeconds))
                .getObjectRequest(r -> r
                        .bucket(awsProps.s3().rawBucket())
                        .key(buildKey(tenantId, key)))
                .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    // ── Fallback methods ──────────────────────────────────────────────────────

    /**
     * Invoked by Resilience4j when the S3 circuit is open or the bulkhead is full.
     * Throws a typed exception so the ingestion service can route the event to the
     * DLQ rather than silently dropping it.
     */
    private String uploadFallback(TenantId tenantId, String key, byte[] data,
                                   Map<String, String> metadata, Exception cause) {
        log.error("S3 upload fallback triggered for key {}/{}: {}",
                tenantId.value(), key, cause.getMessage());
        throw new CloudStorageUnavailableException("S3 upload unavailable", cause);
    }

    private Optional<InputStream> downloadFallback(TenantId tenantId, String key, Exception cause) {
        log.warn("S3 download fallback triggered for key {}/{}: {}",
                tenantId.value(), key, cause.getMessage());
        return Optional.empty();
    }

    // ── Key building ──────────────────────────────────────────────────────────

    private String buildKey(TenantId tenantId, String key) {
        return TENANT_PREFIX + tenantId.value() + "/" + key;
    }
}
