package com.linguaflow.common.web;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;

/**
 * Stable JSON shape for paginated responses (instead of serializing Spring's
 * {@link Page}, whose structure is not an API contract). Pages are zero-based.
 * Requests use the standard {@code page}, {@code size} and {@code sort=field,asc|desc}
 * parameters; the size is capped by {@code spring.data.web.pageable.max-page-size}.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages,
		List<SortOrder> sort) {

	public record SortOrder(String property, String direction) {
	}

	public static <T> PageResponse<T> from(Page<T> page) {
		return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
				page.getTotalPages(), toSortOrders(page.getSort()));
	}

	/** Maps entities to DTOs while keeping the page metadata. */
	public static <E, T> PageResponse<T> from(Page<E> page, Function<? super E, ? extends T> mapper) {
		return from(page.map(mapper));
	}

	private static List<SortOrder> toSortOrders(Sort sort) {
		return sort.stream().map(o -> new SortOrder(o.getProperty(), o.getDirection().name())).toList();
	}

}
