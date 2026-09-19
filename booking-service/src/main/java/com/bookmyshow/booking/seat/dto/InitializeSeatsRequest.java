package com.bookmyshow.booking.seat.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record InitializeSeatsRequest(@NotEmpty @Size(max = 1000) List<@NotBlank @Pattern(regexp = "[A-Z0-9-]{1,20}") String> seatNumbers) {
}
