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
        return new KafkaStreamsConfiguration(Map.of(
                StreamsConfig.APPLICATION_ID_CONFIG,        "auroraforge-processing-streams",
                StreamsConfig.BOOTSTRAP_SERVERS_CONFIG,     bootstrapServers,
                StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,   Serdes.String().getClass(),
                StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.ByteArray().getClass(),

                // Exactly-once processing (requires Kafka broker >= 2.5)
                StreamsConfig.PROCESSING_GUARANTEE_CONFIG,  StreamsConfig.EXACTLY_ONCE_V2,

                // State store
                StreamsConfig.REPLICATION_FACTOR_CONFIG,    3,
                StreamsConfig.COMMIT_INTERVAL_MS_CONFIG,    100L,

                // Consumer tuning
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,    "earliest",
                ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,   30000,
                ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3000,

                // Schema Registry
                AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl,

                // Thread count: one per Kafka partition for parallelism
                StreamsConfig.NUM_STREAM_THREADS_CONFIG,    4,

                // Enable RocksDB metrics export
                StreamsConfig.METRICS_RECORDING_LEVEL_CONFIG, "DEBUG"
        ));
    }
}
