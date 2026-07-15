package io.auroraforge.core.application.port.out;

import io.auroraforge.core.domain.model.TenantId;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

/**
 * Output port for cloud-agnostic object storage operations.
 *
 * Implementations (adapters):
 *  - AwsS3StorageAdapter  (aws profile)
 *  - AzureBlobStorageAdapter (azure profile)
 *  - LocalMinioStorageAdapter (local / docker profile)
 *
 * All keys are namespaced by tenantId to enforce tenant isolation at the
 * storage layer. The underlying adapter prepends the tenant prefix.
 */
public interface CloudObjectStoragePort {

    /**
     * Uploads bytes and returns the canonical object key (path) in the bucket/container.
     * The implementation may perform multi-part upload for large objects.
     *
     * @param tenantId  owning tenant — used to namespace the key
     * @param key       relative object key (e.g., "events/2024/01/event-id.avro")
     * @param data      raw bytes (already encrypted if classification requires it)
     * @param metadata  arbitrary string metadata persisted alongside the object
     * @return          full object key as stored (tenantId-prefixed)
     */
    String upload(TenantId tenantId, String key, byte[] data, Map<String, String> metadata);

    /**
     * Streams object content. Callers are responsible for closing the stream.
     * Returns empty if the object does not exist.
     */
    Optional<InputStream> download(TenantId tenantId, String key);

    /**
     * Permanently deletes the object. No-op if it does not exist.
     */
    void delete(TenantId tenantId, String key);

    /**
     * Returns {@code true} if the object exists and the caller has read access.
     */
    boolean exists(TenantId tenantId, String key);

    /**
     * Retrieves object metadata without downloading the body.
     * Returns empty if the object does not exist.
     */
    Optional<Map<String, String>> getMetadata(TenantId tenantId, String key);

    /**
     * Generates a pre-signed URL valid for {@code ttlSeconds}.
     * Used for serving large files to clients without routing through the service.
     */
    String generatePresignedUrl(TenantId tenantId, String key, int ttlSeconds);
}
