package io.auroraforge.core.application.port.in;

import io.auroraforge.core.application.dto.EventDto;
import io.auroraforge.core.application.dto.EventPageDto;
import io.auroraforge.core.domain.model.EventStatus;

import java.time.Instant;

/**
 * Input port for querying DataEvents.
 * Queries are read-only and do not mutate aggregate state.
 */
public interface QueryEventUseCase {

    EventDto findById(String tenantId, String eventId);

    EventPageDto findByTenantAndStatus(String tenantId, EventStatus status,
                                        int page, int size);

    EventPageDto findByTenantCreatedBetween(String tenantId,
                                             Instant from, Instant to,
                                             int page, int size);
}
