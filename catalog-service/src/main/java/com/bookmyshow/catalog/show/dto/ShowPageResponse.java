package com.bookmyshow.catalog.show.dto;

import java.util.List;

public record ShowPageResponse(List<ShowResponse> content, int page, int size,
                                long totalElements, int totalPages) {
}
