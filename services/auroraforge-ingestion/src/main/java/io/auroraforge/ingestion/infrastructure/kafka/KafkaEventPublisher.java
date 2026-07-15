package io.auroraforge.ingestion.infrastructure.kafka;

import io.auroraforge.avro.DataClassification;
import io.auroraforge.avro.DataEvent;
import io.auroraforge.avro.EventStatus;
import io.auroraforge.core.application.port.out.EventPublisherPort;
import io.auroraforge.core.domain.event.DataEventCreated;
import io.auroraforge.core.domain.event.DomainEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Infrastructure adapter: publishes domain events to Kafka as Avro SpecificRecords.
 *
 * Topic routing:
 *   All DataEventCreated events → {topicPrefix}.raw
 *
 * Key = tenantId (partition locality: all events for a tenant go to the same partition).
 *
 * The Avro {@link DataEvent} type is generated from
 * auroraforge-avro/src/main/avro/DataEvent.avsc and registered in the Schema Registry
 * with BACKWARD_TRANSITIVE compatibility.
 *
 * Resilience: wrapped in Resilience4j CircuitBreaker + Retry (instance: kafka-publisher).
 * Tombstone publishing is provided for saga compensation — see {@link #publishTombstone}.
 */
@Slf4j
@Component
public class KafkaEventPublisher implements EventPublisherPort {

    private static final String CB_NAME    = "kafka-publisher";
    private static final String RETRY_NAME = "kafka-publisher";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String                        topicPrefix;
    private final CircuitBreaker                cb;
    private final Retry                         retry;

    public KafkaEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${auroraforge.kafka.topic-prefix:auroraforge.events}") String topicPrefix,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry) {

        this.kafkaTemplate = kafkaTemplate;
        this.topicPrefix   = topicPrefix;
        this.cb            = cbRegistry.circuitBreaker(CB_NAME);
        this.retry         = retryRegistry.retry(RETRY_NAME);
    }

    // ── EventPublisherPort ─────────────────────────────────────────────────

    @Override
    public void publish(DomainEvent event) {
        publishAll(List.of(event));
    }

    @Override
    public void publishAll(List<DomainEvent> events) {
        if (events.isEmpty()) return;
        Retry.decorateRunnable(retry,
                CircuitBreaker.decorateRunnable(cb,
                        () -> events.forEach(this::doPublish))).run();
    }

    // ── Saga-aware publishing: returns partition@offset for audit trail ────

    /**
     * Publishes a {@link DataEvent} Avro record and returns the partition@offset
     * string synchronously (blocks until the broker ACK is received).
     * Used by {@link io.auroraforge.ingestion.application.saga.step.PublishEventStep}.
     */
    public String publishAndGetOffset(DataEvent avroEvent, Map<String, String> extraHeaders) {
        String topic = topicPrefix + ".raw";
        ProducerRecord<String, Object> record =
                new ProducerRecord<>(topic, avroEvent.getTenantId(), avroEvent);

        extraHeaders.forEach((k, v) ->
                record.headers().add(new RecordHeader(k, v.getBytes(StandardCharsets.UTF_8))));

        try {
            SendResult<String, Object> result =
                    kafkaTemplate.send(record).get(); // sync — saga step awaits ACK
            String offset = result.getRecordMetadata().partition()
                    + "@" + result.getRecordMetadata().offset();
            log.info("kafka.publish topic={} key={} offset={}", topic, avroEvent.getTenantId(), offset);
            return offset;
        } catch (Exception e) {
            throw new RuntimeException("Kafka publish failed for sagaId="
                    + extraHeaders.get("x-saga-id"), e);
        }
    }

    /**
     * Publishes a tombstone (null value) to the raw topic keyed by aggregateId.
     * Log compaction will eventually remove the record.
     * Used by {@link io.auroraforge.ingestion.application.saga.step.PublishEventStep} compensation.
     */
    public void publishTombstone(String tenantId, String aggregateId, Map<String, String> headers) {
        String topic = topicPrefix + ".raw";
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, aggregateId, null);
        headers.forEach((k, v) ->
                record.headers().add(new RecordHeader(k, v.getBytes(StandardCharsets.UTF_8))));
        kafkaTemplate.send(record).whenComplete((r, ex) -> {
            if (ex != null) {
                log.error("kafka.tombstone.failed topic={} key={}", topic, aggregateId, ex);
            } else {
                log.info("kafka.tombstone.sent topic={} key={}", topic, aggregateId);
            }
        });
    }

    // ── Internal ───────────────────────────────────────────────────────────

    private void doPublish(DomainEvent event) {
        DataEvent avroEvent = toAvro(event);
        String topic  = topicPrefix + ".raw";
        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(topic, avroEvent.getTenantId(), avroEvent);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("kafka.publish.failed eventType={} topic={}", event.eventType(), topic, ex);
            } else {
                log.debug("kafka.publish.ok eventType={} topic={} partition={} offset={}",
                        event.eventType(), topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }

    private DataEvent toAvro(DomainEvent event) {
        if (event instanceof DataEventCreated created) {
            return DataEvent.newBuilder()
                    .setId(created.eventId())
                    .setTenantId(created.tenantId())             // String in the record
                    .setSourceSystem(created.schemaName())       // schema name as source-system proxy
                    .setEventType(created.eventType())
                    .setClassification(DataClassification.valueOf(
                            created.classification().name()))
                    .setStatus(EventStatus.PUBLISHED)
                    .setSchemaVersion(String.valueOf(created.schemaVersion()))
                    .setIdempotencyKey(created.eventId())        // eventId used as idempotency key
                    .setMetadata(Map.of())
                    .setPayloadSizeBytes(0)
                    .setCreatedAt(created.occurredAt().toEpochMilli())
                    .build();
        }
        throw new IllegalArgumentException(
                "Unsupported domain event type for Avro serialization: " + event.eventType());
    }
}
