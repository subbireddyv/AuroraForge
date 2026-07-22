package io.auroraforge.core.application.service;

import io.auroraforge.core.application.dto.EventDto;
import io.auroraforge.core.application.dto.EventMapper;
import io.auroraforge.core.application.port.in.IngestEventCommand;
import io.auroraforge.core.application.port.in.IngestEventUseCase;
import io.auroraforge.core.application.port.out.EventPublisherPort;
import io.auroraforge.core.application.port.out.EventStoragePort;
import io.auroraforge.core.application.port.out.KeyManagementPort;
import io.auroraforge.core.application.port.out.KeyManagementPort.EncryptionResult;
import io.auroraforge.core.domain.event.DomainEvent;
import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.core.domain.model.DataEvent;
import io.auroraforge.core.domain.model.EventId;
import io.auroraforge.core.domain.model.TenantId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Application service implementing {@link IngestEventUseCase}.
 *
 * Orchestrates domain objects and output ports; contains no business logic itself.
 * Transaction boundary owned here: persist → drain events → publish.
 *
 * This class is NOT annotated with @Service / @Transactional to keep the core module
 * framework-free. Each Spring Boot service module wraps it in a @Service-annotated
 * delegate that adds @Transactional and Spring DI.
 */
@Slf4j
@RequiredArgsConstructor
public class IngestEventService implements IngestEventUseCase {

    private final EventStoragePort   storagePort;
    private final EventPublisherPort publisherPort;
    private final KeyManagementPort  keyManagementPort;
    private final EventMapper        mapper;

    @Override
    public EventDto ingest(IngestEventCommand command) {
        try (var mdc = setupMdc(command)) {
            log.info("Ingesting event: schema={} v{} classification={}",
                     command.schemaName(), command.schemaVersion(), command.classification());

            // ── Idempotency check ─────────────────────────────────────────
            if (command.idempotencyKey() != null) {
                Optional<DataEvent> existing = storagePort.findByIdempotencyKey(command.idempotencyKey());
                if (existing.isPresent()) {
                    log.info("Returning existing event for idempotencyKey={}", command.idempotencyKey());
                    return mapper.toDto(existing.get());
                }
            }

            // ── Build aggregate ───────────────────────────────────────────
            Map<String, String> metadata = buildMetadata(command);
            byte[] payloadForStorage     = command.rawPayload();

            DataEvent event = DataEvent.builder(
                            EventId.generate(),
                            TenantId.of(command.tenantId()),
                            command.schemaName(),
                            command.schemaVersion(),
                            command.classification())
                    .rawPayload(command.classification() == DataClassification.RESTRICTED
                                ? null : command.rawPayload())
                    .metadata(metadata)
                    .build();

            // ── Encrypt if required ───────────────────────────────────────
            if (command.classification().requiresCmkEncryption() && command.rawPayload() != null) {
                EncryptionResult encrypted = keyManagementPort.encrypt(
                        command.rawPayload(), command.classification(), command.tenantId());
                event.attachEncryptedPayload(encrypted.ciphertext(), encrypted.keyVersion());
                log.debug("Payload encrypted with keyVersion={}", encrypted.keyVersion());
            }

            // ── Persist ───────────────────────────────────────────────────
            DataEvent saved = storagePort.save(event);
            log.info("DataEvent persisted: id={}", saved.getId().value());

            // ── Publish domain events (transactional outbox pattern) ──────
            List<DomainEvent> domainEvents = saved.drainEvents();
            publisherPort.publishAll(domainEvents);
            log.debug("Published {} domain events for eventId={}", domainEvents.size(), saved.getId().value());

            return mapper.toDto(saved);
        }
    }

    private Map<String, String> buildMetadata(IngestEventCommand command) {
        Map<String, String> metadata = new HashMap<>(command.metadata());
        metadata.put("sourceSystem", command.sourceSystem());
        if (command.idempotencyKey() != null) {
            metadata.put("idempotencyKey", command.idempotencyKey());
        }
        return metadata;
    }

    /** Puts per-request context into MDC for structured logging correlation. */
    private MdcScope setupMdc(IngestEventCommand command) {
        MDC.put("tenantId",    command.tenantId());
        MDC.put("schemaName",  command.schemaName());
        MDC.put("sourceSystem", command.sourceSystem());
        return MDC::clear;
    }

    /** AutoCloseable whose close() does not throw, so try-with-resources needs no catch. */
    @FunctionalInterface
    private interface MdcScope extends AutoCloseable {
        @Override
        void close();
    }
}
