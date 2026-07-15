package io.auroraforge.observability.audit;

import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Writes structured audit records to the dedicated AUDIT logger.
 *
 * The AUDIT logger is configured in logback-base.xml with its own rolling
 * appender (audit.log) and additivity=false, so records never appear in the
 * operational log and are retained for the full 90-day compliance window.
 *
 * Every field in {@link AuditEvent} is emitted as a top-level JSON key so
 * SIEM tools can parse events without field extraction rules.
 *
 * Usage:
 * <pre>
 *   auditLogger.log(AuditEvent.builder(AuditEventType.KEY_ROTATION_COMPLETED)
 *       .actor(userId)
 *       .tenantId(tenantId)
 *       .resource("KEY:" + keyId)
 *       .action("Rotated encryption key")
 *       .build());
 * </pre>
 */
@Service
public class AuditLogger {

    // Name must match the <logger name="AUDIT" ...> entry in logback-base.xml
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    public void log(AuditEvent event) {
        if (!AUDIT.isInfoEnabled()) return;

        AUDIT.info("audit-event",
                StructuredArguments.keyValue("eventType",  event.eventType().name()),
                StructuredArguments.keyValue("outcome",    event.outcome()),
                StructuredArguments.keyValue("actor",      event.actor()),
                StructuredArguments.keyValue("tenantId",   event.tenantId()),
                StructuredArguments.keyValue("resource",   event.resource()),
                StructuredArguments.keyValue("action",     event.action()),
                StructuredArguments.keyValue("requestId",  coalesce(event.requestId(), MDC.get("requestId"))),
                StructuredArguments.keyValue("sourceIp",   event.sourceIp()),
                StructuredArguments.keyValue("detail",     event.detail()),
                StructuredArguments.keyValue("timestamp",  event.timestamp().toString()));
    }

    public void success(AuditEventType type, String actor, String tenantId,
                        String resource, String action) {
        log(AuditEvent.builder(type)
                .actor(actor)
                .tenantId(tenantId)
                .resource(resource)
                .action(action)
                .build());
    }

    public void failure(AuditEventType type, String actor, String tenantId,
                        String resource, String action, String detail) {
        log(AuditEvent.builder(type)
                .actor(actor)
                .tenantId(tenantId)
                .resource(resource)
                .action(action)
                .detail(detail)
                .failure()
                .build());
    }

    private String coalesce(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
