package io.auroraforge.core.application.dto;

import java.util.List;

/** Paginated result wrapper for event queries. */
public record EventPageDto(
        List<EventDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public boolean hasNext() {
        return page < totalPages - 1;
    }

    public boolean isEmpty() {
        return content.isEmpty();
    }
}
