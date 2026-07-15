package io.auroraforge.ingestion.infrastructure.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Duration;

/**
 * Resilience4j configuration for all outbound calls in the ingestion service.
 *
 * Named instances:
 *  - "kafka-publisher"   : Kafka producer calls
 *  - "db-storage"        : PostgreSQL write operations
 *  - "key-management"    : KMS / Key Vault encryption calls
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig base = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)                // Open after 50% failures
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .permittedNumberOfCallsInHalfOpenState(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(IOException.class, RuntimeException.class)
                .build();

        CircuitBreakerConfig kafkaConfig = CircuitBreakerConfig.from(base)
                .waitDurationInOpenState(Duration.ofSeconds(10))  // Kafka recovers faster
                .build();

        CircuitBreakerConfig kmsConfig = CircuitBreakerConfig.from(base)
                .failureRateThreshold(20)  // KMS failures are expensive – be more sensitive
                .build();

        return CircuitBreakerRegistry.of(base, java.util.Map.of(
                "kafka-publisher", kafkaConfig,
                "db-storage",      base,
                "key-management",  kmsConfig
        ));
    }

    @Bean
    public RetryRegistry retryRegistry() {
        RetryConfig base = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(200))
                .retryExceptions(IOException.class)
                .ignoreExceptions(IllegalArgumentException.class)
                .build();

        RetryConfig kmsRetry = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(io.github.resilience4j.core.IntervalFunction
                        .ofExponentialBackoff(Duration.ofMillis(500), 2.0))
                .build();

        return RetryRegistry.of(base, java.util.Map.of(
                "kafka-publisher",  base,
                "db-storage",       base,
                "key-management",   kmsRetry
        ));
    }

    @Bean
    public TimeLimiterRegistry timeLimiterRegistry() {
        TimeLimiterConfig config = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(5))
                .cancelRunningFuture(true)
                .build();

        return TimeLimiterRegistry.of(config);
    }
}
