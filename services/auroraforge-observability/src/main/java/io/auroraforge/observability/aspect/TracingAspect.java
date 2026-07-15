package io.auroraforge.observability.aspect;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

/**
 * AOP aspect that wraps all public use-case and adapter methods with an OpenTelemetry span.
 *
 * Pointcut targets:
 *  - All implementations of *UseCase interfaces (application layer)
 *  - All classes in the infrastructure.* packages (port adapters)
 *
 * Span naming convention: {className}.{methodName}
 * Span attributes: class, method, layer (application | infrastructure)
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class TracingAspect {

    private final Tracer tracer;

    @Around("execution(* io.auroraforge..application.service.*.*(..))")
    public Object traceApplicationService(ProceedingJoinPoint pjp) throws Throwable {
        return trace(pjp, "application", SpanKind.INTERNAL);
    }

    @Around("execution(* io.auroraforge..infrastructure..*Adapter.*(..))" +
            " || execution(* io.auroraforge..infrastructure..*Publisher.*(..))")
    public Object traceInfrastructureAdapter(ProceedingJoinPoint pjp) throws Throwable {
        return trace(pjp, "infrastructure", SpanKind.CLIENT);
    }

    private Object trace(ProceedingJoinPoint pjp, String layer, SpanKind kind) throws Throwable {
        MethodSignature sig   = (MethodSignature) pjp.getSignature();
        String spanName       = pjp.getTarget().getClass().getSimpleName()
                                + "." + sig.getName();

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(kind)
                .setAttribute("code.layer",     layer)
                .setAttribute("code.class",     sig.getDeclaringTypeName())
                .setAttribute("code.function",  sig.getName())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            Object result = pjp.proceed();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Throwable t) {
            span.recordException(t);
            span.setStatus(StatusCode.ERROR, t.getMessage());
            throw t;
        } finally {
            span.end();
        }
    }
}
