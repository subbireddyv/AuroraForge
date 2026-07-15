package io.auroraforge.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AuroraForge Ingestion Service entry point.
 *
 * Responsibilities:
 *  - Accepts real-time events via REST API and Kafka topics
 *  - Validates, encrypts (if classified), and persists to PostgreSQL
 *  - Publishes domain events to Kafka for downstream consumers
 *  - Exposes metrics via Actuator → OpenTelemetry Collector
 *
 * Port assignments:
 *  - HTTP: 8081
 *  - Actuator: 8091 (separate port for internal traffic only)
 */
@SpringBootApplication
@EnableKafka
@EnableScheduling
@ConfigurationPropertiesScan
public class IngestionServiceApplication {

    public static void main(String[] args) {
        // Virtual threads for all Tomcat request handlers (Project Loom)
        System.setProperty("spring.threads.virtual.enabled", "true");
        SpringApplication.run(IngestionServiceApplication.class, args);
    }
}
