package com.bookmyshow.payment.payment;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

@Component
public class MockPaymentProcessor implements PaymentProcessor {
    private final Clock clock;
    public MockPaymentProcessor(Clock clock) { this.clock = clock; }
    public PaymentStatus process(String reference, BigDecimal amount, String currency, Instant expiresAt) {
        // Deterministic learning rule: bookings costing >= INR 1000 fail.
        return expiresAt.isAfter(clock.instant()) && amount.compareTo(new BigDecimal("1000.00")) < 0
                ? PaymentStatus.SUCCESS : PaymentStatus.FAILED;
    }
}