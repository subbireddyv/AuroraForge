package io.auroraforge.ingestion.infrastructure;

/**
 * Thrown by cloud storage adapters when the circuit is open or the bulkhead is
 * full, indicating that the underlying cloud storage is temporarily unavailable.
 *
 * Callers (CrossCloudSyncService, IngestionEventHandler) catch this to route
 * the payload to the DLQ rather than propagating a 5xx to the client.
 */
public class CloudStorageUnavailableException extends RuntimeException {

    public CloudStorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public CloudStorageUnavailableException(String message) {
        super(message);
    }
}
