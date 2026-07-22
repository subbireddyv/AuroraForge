package io.auroraforge.observability.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.semconv.ResourceAttributes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * OpenTelemetry SDK bootstrap configuration.
 * Configures: tracer provider, OTLP gRPC exporter, W3C trace context propagation.
 *
 * In production, the agent-based auto-instrumentation replaces most of this config.
 * This bean-based approach is provided for environments where the Java agent is not used.
 */
@Configuration
public class OpenTelemetryConfig {

    @Value("${spring.application.name:auroraforge-service}")
    private String serviceName;

    @Value("${otel.exporter.otlp.endpoint:http://otel-collector:4317}")
    private String otlpEndpoint;

    @Value("${management.tracing.sampling.probability:1.0}")
    private double samplingProbability;

    @Bean
    public OpenTelemetry openTelemetry() {
        Resource serviceResource = Resource.getDefault().merge(
                Resource.builder()
                        .put(ResourceAttributes.SERVICE_NAME, serviceName)
                        .put(ResourceAttributes.SERVICE_VERSION,
                             getClass().getPackage().getImplementationVersion() != null
                             ? getClass().getPackage().getImplementationVersion() : "dev")
                        .build());

        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(otlpEndpoint)
                .setTimeout(Duration.ofSeconds(5))
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(serviceResource)
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter)
                        .setScheduleDelay(100, TimeUnit.MILLISECONDS)
                        .setMaxExportBatchSize(512)
                        .setMaxQueueSize(2048)
                        .build())
                .setSampler(buildSampler())
                .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(
                        TextMapPropagator.composite(
                                W3CTraceContextPropagator.getInstance(),
                                io.opentelemetry.extension.trace.propagation.B3Propagator.injectingMultiHeaders())))
                .buildAndRegisterGlobal();
    }

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer(serviceName, "1.0.0");
    }

    private Sampler buildSampler() {
        if (samplingProbability >= 1.0) return Sampler.alwaysOn();
        if (samplingProbability <= 0.0) return Sampler.alwaysOff();
        return Sampler.traceIdRatioBased(samplingProbability);
    }
}
