package com.bookmyshow.booking.seat.dto;

import com.bookmyshow.booking.seat.SeatStatus;
import java.time.Instant;

public record ShowSeatResponse(Long id, Long showId, String seatNumber, SeatStatus status, Long version,
                               Instant createdAt, Instant updatedAt) {
}
