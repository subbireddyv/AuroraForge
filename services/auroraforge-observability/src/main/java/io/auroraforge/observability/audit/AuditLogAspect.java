package io.auroraforge.observability.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * AOP aspect that processes {@link AuditLog} annotations.
 *
 * For every annotated method:
 *   1. Resolves the authenticated actor from the Spring Security context.
 *   2. Extracts tenantId from the first String argument named "tenantId"
 *      (by parameter name, which requires -parameters compiler flag — already
 *      set in the parent POM maven-compiler-plugin config).
 *   3. Proceeds with the actual method.
 *   4. On success: logs outcome=SUCCESS.
 *   5. On exception: logs outcome=FAILURE with the exception message in
 *      {@code detail}, then re-throws so normal error handling still applies.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private final AuditLogger auditLogger;

    @Around("@annotation(auditLog)")
    public Object audit(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {

        String actor    = resolveActor();
        String tenantId = extractTenantId(pjp);
        String resource = auditLog.resource();
        String action   = auditLog.action();

        try {
            Object result = pjp.proceed();

            auditLogger.log(AuditEvent.builder(auditLog.eventType())
                    .actor(actor)
                    .tenantId(tenantId)
                    .resource(resource)
                    .action(action)
                    .requestId(MDC.get("requestId"))
                    .build());

            return result;

        } catch (Exception ex) {
            if (auditLog.captureFailures()) {
                auditLogger.log(AuditEvent.builder(auditLog.eventType())
                        .actor(actor)
                        .tenantId(tenantId)
                        .resource(resource)
                        .action(action)
                        .requestId(MDC.get("requestId"))
                        .detail(ex.getMessage())
                        .failure()
                        .build());
            }
            throw ex;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String resolveActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            return auth.getName();
        }
        return AuditEvent.ACTOR_SYSTEM;
    }

    /**
     * Scans method parameters for the first String parameter named "tenantId".
     * Falls back to MDC "tenantId" (set by MdcRequestContextFilter).
     */
    private String extractTenantId(ProceedingJoinPoint pjp) {
        MethodSignature sig    = (MethodSignature) pjp.getSignature();
        Method          method = sig.getMethod();
        Parameter[]     params = method.getParameters();
        Object[]        args   = pjp.getArgs();

        for (int i = 0; i < params.length; i++) {
            if ("tenantId".equals(params[i].getName()) && args[i] instanceof String s) {
                return s;
            }
        }
        return MDC.get("tenantId");
    }
}
