package io.auroraforge.observability.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for declarative audit logging via {@link AuditLogAspect}.
 *
 * The aspect intercepts the annotated method, captures its outcome (SUCCESS /
 * FAILURE), and writes a structured record to the AUDIT log.
 *
 * The actor and requestId are resolved from the MDC (set by
 * MdcRequestContextFilter); the tenantId is extracted from an argument
 * annotated with {@link AuditTenantId} when present.
 *
 * Example:
 * <pre>
 *   {@literal @}AuditLog(
 *       eventType  = AuditEventType.KEY_ROTATION_INITIATED,
 *       resource   = "KEY",
 *       action     = "Rotate encryption key"
 *   )
 *   public void rotateKey(String tenantId, String keyId) { ... }
 * </pre>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** Audit event classification. */
    AuditEventType eventType();

    /** Human-readable resource label (e.g. "KEY", "TENANT", "DR"). */
    String resource() default "";

    /** Human-readable description of the operation. */
    String action() default "";

    /**
     * When true, failures are logged at ERROR level and include the exception
     * message in the {@code detail} field.  Default is true.
     */
    boolean captureFailures() default true;
}
