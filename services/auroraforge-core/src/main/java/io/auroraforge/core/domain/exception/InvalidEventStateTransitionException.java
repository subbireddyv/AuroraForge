package io.auroraforge.core.domain.exception;

import io.auroraforge.core.domain.model.EventId;
import io.auroraforge.core.domain.model.EventStatus;

public class InvalidEventStateTransitionException extends DomainException {

    public InvalidEventStateTransitionException(EventId id, EventStatus from,
                                                 EventStatus to, String reason) {
        super("INVALID_STATE_TRANSITION",
              "DataEvent [%s] cannot transition from [%s] to [%s]%s"
                      .formatted(id.value(), from, to,
                                 reason != null ? ": " + reason : ""));
    }
}
