package io.auroraforge.ingestion.infrastructure.cloud;

import io.auroraforge.core.config.cloud.AzureProperties;
import io.auroraforge.core.config.cloud.AwsProperties;
import io.auroraforge.core.config.cloud.CloudProperties;
import io.auroraforge.core.config.kafka.KafkaTopicProperties;
import io.auroraforge.core.config.retention.DataRetentionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;

/**
 * Root cloud configuration that:
 *  1. Enables all @ConfigurationProperties records via @EnableConfigurationProperties.
 *  2. Creates Kafka topic Admin beans for idempotent topic initialization at startup.
 *  3. Enables Spring Cache for secret caching in cloud secret adapters.
 *
 * This class is the single entry point for all cross-cutting infrastructure
 * configuration — keeping IngestionConfig focused on business-layer wiring.
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties({
        CloudProperties.class,
        AwsProperties.class,
        AzureProperties.class,
        KafkaTopicProperties.class,
        DataRetentionProperties.class
})
public class CloudAdapterConfig {

    private final KafkaTopicProperties kafkaTopicProps;

    public CloudAdapterConfig(KafkaTopicProperties kafkaTopicProps) {
        this.kafkaTopicProps = kafkaTopicProps;
    }

    @Bean
    public KafkaAdmin kafkaAdmin(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configs.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        configs.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "30000");
        return new KafkaAdmin(configs);
    }

    @Bean
    public NewTopic rawEventsTopic() {
        return topicFrom(kafkaTopicProps.events());
    }

    @Bean
    public NewTopic enrichedEventsTopic() {
        return topicFrom(kafkaTopicProps.eventsEnriched());
    }

    @Bean
    public NewTopic processedEventsTopic() {
        return topicFrom(kafkaTopicProps.eventsProcessed());
    }

    @Bean
    public NewTopic syncCommandsTopic() {
        return topicFrom(kafkaTopicProps.syncCommands());
    }

    @Bean
    public NewTopic dlqTopic() {
        return topicFrom(kafkaTopicProps.dlq());
    }

    private NewTopic topicFrom(KafkaTopicProperties.TopicConfig cfg) {
        NewTopic topic = new NewTopic(cfg.name(), cfg.partitions(), (short) cfg.replicationFactor());
        topic.configs(Map.of(
                "retention.ms",              String.valueOf(cfg.retentionMs()),
                "min.insync.replicas",       String.valueOf(kafkaTopicProps.defaultMinInsyncReplicas()),
                "cleanup.policy",            cfg.compacted() ? "compact" : "delete",
                "compression.type",          "snappy",
                "message.timestamp.type",    "LogAppendTime"
        ));
        return topic;
    }
}
