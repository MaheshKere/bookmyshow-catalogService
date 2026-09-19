package com.bookmyshow.booking.reservation.dto;

import com.bookmyshow.booking.reservation.ReservationStatus;
import java.time.Instant;
import java.util.List;

public record ReservationResponse(Long id, String reservationReference, Long showId, ReservationStatus status,
                                  Instant expiresAt, List<Long> seatIds, Instant createdAt, Instant updatedAt) {
}
