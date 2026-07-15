package io.auroraforge.auth.infrastructure.persistence;

import io.auroraforge.core.domain.security.AuroraForgeRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByUsername(String username);

    Optional<UserEntity> findByUsernameAndTenantId(String username, String tenantId);

    Page<UserEntity> findByTenantId(String tenantId, Pageable pageable);

    long countByTenantId(String tenantId);

    boolean existsByUsernameAndTenantId(String username, String tenantId);

    /** Used by UserDetailsService — includes enabled/locked check inline. */
    @Query("""
        SELECT u FROM UserEntity u
        WHERE u.username = :username
          AND u.enabled  = true
          AND (u.lockedUntil IS NULL OR u.lockedUntil < :now)
        """)
    Optional<UserEntity> findActiveByUsername(
            @Param("username") String username,
            @Param("now")      Instant now);

    /** Efficiently disable all users for a tenant (e.g. tenant offboarding). */
    @Modifying
    @Query("UPDATE UserEntity u SET u.enabled = false WHERE u.tenantId = :tenantId")
    int disableAllForTenant(@Param("tenantId") String tenantId);

    /** Find all service accounts with a given role — used for audit reporting. */
    @Query("""
        SELECT u FROM UserEntity u
        JOIN u.roles r
        WHERE u.serviceAccount = true
          AND r = :role
        """)
    Page<UserEntity> findServiceAccountsByRole(
            @Param("role") AuroraForgeRole role, Pageable pageable);
}
