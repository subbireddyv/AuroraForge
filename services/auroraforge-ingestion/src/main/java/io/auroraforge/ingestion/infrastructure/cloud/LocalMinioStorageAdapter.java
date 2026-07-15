package io.auroraforge.ingestion.infrastructure.cloud;

import io.auroraforge.core.application.port.out.CloudObjectStoragePort;
import io.auroraforge.core.domain.model.TenantId;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * MinIO implementation of {@link CloudObjectStoragePort} for local development.
 *
 * MinIO exposes an S3-compatible API, so behaviour is identical to the AWS
 * adapter without real S3 latency or cost. Uses the MinIO Java SDK directly
 * (rather than the AWS SDK pointed at MinIO) to support MinIO-specific features
 * like bucket notifications.
 *
 * Bucket "auroraforge-raw" is created by the docker-compose init container
 * (mc mb minio/auroraforge-raw).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "auroraforge.cloud.provider", havingValue = "LOCAL")
public class LocalMinioStorageAdapter implements CloudObjectStoragePort {

    private static final String TENANT_PREFIX = "tenants/";

    private final MinioClient minioClient;
    private final String      bucket;

    public LocalMinioStorageAdapter(
            @Value("${auroraforge.local.minio.endpoint:http://minio:9000}")   String endpoint,
            @Value("${auroraforge.local.minio.access-key:minioadmin}")        String accessKey,
            @Value("${auroraforge.local.minio.secret-key:minioadmin}")        String secretKey,
            @Value("${auroraforge.local.minio.bucket:auroraforge-raw}")       String bucket) {
        this.bucket = bucket;
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    @Override
    public String upload(TenantId tenantId, String key, byte[] data, Map<String, String> metadata) {
        String fullKey = buildKey(tenantId, key);
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(fullKey)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .userMetadata(metadata != null ? metadata : Map.of())
                    .build());
            log.debug("[LOCAL] Uploaded {} bytes to minio://{}/{}", data.length, bucket, fullKey);
            return fullKey;
        } catch (Exception e) {
            throw new RuntimeException("MinIO upload failed for key: " + fullKey, e);
        }
    }

    @Override
    public Optional<InputStream> download(TenantId tenantId, String key) {
        try {
            return Optional.of(minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(buildKey(tenantId, key))
                    .build()));
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) return Optional.empty();
            throw new RuntimeException("MinIO download failed", e);
        } catch (Exception e) {
            throw new RuntimeException("MinIO download failed", e);
        }
    }

    @Override
    public void delete(TenantId tenantId, String key) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket).object(buildKey(tenantId, key)).build());
        } catch (Exception e) {
            throw new RuntimeException("MinIO delete failed", e);
        }
    }

    @Override
    public boolean exists(TenantId tenantId, String key) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(buildKey(tenantId, key)).build());
            return true;
        } catch (ErrorResponseException e) {
            return !"NoSuchKey".equals(e.errorResponse().code())
                    && !"NoSuchObject".equals(e.errorResponse().code());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Optional<Map<String, String>> getMetadata(TenantId tenantId, String key) {
        try {
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(buildKey(tenantId, key)).build());
            return Optional.of(new HashMap<>(stat.userMetadata()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public String generatePresignedUrl(TenantId tenantId, String key, int ttlSeconds) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(buildKey(tenantId, key))
                    .expiry(ttlSeconds, TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("MinIO presigned URL generation failed", e);
        }
    }

    private String buildKey(TenantId tenantId, String key) {
        return TENANT_PREFIX + tenantId.value() + "/" + key;
    }
}
