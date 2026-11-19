package com.banking.paymentservice.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * A stable JSON shape for paged results.
 *
 * <p>Returning Spring's {@code Page} directly leaks framework internals into the API, and its
 * JSON format isn't guaranteed to stay the same across versions.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
