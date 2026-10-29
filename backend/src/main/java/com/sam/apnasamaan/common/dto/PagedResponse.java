package com.sam.apnasamaan.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Clean, stable JSON pagination wrapper used instead of exposing Spring's internal PageImpl structure.
 */
@Schema(description = "Paginated list response")
public record PagedResponse<T>(
        List<T> items,
        @Schema(example = "0") int page,
        @Schema(example = "10") int size,
        @Schema(example = "25") long totalElements,
        @Schema(example = "3") int totalPages,
        @Schema(example = "true") boolean hasNext
) {
    public static <T> PagedResponse<T> from(Page<T> springPage) {
        return new PagedResponse<>(
                springPage.getContent(),
                springPage.getNumber(),
                springPage.getSize(),
                springPage.getTotalElements(),
                springPage.getTotalPages(),
                springPage.hasNext()
        );
    }
}
