package com.bookmyshow.notification.messaging;

import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class EventCodecTest {
    final EventCodec codec = new EventCodec(JsonMapper.builder().findAndAddModules().build(),
            Validation.buildDefaultValidatorFactory().getValidator());
    BookingConfirmedEvent event() {
        return new BookingConfirmedEvent(UUID.randomUUID(), 1, "BookingConfirmed", UUID.randomUUID().toString(),
                "1", new BigDecimal("100.00"), "INR", Instant.now(), 100L);
    }
    @Test void roundTripPreservesContract() {
        var event = event();
        assertThat(codec.read(event.bookingReference(), codec.write(event))).isEqualTo(event);
    }
    @Test void rejectsMalformedNullAndMismatchedKey() {
        var event = event();
        for (var payload : new String[]{"{broken", "null", "{}"})
            assertThatThrownBy(() -> codec.read(event.bookingReference(), payload)).isInstanceOf(PermanentEventException.class);
        assertThatThrownBy(() -> codec.read("other", codec.write(event))).isInstanceOf(PermanentEventException.class);
    }
    @Test void rejectsWrongTypeVersionAndInvalidFields() {
        var event = event();
        var json = codec.write(event);
        for (var invalid : new String[]{json.replace("BookingConfirmed", "PaymentSucceeded"),
                json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                json.replace("100.00", "-1.00"), json.replace("INR", "USD"),
                json.replace("\"subject\":\"1\"", "\"subject\":\"bad\"")})
            assertThatThrownBy(() -> codec.read(event.bookingReference(), invalid)).isInstanceOf(PermanentEventException.class);
    }
}
