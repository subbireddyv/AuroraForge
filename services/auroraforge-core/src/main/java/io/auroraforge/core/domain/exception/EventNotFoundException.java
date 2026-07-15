package io.auroraforge.core.domain.exception;

import io.auroraforge.core.domain.model.EventId;

public class EventNotFoundException extends DomainException {

    public EventNotFoundException(EventId eventId) {
        super("EVENT_NOT_FOUND",
              "DataEvent with id [%s] was not found".formatted(eventId.value()));
    }
}
