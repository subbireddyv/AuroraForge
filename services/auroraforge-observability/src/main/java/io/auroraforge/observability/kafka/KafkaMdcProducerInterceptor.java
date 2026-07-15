package io.auroraforge.observability.kafka;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Kafka producer interceptor that propagates the current SLF4J MDC context
 * as Kafka record headers so consuming services can reconstruct the trace.
 *
 * Headers injected (matching MDC key names from MdcRequestContextFilter):
 *   X-Request-ID   ← MDC "requestId"
 *   X-Tenant-ID    ← MDC "tenantId"
 *   X-Trace-ID     ← MDC "traceId"
 *   X-Span-ID      ← MDC "spanId"
 *   X-User-ID      ← MDC "userId"
 *
 * Registration in application.yml (ingestion / processing services):
 * <pre>
 *   spring.kafka.producer.properties:
 *     interceptor.classes: io.auroraforge.observability.kafka.KafkaMdcProducerInterceptor
 * </pre>
 *
 * Values are UTF-8 encoded byte arrays so they survive across language
 * boundaries (Python, Go consumers reading AuroraForge topics).
 */
public class KafkaMdcProducerInterceptor<K, V> implements ProducerInterceptor<K, V> {

    private static final String HDR_REQUEST_ID = "X-Request-ID";
    private static final String HDR_TENANT_ID  = "X-Tenant-ID";
    private static final String HDR_TRACE_ID   = "X-Trace-ID";
    private static final String HDR_SPAN_ID    = "X-Span-ID";
    private static final String HDR_USER_ID    = "X-User-ID";

    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        injectHeader(record, HDR_REQUEST_ID, MDC.get("requestId"));
        injectHeader(record, HDR_TENANT_ID,  MDC.get("tenantId"));
        injectHeader(record, HDR_TRACE_ID,   MDC.get("traceId"));
        injectHeader(record, HDR_SPAN_ID,    MDC.get("spanId"));
        injectHeader(record, HDR_USER_ID,    MDC.get("userId"));
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        // no-op — we only need to propagate on send
    }

    @Override
    public void close() { }

    @Override
    public void configure(Map<String, ?> configs) { }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void injectHeader(ProducerRecord<K, V> record, String name, String value) {
        if (value != null && !value.isBlank() && !"–".equals(value)) {
            // Only set if not already present — downstream retries may have pre-set it
            if (record.headers().lastHeader(name) == null) {
                record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
