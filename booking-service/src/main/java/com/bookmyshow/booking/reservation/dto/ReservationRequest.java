package com.bookmyshow.booking.reservation.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record ReservationRequest(@NotNull @Positive Long showId,
        @NotEmpty @Size(max = 20) List<@NotNull @Positive Long> seatIds) {
}
