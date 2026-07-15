package io.auroraforge.sync.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DlqRecordRepository extends JpaRepository<DlqRecordEntity, UUID> {

    /** Returns all PENDING_RETRY records whose next-retry window has elapsed. */
    List<DlqRecordEntity> findByStatusAndNextRetryAtBefore(
            DlqRecordEntity.DlqStatus status, Instant now);

    /** Paginated DLQ records for a tenant, newest first. */
    Page<DlqRecordEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    /** Count DLQ records for a tenant in a given status (for dashboards). */
    long countByTenantIdAndStatus(String tenantId, DlqRecordEntity.DlqStatus status);

    /** Returns all CONFLICT_REVIEW records awaiting human sign-off. */
    Page<DlqRecordEntity> findByStatusOrderByCreatedAtAsc(
            DlqRecordEntity.DlqStatus status, Pageable pageable);

    /** Resolves all DLQ records for a tenant (post-tenant-deletion cleanup). */
    @Modifying
    @Query("UPDATE DlqRecordEntity e SET e.status = 'RESOLVED' WHERE e.tenantId = :tenantId")
    int resolveAllForTenant(@Param("tenantId") String tenantId);

    /** Returns the total count of EXHAUSTED records across all tenants (SLI metric). */
    @Query("SELECT COUNT(e) FROM DlqRecordEntity e WHERE e.status = 'EXHAUSTED'")
    long countExhausted();
}
