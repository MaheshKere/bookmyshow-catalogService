package com.bookmyshow.catalog.show.dto;

import java.time.Instant;

public record ShowResponse(Long id, Long movieId, String movieTitle, Long screenId, String screenName,
                           Long theaterId, String theaterName, String city,
                           Instant startTime, Instant endTime, boolean active,
                           Instant createdAt, Instant updatedAt) {
}
