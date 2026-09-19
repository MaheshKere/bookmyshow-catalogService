package com.bookmyshow.booking.booking.dto;

import jakarta.validation.constraints.*;

public record BookingRequest(@NotBlank @Size(max = 36) String reservationReference) {
}
