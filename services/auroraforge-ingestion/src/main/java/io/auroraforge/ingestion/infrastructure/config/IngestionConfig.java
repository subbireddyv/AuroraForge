package io.auroraforge.ingestion.infrastructure.config;

import io.auroraforge.core.application.dto.EventMapper;
import io.auroraforge.core.application.port.out.EventPublisherPort;
import io.auroraforge.core.application.port.out.EventStoragePort;
import io.auroraforge.core.application.port.out.KeyManagementPort;
import io.auroraforge.core.application.service.IngestEventService;
import io.auroraforge.core.application.service.QueryEventService;
import io.auroraforge.core.application.port.in.IngestEventUseCase;
import io.auroraforge.core.application.port.in.QueryEventUseCase;
import org.mapstruct.factory.Mappers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Spring wiring for the application layer.
 * The core module's services are framework-free; we wrap them here
 * with transaction management and Spring-managed beans.
 */
@Configuration
@EnableTransactionManagement
public class IngestionConfig {

    @Bean
    public EventMapper eventMapper() {
        return Mappers.getMapper(EventMapper.class);
    }

    @Bean
    public IngestEventUseCase ingestEventUseCase(EventStoragePort storagePort,
                                                  EventPublisherPort publisherPort,
                                                  KeyManagementPort keyManagementPort,
                                                  EventMapper mapper) {
        return new IngestEventService(storagePort, publisherPort, keyManagementPort, mapper);
    }

    @Bean
    public QueryEventUseCase queryEventUseCase(EventStoragePort storagePort,
                                                EventMapper mapper) {
        return new QueryEventService(storagePort, mapper);
    }
}
