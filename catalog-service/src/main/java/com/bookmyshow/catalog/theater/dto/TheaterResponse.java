package com.bookmyshow.catalog.theater.dto;

import java.time.Instant;

public record TheaterResponse(Long id, String name, String city, String address, boolean active,
                              Instant createdAt, Instant updatedAt) {
}
