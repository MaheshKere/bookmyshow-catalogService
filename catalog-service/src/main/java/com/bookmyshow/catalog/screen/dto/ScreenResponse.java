package com.bookmyshow.catalog.screen.dto;

import java.time.Instant;

public record ScreenResponse(Long id, String name, int totalSeats, boolean active, Long theaterId,
                             Instant createdAt, Instant updatedAt) {
}
