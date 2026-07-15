package io.auroraforge.auth.infrastructure.persistence;

import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.core.domain.security.AuroraForgeRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent user record for the AuroraForge auth service.
 *
 * Notes:
 * - {@code passwordHash} stores a BCrypt-hashed value (prefix $2a$12$...).
 *   Never store plaintext; Spring Security's {@link org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder}
 *   handles encoding and verification.
 * - {@code roles} and {@code allowedClassifications} are stored in separate join tables
 *   so they can be queried and updated independently without reloading the parent row.
 * - {@code @Version} provides optimistic locking — concurrent role changes will conflict
 *   and retry rather than silently overwrite.
 * - Service accounts (machine-to-machine) share the same entity with {@code serviceAccount = true};
 *   their "password" is a client secret hash.
 */
@Entity
@Table(
    name = "auth_users",
    indexes = {
        @Index(name = "idx_au_username",   columnList = "username",            unique = true),
        @Index(name = "idx_au_tenant",     columnList = "tenant_id"),
        @Index(name = "idx_au_tenant_usr", columnList = "tenant_id,username",  unique = true)
    }
)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "username", length = 128, nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", length = 72, nullable = false)
    private String passwordHash;

    /** Tenant this user belongs to. ADMIN users may have tenantId = "platform". */
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "auth_user_roles",
                     joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 32, nullable = false)
    @Builder.Default
    private Set<AuroraForgeRole> roles = Set.of();

    /**
     * The subset of {@link DataClassification} levels this user's tokens may carry.
     * Defaults to the union of all roles' {@link AuroraForgeRole#defaultClassifications()}.
     * Can be further restricted per-user to enforce data-classification-level access control.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "auth_user_classifications",
                     joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "classification", length = 32, nullable = false)
    @Builder.Default
    private Set<DataClassification> allowedClassifications = Set.of();

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /** True for machine-to-machine service accounts (client_credentials flow). */
    @Column(name = "service_account", nullable = false)
    @Builder.Default
    private boolean serviceAccount = false;

    @Column(name = "failed_login_count", nullable = false)
    @Builder.Default
    private int failedLoginCount = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    // ── Domain helpers ───────────────────────────────────────────────────────

    public boolean isLocked() {
        return lockedUntil != null && Instant.now().isBefore(lockedUntil);
    }

    public void recordFailedLogin(Instant lockUntil) {
        this.failedLoginCount++;
        this.lockedUntil = lockUntil;
    }

    public void resetFailedLogin() {
        this.failedLoginCount = 0;
        this.lockedUntil      = null;
    }
}
