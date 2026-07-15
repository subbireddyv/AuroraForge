package io.auroraforge.keymgmt.infrastructure.persistence;

import io.auroraforge.core.domain.model.DataClassification;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity: tracks the active CMK key version per tenant per classification.
 *
 * One row per rotation event; the current active version has {@code active = true}.
 * Previous versions are retained with {@code active = false} to support audit queries
 * and to identify which CMK version decrypts historical ciphertext.
 *
 * Optimistic locking via {@code @Version} prevents concurrent rotation races.
 */
@Entity
@Table(
        name = "key_versions",
        indexes = {
                @Index(name = "idx_kv_tenant_class",    columnList = "tenant_id, classification"),
                @Index(name = "idx_kv_active_class",    columnList = "active, classification"),
                @Index(name = "idx_kv_rotated_at_desc", columnList = "rotated_at")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Tenant whose data is encrypted under this key version. */
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    /** Classification tier this version applies to. */
    @Enumerated(EnumType.STRING)
    @Column(name = "classification", length = 32, nullable = false)
    private DataClassification classification;

    /**
     * Cloud-provider key version identifier:
     *  - AWS:   CMK ARN (e.g. arn:aws:kms:us-east-1:123:key/uuid)
     *  - Azure: Key Vault URI (e.g. https://vault.../keys/name/version-id)
     *  - LOCAL: "local-noop-v1"
     */
    @Column(name = "key_version", length = 512, nullable = false)
    private String keyVersion;

    @Column(name = "cloud_provider", length = 16, nullable = false)
    private String cloudProvider;

    /** True for the currently active version; false for superseded versions. */
    @Column(name = "active", nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Set when this version is superseded by a newer rotation. Null if still active. */
    @Column(name = "rotated_at")
    private Instant rotatedAt;

    /** Cumulative rotation count for this tenant+classification. */
    @Column(name = "rotation_count", nullable = false)
    @Builder.Default
    private int rotationCount = 0;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
