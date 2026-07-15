package io.auroraforge.processing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.EnableKafkaStreams;

/**
 * AuroraForge Processing Service.
 *
 * Dual processing model:
 *  1. Kafka Streams – stateful real-time aggregations (tumbling / sliding windows)
 *  2. Spark – scheduled batch jobs for historical aggregations and ML features
 *
 * Port: 8082
 */
@SpringBootApplication
@EnableKafka
@EnableKafkaStreams
@ConfigurationPropertiesScan
public class ProcessingServiceApplication {

    public static void main(String[] args) {
        System.setProperty("spring.threads.virtual.enabled", "true");
        SpringApplication.run(ProcessingServiceApplication.class, args);
    }
}
