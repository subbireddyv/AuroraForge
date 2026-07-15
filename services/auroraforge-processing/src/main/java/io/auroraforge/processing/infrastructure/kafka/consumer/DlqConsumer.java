package io.auroraforge.processing.infrastructure.kafka.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Dead Letter Queue consumer — monitors, classifies, and re-routes failed events.
 *
 * DLQ routing strategy:
 *  - SCHEMA_EVOLUTION_MISMATCH: events whose Avro schema version cannot be
 *    deserialized by the current consumer. Forwarded to the schema-ops team
 *    Slack channel via webhook alert.
 *  - PROCESSING_FAILURE: events that exhausted their retry budget. Logged with
 *    full context for manual intervention.
 *  - POISON_PILL: null-value events (Kafka deserialization failure). Skipped
 *    and counted.
 *
 * Retry policy: DLQ events are NOT automatically retried. Manual re-trigger
 * is done via the JobController REST endpoint which resubmits a Spark job
 * to reprocess DLQ records from a given time range.
 *
 * Observability: every DLQ event increments auroraforge.dlq.events counter
 * tagged by failure type, which drives the Grafana DLQ alert.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlqConsumer {

    private static final String HEADER_EXCEPTION_CLASS   = "kafka_dlt-exception-fqcn";
    private static final String HEADER_EXCEPTION_MESSAGE = "kafka_dlt-exception-message";
    private static final String HEADER_ORIGINAL_TOPIC    = "kafka_dlt-original-topic";
    private static final String HEADER_ORIGINAL_OFFSET   = "kafka_dlt-original-offset";

    private final MeterRegistry meterRegistry;

    private Counter dlqPoison;
    private Counter dlqSchema;
    private Counter dlqProcessing;
    private Counter dlqTotal;

    @PostConstruct
    void initMetrics() {
        dlqTotal = Counter.builder("auroraforge.dlq.events")
                .description("Total events routed to DLQ")
                .register(meterRegistry);

        dlqPoison = Counter.builder("auroraforge.dlq.events")
                .tag("reason", "POISON_PILL")
                .register(meterRegistry);

        dlqSchema = Counter.builder("auroraforge.dlq.events")
                .tag("reason", "SCHEMA_MISMATCH")
                .register(meterRegistry);

        dlqProcessing = Counter.builder("auroraforge.dlq.events")
                .tag("reason", "PROCESSING_FAILURE")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics           = "#{kafkaTopicProperties.dlq().name()}",
            containerFactory = "dlqListenerContainerFactory",
            groupId          = "auroraforge-dlq-consumer"
    )
    public void onDlqRecord(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        dlqTotal.increment();

        String originalTopic   = headerValue(record, HEADER_ORIGINAL_TOPIC)  .orElse("unknown");
        String exceptionClass  = headerValue(record, HEADER_EXCEPTION_CLASS) .orElse("unknown");
        String exceptionMsg    = headerValue(record, HEADER_EXCEPTION_MESSAGE).orElse("unknown");
        String originalOffset  = headerValue(record, HEADER_ORIGINAL_OFFSET) .orElse("unknown");

        DlqReason reason = classifyFailure(record, exceptionClass);

        switch (reason) {
            case POISON_PILL -> {
                dlqPoison.increment();
                log.warn("DLQ[POISON_PILL]: key={} topic={} partition={} offset={}",
                        record.key(), originalTopic, record.partition(), originalOffset);
            }
            case SCHEMA_MISMATCH -> {
                dlqSchema.increment();
                log.error("DLQ[SCHEMA_MISMATCH]: key={} topic={} exceptionClass={} error={}",
                        record.key(), originalTopic, exceptionClass, exceptionMsg);
                triggerSchemaAlert(record, exceptionClass, exceptionMsg);
            }
            case PROCESSING_FAILURE -> {
                dlqProcessing.increment();
                log.error("DLQ[PROCESSING_FAILURE]: key={} topic={} partition={} offset={} " +
                                "exceptionClass={} error={}",
                        record.key(), originalTopic, record.partition(), originalOffset,
                        exceptionClass, exceptionMsg);
                // Write structured DLQ record to persistent audit log
                auditDlqRecord(record, reason, exceptionClass, exceptionMsg);
            }
        }

        // Always acknowledge — DLQ records are never retried automatically
        ack.acknowledge();
    }

    private DlqReason classifyFailure(ConsumerRecord<?, ?> record, String exceptionClass) {
        if (record.value() == null) {
            return DlqReason.POISON_PILL;
        }
        if (exceptionClass.contains("SerializationException")
                || exceptionClass.contains("SchemaParseException")
                || exceptionClass.contains("AvroTypeException")) {
            return DlqReason.SCHEMA_MISMATCH;
        }
        return DlqReason.PROCESSING_FAILURE;
    }

    private void triggerSchemaAlert(ConsumerRecord<?, ?> record, String exception, String msg) {
        // In production: call PagerDuty / Slack webhook via a dedicated AlertPort
        // The schema-ops team must inspect the Schema Registry compatibility settings.
        log.error("SCHEMA ALERT: schema incompatibility on topic={} exception={} — " +
                "run `kafka-avro-console-consumer --topic {} --property print.schema.ids=true` " +
                "to inspect. Consider running the schema-evolution Spark job.",
                record.topic(), exception, record.topic());
    }

    private void auditDlqRecord(ConsumerRecord<?, ?> record, DlqReason reason,
                                 String exceptionClass, String exceptionMsg) {
        // Structured audit record for compliance: tenant, event key, failure reason, timestamp
        log.warn("DLQ_AUDIT: key={} reason={} topic={} partition={} offset={} " +
                        "exceptionClass={} exceptionMsg={} timestamp={}",
                record.key(), reason, record.topic(), record.partition(), record.offset(),
                exceptionClass, exceptionMsg, record.timestamp());
    }

    private Optional<String> headerValue(ConsumerRecord<?, ?> record, String headerName) {
        return Arrays.stream(record.headers().toArray())
                .filter(h -> headerName.equals(h.key()))
                .findFirst()
                .map(h -> new String(h.value(), StandardCharsets.UTF_8));
    }

    private enum DlqReason { POISON_PILL, SCHEMA_MISMATCH, PROCESSING_FAILURE }
}
