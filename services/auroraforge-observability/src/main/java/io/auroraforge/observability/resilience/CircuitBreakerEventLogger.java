package io.auroraforge.observability.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.argument.StructuredArguments;
import org.springframework.stereotype.Component;

/**
 * Subscribes to every CircuitBreaker registered in the Resilience4j registry
 * and emits a structured log line on each state transition and call event.
 *
 * Log events emitted:
 *
 *   circuit-breaker-state-change  — CLOSED→OPEN, OPEN→HALF_OPEN, HALF_OPEN→CLOSED
 *   circuit-breaker-open          — WARN level; includes failureRate%
 *   circuit-breaker-reset         — INFO level; circuit recovered
 *   circuit-breaker-failure       — DEBUG level; individual call failure
 *   circuit-breaker-success       — TRACE level; individual call success
 *   circuit-breaker-ignored       — DEBUG level; ignored exception
 *
 * All events include the circuit-breaker name as a structured field so
 * Elasticsearch/Grafana dashboards can filter by downstream dependency.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerEventLogger {

    private final CircuitBreakerRegistry registry;

    @PostConstruct
    public void registerListeners() {
        // Register listeners on all existing CBs and any future ones added at runtime
        registry.getAllCircuitBreakers().forEach(this::attachListeners);
        registry.getEventPublisher()
                .onEntryAdded(event -> attachListeners(event.getAddedEntry()));
    }

    private void attachListeners(CircuitBreaker cb) {
        cb.getEventPublisher()

            .onStateTransition(event -> {
                CircuitBreaker.StateTransition transition = event.getStateTransition();
                boolean opening = transition == CircuitBreaker.StateTransition.CLOSED_TO_OPEN
                        || transition == CircuitBreaker.StateTransition.HALF_OPEN_TO_OPEN;

                if (opening) {
                    log.warn("circuit-breaker-open",
                            StructuredArguments.keyValue("circuitBreaker", event.getCircuitBreakerName()),
                            StructuredArguments.keyValue("transition",     transition.name()),
                            StructuredArguments.keyValue("failureRate",    cb.getMetrics().getFailureRate()));
                } else if (transition == CircuitBreaker.StateTransition.HALF_OPEN_TO_CLOSED) {
                    log.info("circuit-breaker-reset",
                            StructuredArguments.keyValue("circuitBreaker", event.getCircuitBreakerName()),
                            StructuredArguments.keyValue("transition",     transition.name()));
                } else {
                    log.info("circuit-breaker-state-change",
                            StructuredArguments.keyValue("circuitBreaker", event.getCircuitBreakerName()),
                            StructuredArguments.keyValue("transition",     transition.name()));
                }
            })

            .onCallNotPermitted(event ->
                log.warn("circuit-breaker-call-rejected",
                        StructuredArguments.keyValue("circuitBreaker", event.getCircuitBreakerName()),
                        StructuredArguments.keyValue("state",          cb.getState().name())))

            .onError(event -> {
                if (log.isDebugEnabled()) {
                    log.debug("circuit-breaker-failure",
                            StructuredArguments.keyValue("circuitBreaker", event.getCircuitBreakerName()),
                            StructuredArguments.keyValue("durationMs",     event.getElapsedDuration().toMillis()),
                            StructuredArguments.keyValue("error",          event.getThrowable().getMessage()));
                }
            })

            .onSuccess(event -> {
                if (log.isTraceEnabled()) {
                    log.trace("circuit-breaker-success",
                            StructuredArguments.keyValue("circuitBreaker", event.getCircuitBreakerName()),
                            StructuredArguments.keyValue("durationMs",     event.getElapsedDuration().toMillis()));
                }
            })

            .onIgnoredError(event -> {
                if (log.isDebugEnabled()) {
                    log.debug("circuit-breaker-ignored-error",
                            StructuredArguments.keyValue("circuitBreaker", event.getCircuitBreakerName()),
                            StructuredArguments.keyValue("error",          event.getThrowable().getMessage()));
                }
            });
    }
}
