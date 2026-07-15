package io.auroraforge.core.domain.repository;

import io.auroraforge.core.domain.model.DataEvent;
import io.auroraforge.core.domain.model.EventId;
import io.auroraforge.core.domain.model.EventStatus;
import io.auroraforge.core.domain.model.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Domain repository contract for {@link DataEvent}.
 *
 * This is an output port in Clean Architecture terms: the domain declares what it needs
 * and the infrastructure layer (Postgres/Cosmos DB adapters) fulfils the contract.
 *
 * No JPA / Hibernate annotations live here – the domain must remain framework-free.
 */
public interface DataEventRepository {

    /** Persist a new DataEvent. Throws if ID already exists (no upsert). */
    DataEvent save(DataEvent event);

    /** Persist or update an existing DataEvent (used after state transitions). */
    DataEvent update(DataEvent event);

    Optional<DataEvent> findById(EventId eventId);

    List<DataEvent> findByTenantAndStatus(TenantId tenantId, EventStatus status);

    List<DataEvent> findByTenantCreatedBetween(TenantId tenantId,
                                                Instant from, Instant to,
                                                int limit, int offset);

    /** Find events stuck in PROCESSING for longer than the given threshold (for retry scheduler). */
    List<DataEvent> findStaleProcessingEvents(Instant olderThan, int limit);

    void deleteById(EventId eventId);

    long countByTenantAndStatus(TenantId tenantId, EventStatus status);
}
