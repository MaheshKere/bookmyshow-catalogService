package com.bookmyshow.payment.payment;

import java.math.BigDecimal;
import java.time.Instant;

// A future provider adapter must use paymentReference as its provider idempotency key.
public interface PaymentProcessor {
    PaymentStatus process(String paymentReference, BigDecimal amount, String currency, Instant expiresAt);
}