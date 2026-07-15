package io.auroraforge.observability.config;

import io.auroraforge.observability.aspect.TracingAspect;
import io.auroraforge.observability.audit.AuditLogAspect;
import io.auroraforge.observability.audit.AuditLogger;
import io.auroraforge.observability.logging.HttpRequestLoggingFilter;
import io.auroraforge.observability.logging.MdcRequestContextFilter;
import io.auroraforge.observability.metrics.AuroraForgeMetrics;
import io.auroraforge.observability.resilience.CircuitBreakerEventLogger;
import io.auroraforge.observability.security.SecurityHeadersFilter;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Spring Boot auto-configuration for the AuroraForge observability module.
 *
 * Registered in META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
 * so any service that declares auroraforge-observability as a Maven dependency
 * automatically gets tracing, metrics, structured logging, security headers,
 * audit logging, and circuit-breaker event logging configured without any
 * {@code @Import} or {@code @ComponentScan} directives.
 *
 * All beans are conditional so that service-specific overrides take precedence:
 *  - ConditionalOnMissingBean: allows overriding individual beans.
 *  - ConditionalOnProperty(auroraforge.observability.enabled, default=true): allows
 *    opt-out for test contexts via application-test.yml.
 *  - ConditionalOnClass guards against optional dependencies not on the classpath.
 */
@AutoConfiguration
@ConditionalOnProperty(
        name = "auroraforge.observability.enabled",
        havingValue = "true",
        matchIfMissing = true
)
@ConditionalOnClass(Tracer.class)
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class ObservabilityAutoConfiguration {

    // ── OpenTelemetry / Tracing ───────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(OpenTelemetryConfig.class)
    public OpenTelemetryConfig openTelemetryConfig() {
        return new OpenTelemetryConfig();
    }

    @Bean
    @ConditionalOnMissingBean(TracingAspect.class)
    public TracingAspect tracingAspect(Tracer tracer) {
        return new TracingAspect(tracer);
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(AuroraForgeMetrics.class)
    @ConditionalOnClass(MeterRegistry.class)
    public AuroraForgeMetrics auroraForgeMetrics(MeterRegistry meterRegistry) {
        return new AuroraForgeMetrics(meterRegistry);
    }

    // ── Structured Logging Filters ────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(MdcRequestContextFilter.class)
    public MdcRequestContextFilter mdcRequestContextFilter() {
        return new MdcRequestContextFilter();
    }

    @Bean
    @ConditionalOnMissingBean(HttpRequestLoggingFilter.class)
    public HttpRequestLoggingFilter httpRequestLoggingFilter() {
        return new HttpRequestLoggingFilter();
    }

    // ── Security Headers ──────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(SecurityHeadersFilter.class)
    public SecurityHeadersFilter securityHeadersFilter() {
        return new SecurityHeadersFilter();
    }

    // ── Audit Logging ─────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(AuditLogger.class)
    public AuditLogger auditLogger() {
        return new AuditLogger();
    }

    @Bean
    @ConditionalOnMissingBean(AuditLogAspect.class)
    public AuditLogAspect auditLogAspect(AuditLogger auditLogger) {
        return new AuditLogAspect(auditLogger);
    }

    // ── Resilience4j Circuit Breaker Event Logging ────────────────────────────

    @Bean
    @ConditionalOnMissingBean(CircuitBreakerEventLogger.class)
    @ConditionalOnClass(CircuitBreakerRegistry.class)
    public CircuitBreakerEventLogger circuitBreakerEventLogger(CircuitBreakerRegistry registry) {
        return new CircuitBreakerEventLogger(registry);
    }
}
