package io.auroraforge.ingestion.application.saga.step;

import io.auroraforge.avro.DataClassification;
import io.auroraforge.avro.DataEvent;
import io.auroraforge.avro.EventStatus;
import io.auroraforge.ingestion.application.saga.IngestionSagaContext;
import io.auroraforge.ingestion.application.saga.SagaStep;
import io.auroraforge.ingestion.application.saga.SagaStepException;
import io.auroraforge.ingestion.infrastructure.kafka.KafkaEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Step 4 — Publish the Avro {@link DataEvent} SpecificRecord to the raw Kafka topic.
 *
 * Uses {@link KafkaEventPublisher#publishAndGetOffset} which blocks until the broker ACK
 * is received, giving the saga an exact partition@offset for the audit trail.
 *
 * NOTE: In the target architecture (post-outbox) this step is replaced by Debezium CDC
 * reading from the {@code outbox_events} table written in Step 3 — eliminating the
 * dual-write risk. This class remains for deployments that have not yet enabled CDC.
 *
 * Compensation: publish a tombstone (null-value record) to the same key so log compaction
 * removes the event. Downstream consumers that implement idempotent processing will
 * handle or ignore the tombstone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublishEventStep implements SagaStep {

    private final KafkaEventPublisher kafkaEventPublisher;

    @Override
    public String name() { return "PUBLISH_EVENT"; }

    @Override
    public IngestionSagaContext execute(IngestionSagaContext ctx) throws SagaStepException {
        log.debug("saga={} step={} aggregateId={}", ctx.getSagaId(), name(), ctx.getAggregateId());
        try {
            DataEvent avroEvent = buildAvroEvent(ctx);
            String offset = kafkaEventPublisher.publishAndGetOffset(
                    avroEvent,
                    Map.of(
                            "x-saga-id",    ctx.getSagaId(),
                            "x-request-id", ctx.getRequestId()
                    )
            );
            log.info("saga={} step={} status=ok offset={}", ctx.getSagaId(), name(), offset);
            return ctx.withKafkaOffset(offset);
        } catch (Exception e) {
            throw new SagaStepException(name(), "Kafka publish failed: " + e.getMessage(), e, true);
        }
    }

    @Override
    public void compensate(IngestionSagaContext ctx) {
        if (ctx.getKafkaOffset() == null) return;
        try {
            kafkaEventPublisher.publishTombstone(
                    ctx.getTenantId().value(),
                    ctx.getAggregateId(),
                    Map.of(
                            "x-saga-id",          ctx.getSagaId(),
                            "x-compensation-for", ctx.getKafkaOffset()
                    )
            );
            log.info("saga={} step={} compensation=tombstone-sent offset={}",
                    ctx.getSagaId(), name(), ctx.getKafkaOffset());
        } catch (Exception e) {
            log.error("saga={} step={} compensation=failed error={}",
                    ctx.getSagaId(), name(), e.getMessage(), e);
        }
    }

    private DataEvent buildAvroEvent(IngestionSagaContext ctx) {
        return DataEvent.newBuilder()
                .setId(ctx.getSagaId())
                .setTenantId(ctx.getTenantId().value())
                .setSourceSystem("auroraforge-ingestion")
                .setEventType(ctx.getEventType())
                .setClassification(DataClassification.valueOf(ctx.getClassification().name()))
                .setStatus(EventStatus.PUBLISHED)
                .setSchemaVersion("1.0")
                .setIdempotencyKey(ctx.getRequestId())
                .setMetadata(Map.of(
                        "storageKey",    ctx.getStorageObjectKey() != null ? ctx.getStorageObjectKey() : "",
                        "encryptionKey", ctx.getEncryptionKeyId() != null ? ctx.getEncryptionKeyId() : ""
                ))
                .setPayloadSizeBytes(
                        ctx.getEncryptedPayload() != null ? ctx.getEncryptedPayload().length : 0)
                .setCreatedAt(ctx.getStartedAt().toEpochMilli())
                .build();
    }
}
