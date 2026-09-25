package com.bookmyshow.payment.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jakarta.validation.constraints.*;

// Wire contract v1: plain JSON, never a persistence entity or Java type header.
public record PaymentEvent(
        @NotNull UUID eventId, @Min(1) @Max(1) int schemaVersion,
        @NotBlank String eventType,
        @NotBlank @Size(max = 36) String bookingReference,
        @NotBlank @Pattern(regexp = "[1-9][0-9]*") @Size(max = 100) String subject,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @Pattern(regexp = "INR") @NotNull String currency,
        @Size(max = 36) String paymentReference,
        @NotNull Instant occurredAt, @NotNull Instant expiresAt) {
    public static final String BOOKINGS = "bookmyshow.booking.created.v1";
    public static final String RESULTS = "bookmyshow.payment.results.v1";
}
