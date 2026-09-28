package com.bookmyshow.notification.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

@Component
public class EventCodec {
    private final ObjectMapper mapper;
    private final Validator validator;
    public EventCodec(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }
    public BookingConfirmedEvent read(String key, String json) {
        try {
            var event = mapper.readValue(json, BookingConfirmedEvent.class);
            if (event == null || !validator.validate(event).isEmpty()
                    || !event.bookingReference().equals(key)) {
                throw new PermanentEventException("Invalid v1 event or booking key");
            }
            return event;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new PermanentEventException("Malformed event JSON");
        }
    }
    public String write(BookingConfirmedEvent event) {
        try { return mapper.writeValueAsString(event); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Cannot encode event", exception); }
    }
}
