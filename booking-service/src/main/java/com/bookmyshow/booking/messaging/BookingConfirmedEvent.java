package com.bookmyshow.booking.messaging;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// v1 JSON contract: a confirmed Booking fact, independent of the payment result.
public record BookingConfirmedEvent(
        @NotNull UUID eventId, @Min(1) @Max(1) int schemaVersion,
        @NotNull @Pattern(regexp = "BookingConfirmed") String eventType,
        @NotBlank @Size(max = 36) String bookingReference,
        @NotBlank @Pattern(regexp = "[1-9][0-9]*") @Size(max = 100) String subject,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull @Pattern(regexp = "INR") String currency,
        @NotNull Instant occurredAt, @NotNull @Positive Long showId) {
    public static final String TOPIC = "bookmyshow.booking.confirmed.v1";
}
