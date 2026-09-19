package com.bookmyshow.catalog.theater.dto;

import jakarta.validation.constraints.*;

public record TheaterRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 1000) String address,
        @NotNull Boolean active) {
}
