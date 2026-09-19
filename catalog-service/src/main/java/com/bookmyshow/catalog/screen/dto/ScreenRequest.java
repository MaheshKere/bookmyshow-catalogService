package com.bookmyshow.catalog.screen.dto;

import jakarta.validation.constraints.*;

public record ScreenRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Positive Integer totalSeats, @NotNull Boolean active) {
}
