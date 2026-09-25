package com.bookmyshow.payment;

import com.bookmyshow.payment.messaging.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class EventCodecTest {
    @Test void rejectsUnknownVersionInvalidAmountAndWrongKey() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var codec = new EventCodec(JsonMapper.builder().findAndAddModules().build(), factory.getValidator());
            var valid = new PaymentEvent(UUID.randomUUID(), 1, "BookingCreated", "booking", "1",
                    new BigDecimal("100.00"), "INR", null, Instant.now(), Instant.now().plusSeconds(300));
            String json = codec.write(valid);
            assertThat(codec.read("booking", json)).isEqualTo(valid);
            assertThatThrownBy(() -> codec.read("wrong-key", json)).isInstanceOf(PermanentEventException.class);
            assertThatThrownBy(() -> codec.read("booking", json.replace("Version" + (char) 34 + ":1", "Version" + (char) 34 + ":2")))
                    .isInstanceOf(PermanentEventException.class);
            assertThatThrownBy(() -> codec.read("booking", json.replace("100.00", "-1.00")))
                    .isInstanceOf(PermanentEventException.class);
            assertThatThrownBy(() -> codec.read("booking", "null")).isInstanceOf(PermanentEventException.class);
        }
    }
}