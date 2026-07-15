package io.auroraforge.core.application.port.in;

import io.auroraforge.core.application.dto.EventDto;
import jakarta.validation.Valid;

/**
 * Input port for the data ingestion use case.
 *
 * In the Hexagonal / Clean Architecture, this interface is the boundary
 * between the presentation layer (REST controllers, gRPC handlers, Kafka consumers)
 * and the application layer. Controllers depend on this interface, never on
 * the concrete service implementation.
 */
public interface IngestEventUseCase {

    /**
     * Ingest a single data event.
     *
     * @param command validated command object
     * @return DTO representing the created (or deduplicated) event
     * @throws io.auroraforge.core.domain.exception.DomainException on constraint violation
     */
    EventDto ingest(@Valid IngestEventCommand command);
}
