package io.auroraforge.processing.infrastructure.kafka;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration;
import org.springframework.kafka.config.KafkaStreamsConfiguration;

import java.util.Map;

/**
 * Kafka Streams application configuration.
 *
 * Key settings:
 *  - processing.guarantee=exactly_once_v2: EOS with the optimized 2PC protocol
 *  - state store: RocksDB (default) on local disk, backed by changelog topics
 *  - commit.interval.ms=100: frequent commits for low-latency state updates
 */
@Configuration
public class KafkaStreamsConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.schema-registry-url}")
    private String schemaRegistryUrl;

    @Bean(name = KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
    public KafkaStreamsConfiguration kafkaStreamsConfig() {
        // Map.of(...) is limited to 10 key/value pairs - use Map.ofEntries instead.
        return new KafkaStreamsConfiguration(Map.ofEntries(
                Map.entry(StreamsConfig.APPLICATION_ID_CONFIG,        "auroraforge-processing-streams"),
                Map.entry(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG,     bootstrapServers),
                Map.entry(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,   Serdes.String().getClass()),
                Map.entry(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.ByteArray().getClass()),

                // Exactly-once processing (requires Kafka broker >= 2.5)
                Map.entry(StreamsConfig.PROCESSING_GUARANTEE_CONFIG,  StreamsConfig.EXACTLY_ONCE_V2),

                // State store
                Map.entry(StreamsConfig.REPLICATION_FACTOR_CONFIG,    3),
                Map.entry(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG,    100L),

                // Consumer tuning
                Map.entry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,    "earliest"),
                Map.entry(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,   30000),
                Map.entry(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3000),

                // Schema Registry
                Map.entry(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl),

                // Thread count: one per Kafka partition for parallelism
                Map.entry(StreamsConfig.NUM_STREAM_THREADS_CONFIG,    4),

                // Enable RocksDB metrics export
                Map.entry(StreamsConfig.METRICS_RECORDING_LEVEL_CONFIG, "DEBUG")
        ));
    }
}
