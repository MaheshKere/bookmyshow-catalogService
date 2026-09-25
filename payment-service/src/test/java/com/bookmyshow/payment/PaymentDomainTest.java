package com.bookmyshow.payment;

import com.bookmyshow.payment.messaging.PaymentEvent;
import com.bookmyshow.payment.payment.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class PaymentDomainTest {
    final Instant now = Instant.parse("2030-01-01T00:00:00Z");
    @Test void deterministicSuccessFailureAndExpiry() {
        var processor = new MockPaymentProcessor(Clock.fixed(now, ZoneOffset.UTC));
        assertThat(processor.process("ref", new BigDecimal("100"), "INR", now.plusSeconds(1))).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(processor.process("ref", new BigDecimal("1000"), "INR", now.plusSeconds(1))).isEqualTo(PaymentStatus.FAILED);
        assertThat(processor.process("ref", new BigDecimal("100"), "INR", now)).isEqualTo(PaymentStatus.FAILED);
    }
    @Test void terminalTransitionsAreExplicitAndIdempotent() {
        var payment = new Payment(new PaymentEvent(UUID.randomUUID(), 1, "BookingCreated", UUID.randomUUID().toString(),
                "1", new BigDecimal("100"), "INR", null, now, now.plusSeconds(300)));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThatThrownBy(() -> payment.complete(PaymentStatus.PENDING)).isInstanceOf(IllegalArgumentException.class);
        payment.complete(PaymentStatus.SUCCESS);
        payment.complete(PaymentStatus.SUCCESS);
        assertThatThrownBy(() -> payment.complete(PaymentStatus.FAILED)).isInstanceOf(IllegalStateException.class);
    }
}