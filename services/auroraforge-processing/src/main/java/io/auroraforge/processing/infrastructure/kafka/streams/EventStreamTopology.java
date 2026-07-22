package io.auroraforge.processing.infrastructure.kafka.streams;

import io.auroraforge.avro.DataEvent;
import io.auroraforge.avro.DataEventEnriched;
import io.auroraforge.avro.WindowedAggregation;
import io.auroraforge.core.config.kafka.KafkaTopicProperties;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * Production Kafka Streams topology for the AuroraForge real-time pipeline.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │                     RAW EVENTS TOPIC (Avro)                            │
 * │                   key=tenantId, value=DataEvent                        │
 * └──────────────────────────────┬──────────────────────────────────────────┘
 *                                │
 *                    ┌───────────▼────────────┐
 *                    │   NULL / POISON FILTER  │ ── DLQ topic
 *                    └───────────┬────────────┘
 *                                │ valid events
 *                    ┌───────────▼────────────┐
 *                    │  CLASSIFICATION BRANCH  │
 *                    └──────┬───────────┬──────┘
 *               FAST PATH   │           │  ENRICHMENT PATH
 *           (PUBLIC/INTERNAL)           │  (CONFIDENTIAL/RESTRICTED)
 *                    │                  │
 *                    │       ┌──────────▼────────────┐
 *                    │       │  EventEnrichment       │ (stateful: RocksDB count store)
 *                    │       │  Transformer           │
 *                    │       └──────────┬────────────┘
 *                    │                  │
 *                    │       ┌──────────▼────────────┐
 *                    │       │  AnomalyDetection      │ (stateful: RocksDB EWMA store)
 *                    │       │  Transformer           │
 *                    │       └──────────┬────────────┘
 *                    │                  │
 *                    └─────────┬────────┘   MERGE
 *                              │
 *               ┌──────────────▼──────────────────┐
 *               │       ENRICHED EVENTS STREAM      │
 *               └──────┬──────────────┬────────────┘
 *                       │              │
 *          ┌────────────▼──┐    ┌──────▼──────────────────┐
 *          │  enriched     │    │   5-min TUMBLING WINDOW  │
 *          │  topic sink   │    │   grouped by tenantId    │
 *          └───────────────┘    │   WindowedAggregation    │
 *                               └──────────────────────────┘
 *                                         │
 *                                ┌────────▼──────────┐
 *                                │  aggregations sink │
 *                                └───────────────────┘
 *
 * Exactly-once semantics: guaranteed by StreamsConfig.EXACTLY_ONCE_V2.
 * State stores are persisted to changelog topics with replication factor 3.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class EventStreamTopology {

    private final KafkaTopicProperties        topicProps;

    @Value("${spring.kafka.properties.schema-registry-url}")
    private String schemaRegistryUrl;

    @Value("${spring.application.name:auroraforge-processing}")
    private String appId;

    @Bean
    @SuppressWarnings("unchecked")
    public KStream<String, DataEventEnriched> eventPipeline(StreamsBuilder builder) {
        Map<String, String> serdeConfig = Collections.singletonMap(
                "schema.registry.url", schemaRegistryUrl);

        // ── Avro Serdes ────────────────────────────────────────────────────
        SpecificAvroSerde<DataEvent>        rawSerde      = new SpecificAvroSerde<>();
        SpecificAvroSerde<DataEventEnriched> enrichedSerde = new SpecificAvroSerde<>();
        rawSerde.configure(serdeConfig, false);
        enrichedSerde.configure(serdeConfig, false);

        // ── State stores (registered before topology is built) ─────────────
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore("tenant-event-counts"),
                Serdes.String(), Serdes.Long()));

        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore("tenant-ewma-store"),
                Serdes.String(), Serdes.Double()));

        // ── Source stream ───────────────────────────────────────────────────
        KStream<String, DataEvent> sourceStream = builder.stream(
                topicProps.events().name(),
                Consumed.with(Serdes.String(), rawSerde)
                        .withName("source-raw-events")
                        .withOffsetResetPolicy(Topology.AutoOffsetReset.EARLIEST));

        // ── DLQ branch: null payloads from deserialization errors ──────────
        sourceStream
                .filter((k, v) -> v == null, Named.as("filter-poison-events"))
                .to(topicProps.dlq().name(),
                    Produced.with(Serdes.String(), rawSerde)
                            .withName("sink-poison-dlq"));

        KStream<String, DataEvent> validStream = sourceStream
                .filter((k, v) -> v != null, Named.as("filter-valid-events"))
                .peek((k, v) -> log.trace("Received: id={} tenant={} type={}",
                        v.getId(), k, v.getEventType()),
                        Named.as("peek-incoming"));

        // ── Classification branches ────────────────────────────────────────
        Map<String, KStream<String, DataEvent>> branches = validStream.split(Named.as("branch-"))
                .branch((k, v) -> isFastPath(v.getClassification().name()),
                        Branched.as("fast-path"))
                .branch((k, v) -> isEnrichmentPath(v.getClassification().name()),
                        Branched.as("enrichment-path"))
                .defaultBranch(Branched.as("unknown"));

        KStream<String, DataEvent> fastPathStream        = branches.get("branch-fast-path");
        KStream<String, DataEvent> enrichmentPathStream  = branches.get("branch-enrichment-path");
        KStream<String, DataEvent> unknownStream         = branches.get("branch-unknown");

        // Route unknown classifications to DLQ
        unknownStream.to(topicProps.dlq().name(),
                Produced.with(Serdes.String(), rawSerde)
                        .withName("sink-unknown-dlq"));

        // ── Fast path: minimal enrichment (no state store access) ──────────
        KStream<String, DataEventEnriched> fastEnriched = fastPathStream
                .mapValues((k, event) -> buildMinimalEnriched(event), Named.as("map-fast-enrich"));

        // ── Enrichment path: stateful transformer → anomaly detector ───────
        KStream<String, DataEventEnriched> enrichedStream = enrichmentPathStream
                .transform(EventEnrichmentTransformer::new,
                        Named.as("transform-enrich"),
                        "tenant-event-counts")
                .transform(AnomalyDetectionTransformer::new,
                        Named.as("transform-anomaly"),
                        "tenant-event-counts", "tenant-ewma-store");

        // ── Merge both paths ───────────────────────────────────────────────
        KStream<String, DataEventEnriched> mergedStream = fastEnriched
                .merge(enrichedStream, Named.as("merge-enriched-streams"))
                .peek((k, v) -> log.debug(
                        "Enriched: id={} tenant={} anomaly={} score={}",
                        v.getId(), k, v.getAnomalyDetected(), v.getAnomalyScore()),
                        Named.as("peek-enriched"));

        // ── Sink 1: enriched events topic (consumed by EnrichedEventConsumer) ─
        mergedStream.to(topicProps.eventsEnriched().name(),
                Produced.with(Serdes.String(), enrichedSerde)
                        .withName("sink-enriched-events"));

        // ── Sink 2: 5-minute tumbling window aggregation ───────────────────
        buildWindowedAggregation(mergedStream, enrichedSerde, serdeConfig);

        log.info("Kafka Streams topology built. Source: {} → Enriched: {} → Agg: {}",
                topicProps.events().name(),
                topicProps.eventsEnriched().name(),
                topicProps.eventsProcessed().name());

        return mergedStream;
    }

    /**
     * Builds the tumbling-window aggregation branch and writes to the processed topic.
     *
     * Window: 5 minutes tumbling, no grace period (late events go to DLQ).
     * The KTable changelog is emitted to a dedicated "_aggregations" suffixed topic.
     */
    private void buildWindowedAggregation(
            KStream<String, DataEventEnriched> stream,
            SpecificAvroSerde<DataEventEnriched> enrichedSerde,
            Map<String, String> serdeConfig) {

        SpecificAvroSerde<WindowedAggregation> aggSerde = new SpecificAvroSerde<>();
        aggSerde.configure(serdeConfig, false);

        TimeWindows windows = TimeWindows
                .ofSizeAndGrace(Duration.ofMinutes(5), Duration.ofSeconds(30));

        stream
                .groupByKey(Grouped.with(Serdes.String(), enrichedSerde)
                        .withName("group-for-aggregation"))
                .windowedBy(windows)
                .aggregate(
                        () -> WindowedAggregation.newBuilder()
                                .setTenantId("")
                                .setWindowStart(0L)
                                .setWindowEnd(0L)
                                .setWindowDurationMs(Duration.ofMinutes(5).toMillis())
                                .setTotalEventCount(0L)
                                .setCountByClassification(new java.util.HashMap<>())
                                .setCountByEventType(new java.util.HashMap<>())
                                .setTotalPayloadBytes(0L)
                                .setAnomalyCount(0L)
                                .setDlqCount(0L)
                                .setP50LatencyMs(null)
                                .setP99LatencyMs(null)
                                .setComputedAt(Instant.now())
                                .build(),
                        (tenantId, event, agg) -> {
                            agg.setTenantId(tenantId);
                            agg.setWindowStart(Instant.now()
                                    .minusMillis(Duration.ofMinutes(5).toMillis())
                                    .toEpochMilli());
                            agg.setWindowEnd(Instant.now().toEpochMilli());
                            agg.setTotalEventCount(agg.getTotalEventCount() + 1);
                            agg.setTotalPayloadBytes(
                                    agg.getTotalPayloadBytes() + event.getPayloadSizeBytes());

                            // Count by classification
                            agg.getCountByClassification().merge(
                                    event.getClassification().name(), 1L, Long::sum);

                            // Count by event type (top-N tracking)
                            agg.getCountByEventType().merge(event.getEventType(), 1L, Long::sum);

                            if (event.getAnomalyDetected()) {
                                agg.setAnomalyCount(agg.getAnomalyCount() + 1);
                            }
                            agg.setComputedAt(Instant.now());
                            return agg;
                        },
                        Named.as("aggregate-window"),
                        Materialized.<String, WindowedAggregation,
                                WindowStore<org.apache.kafka.common.utils.Bytes, byte[]>>
                                as("windowed-aggregation-store")
                                .withKeySerde(Serdes.String())
                                .withValueSerde(aggSerde))
                .toStream(Named.as("agg-to-stream"))
                .map((wk, agg) -> KeyValue.pair(wk.key(), agg),
                        Named.as("map-strip-window-key"))
                .to(topicProps.eventsProcessed().name(),
                    Produced.with(Serdes.String(), aggSerde)
                            .withName("sink-aggregations"));
    }

    private DataEventEnriched buildMinimalEnriched(DataEvent event) {
        return DataEventEnriched.newBuilder()
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
                .setMetadata(new java.util.HashMap<>(event.getMetadata()))
                .setEnrichedAt(Instant.now())
                .setProcessorId(appId)
                .setWindowedEventCount(0L)
                .setAnomalyScore(0.0)
                .setAnomalyDetected(false)
                .setEnrichmentLatencyMs(0L)
                .setProcessingPath(io.auroraforge.avro.ProcessingPath.FAST_PATH)
                .build();
    }

    private boolean isFastPath(String classification) {
        return "PUBLIC".equals(classification) || "INTERNAL".equals(classification);
    }

    private boolean isEnrichmentPath(String classification) {
        return "CONFIDENTIAL".equals(classification) || "RESTRICTED".equals(classification);
    }
}
