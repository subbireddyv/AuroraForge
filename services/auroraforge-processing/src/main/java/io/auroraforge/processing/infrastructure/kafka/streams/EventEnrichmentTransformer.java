package io.auroraforge.processing.infrastructure.kafka.streams;

import io.auroraforge.avro.DataEvent;
import io.auroraforge.avro.DataEventEnriched;
import io.auroraforge.avro.ProcessingPath;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Transformer;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.state.KeyValueStore;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Stateful Kafka Streams transformer that enriches raw DataEvents.
 *
 * State store access:
 *  - "tenant-event-counts" (KeyValueStore<String, Long>): running per-tenant event count
 *    for the current tumbling window. Reset by the AnomalyDetectionTransformer after
 *    each window close.
 *
 * Enrichment adds:
 *  1. windowedEventCount from the state store (how many events this tenant sent so far)
 *  2. processingPath (FAST_PATH for PUBLIC/INTERNAL, ENRICHMENT_PATH for C/R)
 *  3. processorId (streams application instance)
 *  4. enrichedAt timestamp
 *  5. enrichmentLatencyMs (wall-clock)
 *
 * Thread safety: Kafka Streams guarantees single-threaded access per partition,
 * so state store operations are safe without synchronization here.
 */
@Slf4j
public class EventEnrichmentTransformer implements Transformer<String, DataEvent, KeyValue<String, DataEventEnriched>> {

    private static final String COUNT_STORE = "tenant-event-counts";

    private ProcessorContext              context;
    private KeyValueStore<String, Long>   countStore;

    @Override
    public void init(ProcessorContext context) {
        this.context   = context;
        this.countStore = context.getStateStore(COUNT_STORE);
    }

    @Override
    public KeyValue<String, DataEventEnriched> transform(String tenantId, DataEvent event) {
        long startNs = System.nanoTime();

        // Increment the per-tenant window count
        long currentCount = countStore.get(tenantId) != null ? countStore.get(tenantId) : 0L;
        long newCount = currentCount + 1;
        countStore.put(tenantId, newCount);

        // Determine fast vs enrichment path
        ProcessingPath path = switch (event.getClassification().name()) {
            case "PUBLIC", "INTERNAL"             -> ProcessingPath.FAST_PATH;
            case "CONFIDENTIAL", "RESTRICTED"     -> ProcessingPath.ENRICHMENT_PATH;
            default                               -> ProcessingPath.FAST_PATH;
        };

        // Build enriched metadata — preserve original + add enrichment tags
        Map<String, String> enrichedMetadata = new HashMap<>(event.getMetadata());
        enrichedMetadata.put("enricher.version", "1.0");
        enrichedMetadata.put("enricher.stream.thread", Thread.currentThread().getName());
        enrichedMetadata.put("enricher.path", path.name());
        enrichedMetadata.put("enricher.window.count", String.valueOf(newCount));

        Instant enrichedAt = Instant.now();
        long latencyMs  = (System.nanoTime() - startNs) / 1_000_000L;

        DataEventEnriched enriched = DataEventEnriched.newBuilder()
                .setId(event.getId())
                .setTenantId(event.getTenantId())
                .setSourceSystem(event.getSourceSystem())
                .setEventType(event.getEventType())
                .setClassification(event.getClassification())
                .setStatus(event.getStatus())
                .setSchemaVersion(event.getSchemaVersion())
                .setIdempotencyKey(event.getIdempotencyKey())
                .setPayloadSizeBytes(event.getPayloadSizeBytes())
                .setCreatedAt(event.getCreatedAt())
                .setMetadata(enrichedMetadata)
                .setEnrichedAt(enrichedAt)
                .setProcessorId(context.applicationId())
                .setWindowedEventCount(newCount)
                .setAnomalyScore(0.0)     // filled in by AnomalyDetectionTransformer
                .setAnomalyDetected(false)
                .setEnrichmentLatencyMs(latencyMs)
                .setProcessingPath(path)
                .build();

        log.debug("Enriched event: id={} tenant={} path={} windowCount={}",
                event.getId(), tenantId, path, newCount);

        return KeyValue.pair(tenantId, enriched);
    }

    @Override
    public void close() {
        // No resources to close — state store lifecycle is managed by Kafka Streams
    }
}
