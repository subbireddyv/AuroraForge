package io.auroraforge.core.domain.model;

/**
 * Value object representing a tenant in the multi-tenant AuroraForge platform.
 * All data is scoped to a tenant; the ID also serves as the Cosmos DB partition key.
 */
public record TenantId(String value) {

    public TenantId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TenantId must not be blank");
        }
        if (!value.matches("[a-zA-Z0-9_-]{1,128}")) {
            throw new IllegalArgumentException(
                    "TenantId must contain only alphanumeric characters, dashes, or underscores (max 128 chars)");
        }
    }

    public static TenantId of(String value) {
        return new TenantId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
