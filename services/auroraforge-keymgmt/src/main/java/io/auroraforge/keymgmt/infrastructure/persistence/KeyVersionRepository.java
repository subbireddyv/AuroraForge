package io.auroraforge.keymgmt.infrastructure.persistence;

import io.auroraforge.core.domain.model.DataClassification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KeyVersionRepository extends JpaRepository<KeyVersionEntity, UUID> {

    /** Find the current active key version for a specific tenant + classification. */
    Optional<KeyVersionEntity> findByTenantIdAndClassificationAndActiveTrue(
            String tenantId, DataClassification classification);

    /** All key versions for a tenant, newest first (rotation history). */
    List<KeyVersionEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    /** All currently active versions for a classification (used for bulk rotation). */
    List<KeyVersionEntity> findByClassificationAndActiveTrueOrderByCreatedAtAsc(
            DataClassification classification);

    /** Paginated rotation history across all tenants, ordered by most recent rotation. */
    Page<KeyVersionEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Deactivate the current active version for a tenant + classification. */
    @Modifying
    @Query("""
            UPDATE KeyVersionEntity k
               SET k.active = false, k.rotatedAt = CURRENT_TIMESTAMP
             WHERE k.tenantId = :tenantId
               AND k.classification = :classification
               AND k.active = true
            """)
    int deactivateCurrentVersion(@Param("tenantId") String tenantId,
                                 @Param("classification") DataClassification classification);

    /** Count of rotations for a tenant + classification (for rotation-count tracking). */
    @Query("SELECT COUNT(k) FROM KeyVersionEntity k WHERE k.tenantId = :tenantId AND k.classification = :classification")
    int countRotations(@Param("tenantId") String tenantId,
                       @Param("classification") DataClassification classification);

    /** Returns true if any active version exists for the given classification. */
    boolean existsByClassificationAndActiveTrue(DataClassification classification);
}
