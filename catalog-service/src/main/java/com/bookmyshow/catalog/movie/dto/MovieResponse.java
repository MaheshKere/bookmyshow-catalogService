package com.bookmyshow.catalog.movie.dto;

import java.time.Instant;
import java.time.LocalDate;

public record MovieResponse(Long id, String title, String description, String language,
                            String genre, int durationMinutes, LocalDate releaseDate,
                            boolean active, Instant createdAt, Instant updatedAt) {
}
