package io.auroraforge.observability.audit;

import java.time.Instant;

/**
 * Immutable value object representing a single auditable action.
 *
 * All fields are serialized as top-level JSON keys in the AUDIT log.
 * Null fields are omitted from the JSON output by the Logstash encoder.
 *
 * @param eventType    classification from {@link AuditEventType}
 * @param outcome      SUCCESS | FAILURE | PARTIAL
 * @param actor        authenticated user ID (sub claim), or "SYSTEM" for scheduled jobs
 * @param tenantId     tenant context; null for platform-wide events
 * @param resource     the resource being acted upon (e.g. "KEY:key-abc", "TENANT:t1")
 * @param action       human-readable description of the operation
 * @param requestId    correlation ID from the HTTP request (null for async jobs)
 * @param sourceIp     client IP; null for internal service-to-service calls
 * @param detail       additional context (never include credentials or PII)
 * @param timestamp    wall-clock time of the event
 */
public record AuditEvent(
        AuditEventType eventType,
        String         outcome,
        String         actor,
        String         tenantId,
        String         resource,
        String         action,
        String         requestId,
        String         sourceIp,
        String         detail,
        Instant        timestamp
) {
    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE = "FAILURE";
    public static final String OUTCOME_PARTIAL = "PARTIAL";
    public static final String ACTOR_SYSTEM    = "SYSTEM";

    public static Builder builder(AuditEventType eventType) {
        return new Builder(eventType);
    }

    public static final class Builder {
        private final AuditEventType eventType;
        private String outcome   = OUTCOME_SUCCESS;
        private String actor     = ACTOR_SYSTEM;
        private String tenantId;
        private String resource;
        private String action;
        private String requestId;
        private String sourceIp;
        private String detail;
        private Instant timestamp = Instant.now();

        private Builder(AuditEventType eventType) { this.eventType = eventType; }

        public Builder outcome(String outcome)     { this.outcome   = outcome;   return this; }
        public Builder actor(String actor)         { this.actor     = actor;     return this; }
        public Builder tenantId(String tenantId)   { this.tenantId  = tenantId;  return this; }
        public Builder resource(String resource)   { this.resource  = resource;  return this; }
        public Builder action(String action)       { this.action    = action;    return this; }
        public Builder requestId(String requestId) { this.requestId = requestId; return this; }
        public Builder sourceIp(String sourceIp)   { this.sourceIp  = sourceIp;  return this; }
        public Builder detail(String detail)       { this.detail    = detail;    return this; }
        public Builder failure()                   { this.outcome   = OUTCOME_FAILURE; return this; }

        public AuditEvent build() {
            return new AuditEvent(eventType, outcome, actor, tenantId,
                    resource, action, requestId, sourceIp, detail, timestamp);
        }
    }
}
