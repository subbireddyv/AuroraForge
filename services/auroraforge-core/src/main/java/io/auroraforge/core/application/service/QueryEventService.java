package io.auroraforge.core.application.service;

import io.auroraforge.core.application.dto.EventDto;
import io.auroraforge.core.application.dto.EventMapper;
import io.auroraforge.core.application.dto.EventPageDto;
import io.auroraforge.core.application.port.in.QueryEventUseCase;
import io.auroraforge.core.application.port.out.EventStoragePort;
import io.auroraforge.core.domain.exception.EventNotFoundException;
import io.auroraforge.core.domain.model.DataEvent;
import io.auroraforge.core.domain.model.EventId;
import io.auroraforge.core.domain.model.EventStatus;
import io.auroraforge.core.domain.model.TenantId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class QueryEventService implements QueryEventUseCase {

    private final EventStoragePort storagePort;
    private final EventMapper      mapper;

    @Override
    public EventDto findById(String tenantId, String eventId) {
        log.debug("Querying DataEvent: tenantId={} eventId={}", tenantId, eventId);
        DataEvent event = storagePort.findById(EventId.of(eventId))
                .orElseThrow(() -> new EventNotFoundException(EventId.of(eventId)));

        // Tenant isolation check – prevent cross-tenant data leaks
        if (!event.getTenantId().value().equals(tenantId)) {
            throw new EventNotFoundException(EventId.of(eventId));
        }

        return mapper.toDto(event);
    }

    @Override
    public EventPageDto findByTenantAndStatus(String tenantId, EventStatus status,
                                               int page, int size) {
        TenantId tid   = TenantId.of(tenantId);
        int offset     = page * size;
        List<DataEvent> results = storagePort.findByTenantAndStatus(tid, status);
        long total     = storagePort.countByTenantAndStatus(tid, status);

        return new EventPageDto(
                mapper.toDtoList(results),
                page, size, total,
                (int) Math.ceil((double) total / size));
    }

    @Override
    public EventPageDto findByTenantCreatedBetween(String tenantId,
                                                    Instant from, Instant to,
                                                    int page, int size) {
        TenantId tid   = TenantId.of(tenantId);
        int offset     = page * size;
        List<DataEvent> results = storagePort.findByTenantCreatedBetween(
                tid, from, to, size, offset);

        return new EventPageDto(mapper.toDtoList(results), page, size, results.size(),
                results.size() < size ? page + 1 : page + 2);
    }
}
