package io.auroraforge.observability.kafka;

import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Kafka consumer interceptor that restores MDC context from Kafka record headers
 * injected by {@link KafkaMdcProducerInterceptor}.
 *
 * Called by the Kafka consumer thread before the {@code @KafkaListener} method
 * processes the record, giving all downstream log calls a consistent set of
 * correlation IDs matching the originating HTTP request.
 *
 * IMPORTANT: The MDC is set per-record in {@link #onConsume} and is NOT
 * automatically cleared — clearing must happen in the listener method or via
 * a separate mechanism.  Spring Kafka's {@code @AfterRollback} / error handler
 * does not guarantee clearing, so listener methods should use try-finally or
 * rely on the next record's interceptor call to overwrite stale values.
 *
 * Registration in application.yml (ingestion / sync services):
 * <pre>
 *   spring.kafka.consumer.properties:
 *     interceptor.classes: io.auroraforge.observability.kafka.KafkaMdcConsumerInterceptor
 * </pre>
 */
public class KafkaMdcConsumerInterceptor<K, V> implements ConsumerInterceptor<K, V> {

    private static final String HDR_REQUEST_ID = "X-Request-ID";
    private static final String HDR_TENANT_ID  = "X-Tenant-ID";
    private static final String HDR_TRACE_ID   = "X-Trace-ID";
    private static final String HDR_SPAN_ID    = "X-Span-ID";
    private static final String HDR_USER_ID    = "X-User-ID";

    @Override
    public ConsumerRecords<K, V> onConsume(ConsumerRecords<K, V> records) {
        // Apply MDC from the last record in the batch (batch listeners process records
        // individually anyway; for single-record @KafkaListener this is the only record).
        for (ConsumerRecord<K, V> record : records) {
            restoreMdc(record);
        }
        return records;
    }

    @Override
    public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) { }

    @Override
    public void close() { }

    @Override
    public void configure(Map<String, ?> configs) { }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void restoreMdc(ConsumerRecord<K, V> record) {
        setMdcFromHeader(record, HDR_REQUEST_ID, "requestId");
        setMdcFromHeader(record, HDR_TENANT_ID,  "tenantId");
        setMdcFromHeader(record, HDR_TRACE_ID,   "traceId");
        setMdcFromHeader(record, HDR_SPAN_ID,    "spanId");
        setMdcFromHeader(record, HDR_USER_ID,    "userId");
    }

    private void setMdcFromHeader(ConsumerRecord<K, V> record, String headerName, String mdcKey) {
        Header header = record.headers().lastHeader(headerName);
        if (header != null && header.value() != null) {
            MDC.put(mdcKey, new String(header.value(), StandardCharsets.UTF_8));
        }
    }
}
