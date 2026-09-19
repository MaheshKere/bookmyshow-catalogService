package com.bookmyshow.booking.booking.dto;

import com.bookmyshow.booking.booking.BookingStatus;
import java.time.Instant;

public record BookingResponse(Long id, String bookingReference, Long reservationId, String reservationReference,
                              Long showId, BookingStatus status, Instant createdAt, Instant updatedAt) {
}
