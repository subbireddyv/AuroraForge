package io.auroraforge.core.application.dto;

import io.auroraforge.core.domain.model.DataEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * MapStruct mapper: DataEvent aggregate → EventDto.
 * Registered as a Spring component in service modules via @Mapper(componentModel = "spring").
 * This file uses the no-arg factory style so it can be used in plain unit tests too.
 */
@Mapper
public interface EventMapper {

    EventMapper INSTANCE = Mappers.getMapper(EventMapper.class);

    @Mapping(source = "id.value",       target = "id")
    @Mapping(source = "tenantId.value", target = "tenantId")
    EventDto toDto(DataEvent event);

    List<EventDto> toDtoList(List<DataEvent> events);
}
