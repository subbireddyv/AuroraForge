package io.auroraforge.ingestion.infrastructure.persistence.entity;

import io.auroraforge.core.domain.model.EventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository. Lives in infrastructure – the domain knows nothing of this.
 * Custom queries use JPQL (not SQL) to remain ORM-agnostic.
 */
@Repository
public interface DataEventJpaRepository extends JpaRepository<DataEventJpaEntity, String> {

    Optional<DataEventJpaEntity> findByIdempotencyKey(String idempotencyKey);

    List<DataEventJpaEntity> findByTenantIdAndStatus(String tenantId, EventStatus status);

    @Query("""
           SELECT e FROM DataEventJpaEntity e
            WHERE e.tenantId = :tenantId
              AND e.createdAt BETWEEN :from AND :to
            ORDER BY e.createdAt DESC
           """)
    List<DataEventJpaEntity> findByTenantAndCreatedBetween(
            @Param("tenantId") String tenantId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            org.springframework.data.domain.Pageable pageable);

    @Query("""
           SELECT e FROM DataEventJpaEntity e
            WHERE e.status = 'PROCESSING'
              AND e.updatedAt < :threshold
            ORDER BY e.updatedAt ASC
           """)
    List<DataEventJpaEntity> findStaleProcessingEvents(
            @Param("threshold") Instant threshold,
            org.springframework.data.domain.Pageable pageable);

    long countByTenantIdAndStatus(String tenantId, EventStatus status);
}
