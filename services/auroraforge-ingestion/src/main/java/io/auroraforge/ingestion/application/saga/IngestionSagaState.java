package io.auroraforge.ingestion.application.saga;

/**
 * State machine for the Data Ingestion Saga.
 *
 * STARTED → ENCRYPTED → STORED → PERSISTED → PUBLISHED → COMPLETED
 *
 * Any forward state can transition to its corresponding *_FAILED terminal,
 * which triggers compensating transactions in reverse order.
 */
public enum IngestionSagaState {

    STARTED,

    ENCRYPTING,
    ENCRYPT_FAILED,

    STORING,         // uploading to object storage (S3 / Blob)
    STORE_FAILED,

    PERSISTING,      // writing to PostgreSQL
    PERSIST_FAILED,

    PUBLISHING,      // publishing Avro event to Kafka
    PUBLISH_FAILED,

    COMPLETED,
    COMPENSATING,
    COMPENSATION_FAILED,
    COMPENSATED
}
