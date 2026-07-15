package io.auroraforge.core.domain.security;

import io.auroraforge.core.domain.model.DataClassification;

import java.util.Set;

/**
 * Platform-wide roles that govern what a principal may do.
 *
 * Spring Security convention: the JWT "roles" claim holds the bare name (e.g. "DATA_INGEST");
 * Spring converts it to a GrantedAuthority with the "ROLE_" prefix automatically via
 * {@link org.springframework.security.core.authority.SimpleGrantedAuthority}.
 */
public enum AuroraForgeRole {

    /**
     * Super-user: all permissions across all tenants.
     * Strictly internal — never issued to end-user tokens.
     */
    ADMIN(Set.of(DataClassification.values())),

    /** Can POST new data events to /api/v1/tenants/{tenantId}/events. */
    DATA_INGEST(Set.of(DataClassification.PUBLIC, DataClassification.INTERNAL,
                       DataClassification.CONFIDENTIAL, DataClassification.RESTRICTED)),

    /** Can GET events (read-only queries). */
    DATA_QUERY(Set.of(DataClassification.PUBLIC, DataClassification.INTERNAL,
                      DataClassification.CONFIDENTIAL, DataClassification.RESTRICTED)),

    /**
     * Can trigger CMK/DEK rotation and view key history.
     * Maps to /api/v1/keys/** endpoints.
     */
    KEY_MANAGER(Set.of()),

    /** Can access Actuator management endpoints and platform-level dashboards. */
    PLATFORM_OPS(Set.of()),

    /**
     * Service-to-service identity (client_credentials grant).
     * Carries the source service name in the "sub" claim.
     */
    SERVICE_ACCOUNT(Set.of(DataClassification.values()));

    /** Default allowed data classifications for this role (individual tokens may restrict further). */
    private final Set<DataClassification> defaultClassifications;

    AuroraForgeRole(Set<DataClassification> defaultClassifications) {
        this.defaultClassifications = Set.copyOf(defaultClassifications);
    }

    public Set<DataClassification> defaultClassifications() {
        return defaultClassifications;
    }

    /** Spring Security authority string: "ROLE_DATA_INGEST", etc. */
    public String authority() {
        return "ROLE_" + name();
    }
}
