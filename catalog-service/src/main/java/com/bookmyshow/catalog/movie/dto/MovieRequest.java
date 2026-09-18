package com.bookmyshow.catalog.movie.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record MovieRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 4000) String description,
        @NotBlank @Size(max = 50) String language,
        @NotBlank @Size(max = 100) String genre,
        @NotNull @Positive Integer durationMinutes,
        @NotNull LocalDate releaseDate,
        @NotNull Boolean active) {
}
