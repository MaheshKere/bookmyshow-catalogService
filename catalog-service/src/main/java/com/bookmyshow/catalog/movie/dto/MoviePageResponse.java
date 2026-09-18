package com.bookmyshow.catalog.movie.dto;

import java.util.List;

public record MoviePageResponse(List<MovieResponse> content, int page, int size,
                                long totalElements, int totalPages) {
}
