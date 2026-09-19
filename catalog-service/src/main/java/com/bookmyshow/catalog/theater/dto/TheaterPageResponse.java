package com.bookmyshow.catalog.theater.dto;

import java.util.List;

public record TheaterPageResponse(List<TheaterResponse> content, int page, int size,
                                long totalElements, int totalPages) {
}
