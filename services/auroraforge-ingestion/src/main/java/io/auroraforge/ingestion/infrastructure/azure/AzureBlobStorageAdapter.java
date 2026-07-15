package io.auroraforge.ingestion.infrastructure.azure;

import com.azure.core.http.rest.Response;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.*;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import io.auroraforge.core.application.port.out.CloudObjectStoragePort;
import io.auroraforge.core.config.cloud.AzureProperties;
import io.auroraforge.core.domain.model.TenantId;
import io.auroraforge.ingestion.infrastructure.CloudStorageUnavailableException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Azure Blob Storage implementation of {@link CloudObjectStoragePort}.
 *
 * All blobs are uploaded with {@link AccessTier#COOL} for raw events and
 * {@link AccessTier#ARCHIVE} for long-term retention (lifecycle rules in Terraform
 * handle the automatic tiering after {@code N} days). Customer-managed key
 * encryption is configured at the storage account level via Terraform, so
 * the SDK does not need to specify it per-request.
 *
 * Tenant isolation uses the same key-prefix strategy as the AWS adapter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "auroraforge.cloud.provider", havingValue = "AZURE")
public class AzureBlobStorageAdapter implements CloudObjectStoragePort {

    private static final String TENANT_PREFIX = "tenants/";

    private final BlobServiceClient blobServiceClient;
    private final AzureProperties   azureProps;

    @Override
    @CircuitBreaker(name = "azureBlob", fallbackMethod = "uploadFallback")
    @Bulkhead(name = "azureBlob", fallbackMethod = "uploadFallback")
    @Retry(name = "azureBlob")
    public String upload(TenantId tenantId, String key, byte[] data, Map<String, String> metadata) {
        String blobName = buildKey(tenantId, key);
        BlobClient blobClient = getContainerClient().getBlobClient(blobName);

        Map<String, String> sanitizedMetadata = sanitizeMetadata(metadata);

        blobClient.upload(new ByteArrayInputStream(data), data.length, true);
        blobClient.setMetadata(sanitizedMetadata);
        blobClient.setAccessTier(AccessTier.COOL);

        log.debug("Uploaded {} bytes to container {}/{}", data.length,
                azureProps.blob().rawContainer(), blobName);
        return blobName;
    }

    @Override
    @CircuitBreaker(name = "azureBlob", fallbackMethod = "downloadFallback")
    @Bulkhead(name = "azureBlob", fallbackMethod = "downloadFallback")
    @Retry(name = "azureBlob")
    public Optional<InputStream> download(TenantId tenantId, String key) {
        try {
            BlobClient blobClient = getContainerClient().getBlobClient(buildKey(tenantId, key));
            if (!blobClient.exists()) return Optional.empty();
            return Optional.of(blobClient.openInputStream());
        } catch (Exception e) {
            log.warn("Blob download failed for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    @CircuitBreaker(name = "azureBlob")
    public void delete(TenantId tenantId, String key) {
        getContainerClient()
                .getBlobClient(buildKey(tenantId, key))
                .deleteIfExists();
    }

    @Override
    @CircuitBreaker(name = "azureBlob")
    public boolean exists(TenantId tenantId, String key) {
        return getContainerClient()
                .getBlobClient(buildKey(tenantId, key))
                .exists();
    }

    @Override
    @CircuitBreaker(name = "azureBlob")
    public Optional<Map<String, String>> getMetadata(TenantId tenantId, String key) {
        BlobClient blobClient = getContainerClient().getBlobClient(buildKey(tenantId, key));
        if (!blobClient.exists()) return Optional.empty();
        return Optional.of(blobClient.getProperties().getMetadata());
    }

    @Override
    public String generatePresignedUrl(TenantId tenantId, String key, int ttlSeconds) {
        BlobClient blobClient = getContainerClient().getBlobClient(buildKey(tenantId, key));
        BlobServiceSasSignatureValues sasValues = new BlobServiceSasSignatureValues(
                OffsetDateTime.now().plusSeconds(ttlSeconds),
                new BlobSasPermission().setReadPermission(true));
        return blobClient.getBlobUrl() + "?" + blobClient.generateSas(sasValues);
    }

    // ── Fallback methods ──────────────────────────────────────────────────────

    private String uploadFallback(TenantId tenantId, String key, byte[] data,
                                   Map<String, String> metadata, Exception cause) {
        log.error("Azure Blob upload fallback triggered for key {}/{}: {}",
                tenantId.value(), key, cause.getMessage());
        throw new CloudStorageUnavailableException("Azure Blob upload unavailable", cause);
    }

    private Optional<InputStream> downloadFallback(TenantId tenantId, String key, Exception cause) {
        log.warn("Azure Blob download fallback triggered for key {}/{}: {}",
                tenantId.value(), key, cause.getMessage());
        return Optional.empty();
    }

    // ── Container / key helpers ───────────────────────────────────────────────

    private BlobContainerClient getContainerClient() {
        return blobServiceClient.getBlobContainerClient(azureProps.blob().rawContainer());
    }

    private String buildKey(TenantId tenantId, String key) {
        return TENANT_PREFIX + tenantId.value() + "/" + key;
    }

    /**
     * Azure metadata keys cannot contain hyphens — replace with underscores.
     * Also enforces the 8KB total metadata size limit by truncating values.
     */
    private Map<String, String> sanitizeMetadata(Map<String, String> metadata) {
        Map<String, String> result = new HashMap<>();
        if (metadata == null) return result;
        metadata.forEach((k, v) -> {
            String sanitizedKey = k.replace("-", "_");
            String truncatedVal = v != null && v.length() > 256 ? v.substring(0, 256) : v;
            result.put(sanitizedKey, truncatedVal);
        });
        return result;
    }
}
