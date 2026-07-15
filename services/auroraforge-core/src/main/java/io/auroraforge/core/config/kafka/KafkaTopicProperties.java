package io.auroraforge.core.config.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Kafka topic naming and partition configuration.
 *
 * Bound from: auroraforge.kafka.*
 *
 * Topic names are constructed as: {@code {prefix}.{domain}} so that all
 * AuroraForge topics are grouped under a common prefix in Kafka UI / MSK.
 * Partition counts and replication factors are read by the AdminClient-based
 * topic initialiser at startup (idempotent — skips if topic already exists).
 */
@Validated
@ConfigurationProperties(prefix = "auroraforge.kafka")
public record KafkaTopicProperties(

        String prefix,

        TopicConfig events,

        TopicConfig eventsEnriched,

        TopicConfig eventsProcessed,

        TopicConfig syncCommands,

        TopicConfig dlq,

        int defaultReplicationFactor,

        int defaultMinInsyncReplicas

) {
    public KafkaTopicProperties {
        if (prefix == null) prefix = "auroraforge";
        if (events == null)
            events = new TopicConfig(prefix + ".events.raw", 12, 3);
        if (eventsEnriched == null)
            eventsEnriched = new TopicConfig(prefix + ".events.enriched", 12, 3);
        if (eventsProcessed == null)
            eventsProcessed = new TopicConfig(prefix + ".events.processed", 6, 3);
        if (syncCommands == null)
            syncCommands = new TopicConfig(prefix + ".sync.commands", 6, 3);
        if (dlq == null)
            dlq = new TopicConfig(prefix + ".dlq", 3, 3);
        if (defaultReplicationFactor == 0) defaultReplicationFactor = 3;
        if (defaultMinInsyncReplicas == 0) defaultMinInsyncReplicas = 2;
    }

    /**
     * Per-topic settings. Retention is expressed in milliseconds (-1 = infinite).
     */
    public record TopicConfig(
            String name,
            int partitions,
            int replicationFactor,
            long retentionMs,
            boolean compacted
    ) {
        public TopicConfig(String name, int partitions, int replicationFactor) {
            this(name, partitions, replicationFactor, 604_800_000L /* 7 days */, false);
        }
    }
}
