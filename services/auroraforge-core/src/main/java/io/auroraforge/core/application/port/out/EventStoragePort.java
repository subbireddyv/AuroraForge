package io.auroraforge.core.application.port.out;

import io.auroraforge.core.domain.model.DataEvent;
import io.auroraforge.core.domain.model.EventId;
import io.auroraforge.core.domain.model.EventStatus;
import io.auroraforge.core.domain.model.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Output port: persistence contract used by the application service.
 * Implemented by infrastructure adapters (Postgres, Cosmos DB).
 */
public interface EventStoragePort {

    DataEvent save(DataEvent event);

    DataEvent update(DataEvent event);

    Optional<DataEvent> findById(EventId eventId);

    Optional<DataEvent> findByIdempotencyKey(String idempotencyKey);

    List<DataEvent> findByTenantAndStatus(TenantId tenantId, EventStatus status);

    List<DataEvent> findByTenantCreatedBetween(TenantId tenantId, Instant from,
                                                Instant to, int limit, int offset);

    List<DataEvent> findStaleProcessingEvents(Instant olderThan, int limit);

    long countByTenantAndStatus(TenantId tenantId, EventStatus status);
}
