package io.auroraforge.processing.infrastructure.kafka.streams;

import io.auroraforge.avro.DataEventEnriched;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Transformer;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.state.KeyValueStore;

import java.time.Duration;
import java.time.Instant;

/**
 * Stateful burst anomaly detector using a sliding-window event rate baseline.
 *
 * Algorithm:
 *  - Maintains an exponentially weighted moving average (EWMA) of per-tenant
 *    event count per 5-minute window in "tenant-ewma-store".
 *  - On each event, computes the current window rate vs the EWMA baseline.
 *  - Anomaly score = currentRate / (ewmaBaseline * SPIKE_THRESHOLD_MULTIPLIER)
 *    clamped to [0.0, 1.0].
 *  - A Punctuator fires every 5 minutes to:
 *    a) update the EWMA with the completed window count
 *    b) reset the current window counter in "tenant-event-counts"
 *    c) emit a null forward (aggregation happens in the topology branch)
 *
 * State stores used (both registered in EventProcessingTopology):
 *  - "tenant-event-counts" (KV<String, Long>): current window count
 *  - "tenant-ewma-store"   (KV<String, Double>): EWMA baseline per tenant
 *
 * The EWMA decay factor α = 0.3 gives ~3-window (15 min) effective memory,
 * long enough to be stable under bursty traffic but fast enough to adapt to
 * genuine load increases.
 */
@Slf4j
public class AnomalyDetectionTransformer
        implements Transformer<String, DataEventEnriched, KeyValue<String, DataEventEnriched>> {

    private static final String  COUNT_STORE       = "tenant-event-counts";
    private static final String  EWMA_STORE        = "tenant-ewma-store";
    private static final double  ALPHA             = 0.3;   // EWMA decay factor
    private static final double  SPIKE_MULTIPLIER  = 3.0;   // 3× baseline = anomaly
    private static final double  ANOMALY_THRESHOLD = 0.8;   // score above which anomalyDetected=true
    private static final long    WINDOW_MS         = Duration.ofMinutes(5).toMillis();

    private ProcessorContext               context;
    private KeyValueStore<String, Long>    countStore;
    private KeyValueStore<String, Double>  ewmaStore;

    @Override
    public void init(ProcessorContext context) {
        this.context   = context;
        this.countStore = context.getStateStore(COUNT_STORE);
        this.ewmaStore  = context.getStateStore(EWMA_STORE);

        // Punctuator: fires at every window boundary to update EWMA and reset counts
        context.schedule(Duration.ofMinutes(5), PunctuationType.WALL_CLOCK_TIME,
                timestamp -> rotateWindow());
    }

    @Override
    public KeyValue<String, DataEventEnriched> transform(String tenantId, DataEventEnriched event) {
        Long currentCount = countStore.get(tenantId);
        if (currentCount == null) currentCount = 0L;

        // EWMA-based anomaly score
        Double ewmaBaseline = ewmaStore.get(tenantId);
        double score = computeAnomalyScore(currentCount, ewmaBaseline);
        boolean detected = score >= ANOMALY_THRESHOLD;

        if (detected) {
            log.warn("Anomaly detected: tenant={} windowCount={} ewmaBaseline={} score={}",
                    tenantId, currentCount, ewmaBaseline, score);
        }

        // Mutate the enriched event with anomaly fields (Avro records are mutable)
        event.setAnomalyScore(score);
        event.setAnomalyDetected(detected);

        return KeyValue.pair(tenantId, event);
    }

    @Override
    public void close() {
        // Kafka Streams manages state store lifecycle
    }

    private void rotateWindow() {
        long now = Instant.now().toEpochMilli();
        log.debug("Rotating anomaly detection window at {}", now);

        // Iterate all tenants currently in the count store
        try (var iterator = countStore.all()) {
            while (iterator.hasNext()) {
                var entry    = iterator.next();
                String tenantId   = entry.key;
                long   windowCount = entry.value;

                // Update EWMA: ewma_new = α * windowCount + (1-α) * ewma_old
                Double oldEwma = ewmaStore.get(tenantId);
                double newEwma = oldEwma == null
                        ? windowCount
                        : ALPHA * windowCount + (1.0 - ALPHA) * oldEwma;

                ewmaStore.put(tenantId, newEwma);
                countStore.put(tenantId, 0L);   // reset window counter

                log.debug("Window rotated: tenant={} windowCount={} newEwma={}",
                        tenantId, windowCount, newEwma);
            }
        }
    }

    private double computeAnomalyScore(long currentCount, Double ewmaBaseline) {
        if (ewmaBaseline == null || ewmaBaseline < 1.0) {
            // No baseline yet — first window, cannot detect anomaly
            return 0.0;
        }
        double spike = currentCount / (ewmaBaseline * SPIKE_MULTIPLIER);
        return Math.min(spike, 1.0);
    }
}
