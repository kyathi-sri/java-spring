package com.securebank.common.web;

import java.util.List;
import java.util.function.Function;

/**
 * Stable JSON shape for paginated responses. Services return this instead of serializing
 * Spring Data's {@code PageImpl}, whose JSON layout is not a guaranteed contract.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public <R> PageResponse<R> map(Function<T, R> mapper) {
        return new PageResponse<>(content.stream().map(mapper).toList(), page, size, totalElements, totalPages);
    }
}
