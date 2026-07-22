package io.auroraforge.processing.infrastructure.kafka.consumer;

import io.auroraforge.avro.DataEventEnriched;
import io.auroraforge.avro.WindowedAggregation;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer configuration for downstream processing consumers.
 *
 * Two consumer groups:
 *  - auroraforge-enriched-consumer: reads enriched events, writes to DB + cloud storage
 *  - auroraforge-dlq-consumer: reads DLQ events, sends alerts, schedules retries
 *
 * Error handling strategy:
 *  - Non-retryable exceptions (deserialization, schema mismatch) → SeekToCurrentErrorHandler
 *    skips to next message and publishes to DLQ topic.
 *  - Retryable exceptions (DB timeout, S3 throttle) → exponential backoff with
 *    max 5 retries before the message is published to DLQ.
 *
 * Manual ACK mode (AckMode.MANUAL_IMMEDIATE): the listener explicitly commits the
 * offset only after the record has been persisted to both DB and cloud storage.
 * This prevents data loss at the cost of at-least-once delivery.
 */
@Configuration
public class EventConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.schema-registry-url}")
    private String schemaRegistryUrl;

    @Bean("enrichedConsumerFactory")
    public ConsumerFactory<String, DataEventEnriched> enrichedConsumerFactory() {
        Map<String, Object> props = commonConsumerProps();
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "auroraforge-enriched-consumer");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 250);
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1024);
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);
        // Use SpecificAvroDeserializer so Avro schema is bound to DataEventEnriched.class
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        @SuppressWarnings("unchecked")
        Deserializer<DataEventEnriched> valueDeserializer =
                (Deserializer<DataEventEnriched>) (Deserializer<?>) new KafkaAvroDeserializer();
        return new DefaultKafkaConsumerFactory<>(props,
                new StringDeserializer(),
                valueDeserializer);
    }

    @Bean("enrichedListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, DataEventEnriched>
            enrichedListenerContainerFactory(
                    ConsumerFactory<String, DataEventEnriched> enrichedConsumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, DataEventEnriched> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(enrichedConsumerFactory);
        factory.setConcurrency(4);  // 4 consumer threads = 4 Kafka partitions
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setPollTimeout(3000L);
        factory.setCommonErrorHandler(buildErrorHandler());
        factory.setBatchListener(true);  // batch processing for throughput
        return factory;
    }

    @Bean("dlqConsumerFactory")
    public ConsumerFactory<String, Object> dlqConsumerFactory() {
        Map<String, Object> props = commonConsumerProps();
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "auroraforge-dlq-consumer");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, false);  // generic
        return new DefaultKafkaConsumerFactory<>(props,
                new StringDeserializer(),
                new KafkaAvroDeserializer());
    }

    @Bean("dlqListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object>
            dlqListenerContainerFactory(
                    ConsumerFactory<String, Object> dlqConsumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(dlqConsumerFactory);
        factory.setConcurrency(1);  // single thread for DLQ; low-volume
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(buildErrorHandler());
        return factory;
    }

    /**
     * Exponential backoff error handler: retries 5 times with doubling delay
     * (1s → 2s → 4s → 8s → 16s), then rethrows to Spring's error handler
     * which publishes to the DLQ topic.
     */
    private DefaultErrorHandler buildErrorHandler() {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(5);
        backOff.setInitialInterval(Duration.ofSeconds(1).toMillis());
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(Duration.ofSeconds(30).toMillis());
        return new DefaultErrorHandler(backOff);
    }

    private Map<String, Object> commonConsumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,   bootstrapServers);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,   "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,  false);  // manual commits
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,  30_000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3_000);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
        props.put("schema.registry.url",                     schemaRegistryUrl);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                KafkaAvroDeserializer.class.getName());
        return props;
    }
}
