package io.auroraforge.ingestion.infrastructure.persistence.adapter;

import io.auroraforge.core.application.port.out.EventStoragePort;
import io.auroraforge.core.domain.model.*;
import io.auroraforge.ingestion.infrastructure.persistence.entity.DataEventJpaEntity;
import io.auroraforge.ingestion.infrastructure.persistence.entity.DataEventJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Infrastructure adapter: implements {@link EventStoragePort} using JPA + PostgreSQL.
 *
 * Mapping:
 *   DataEvent (domain aggregate) ↔ DataEventJpaEntity (JPA entity)
 *
 * The adapter is the ONLY class that knows both the domain model and the JPA entity.
 * No JPA code ever leaks into the application or domain layer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostgresEventStorageAdapter implements EventStoragePort {

    private final DataEventJpaRepository repository;

    @Override
    @Transactional
    public DataEvent save(DataEvent event) {
        DataEventJpaEntity entity = toEntity(event);
        DataEventJpaEntity saved  = repository.save(entity);
        log.debug("Persisted DataEvent id={} tenantId={}", saved.getId(), saved.getTenantId());
        return toDomain(saved);
    }

    @Override
    @Transactional
    public DataEvent update(DataEvent event) {
        DataEventJpaEntity entity = toEntity(event);
        DataEventJpaEntity saved  = repository.save(entity);  // JPA merge on existing ID
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DataEvent> findById(EventId eventId) {
        return repository.findById(eventId.value()).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DataEvent> findByIdempotencyKey(String idempotencyKey) {
        return repository.findByIdempotencyKey(idempotencyKey).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataEvent> findByTenantAndStatus(TenantId tenantId, EventStatus status) {
        return repository.findByTenantIdAndStatus(tenantId.value(), status)
                         .stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataEvent> findByTenantCreatedBetween(TenantId tenantId, Instant from,
                                                       Instant to, int limit, int offset) {
        return repository.findByTenantAndCreatedBetween(
                        tenantId.value(), from, to,
                        PageRequest.of(offset / limit, limit))
                         .stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataEvent> findStaleProcessingEvents(Instant olderThan, int limit) {
        return repository.findStaleProcessingEvents(olderThan, PageRequest.of(0, limit))
                         .stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByTenantAndStatus(TenantId tenantId, EventStatus status) {
        return repository.countByTenantIdAndStatus(tenantId.value(), status);
    }

    // ── Mapping helpers ────────────────────────────────────────────────────

    private DataEventJpaEntity toEntity(DataEvent d) {
        String idempotencyKey = d.getMetadata().get("idempotencyKey");

        return DataEventJpaEntity.builder()
                .id(d.getId().value())
                .tenantId(d.getTenantId().value())
                .schemaName(d.getSchemaName())
                .schemaVersion(d.getSchemaVersion())
                .classification(d.getClassification())
                .rawPayload(d.getRawPayload())
                .encryptedPayload(d.getEncryptedPayload())
                .encryptionKeyVersion(d.getEncryptionKeyVersion())
                .status(d.getStatus())
                .createdAt(d.getCreatedAt())
                .updatedAt(d.getUpdatedAt())
                .retryCount(d.getRetryCount())
                .failureReason(d.getFailureReason())
                .idempotencyKey(idempotencyKey)
                .metadata(d.getMetadata())
                .version(d.getVersion())
                .build();
    }

    private DataEvent toDomain(DataEventJpaEntity e) {
        return DataEvent.reconstitute(
                EventId.of(e.getId()),
                TenantId.of(e.getTenantId()),
                e.getSchemaName(),
                e.getSchemaVersion(),
                e.getClassification(),
                e.getRawPayload(),
                e.getEncryptedPayload(),
                e.getEncryptionKeyVersion(),
                e.getStatus(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getRetryCount(),
                e.getFailureReason(),
                e.getMetadata() != null ? e.getMetadata() : Map.of(),
                e.getVersion());
    }
}
