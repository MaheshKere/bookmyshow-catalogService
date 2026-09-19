package com.bookmyshow.catalog.screen.dto;

import java.util.List;

public record ScreenPageResponse(List<ScreenResponse> content, int page, int size,
                                long totalElements, int totalPages) {
}
