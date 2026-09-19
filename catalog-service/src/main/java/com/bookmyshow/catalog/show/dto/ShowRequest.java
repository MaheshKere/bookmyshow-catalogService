package com.bookmyshow.catalog.show.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;

public record ShowRequest(
        @NotNull @Positive Long movieId, @NotNull @Positive Long screenId,
        @NotNull @Positive Long theaterId,
        @NotNull Instant startTime, @NotNull Instant endTime, @NotNull Boolean active) {
}
