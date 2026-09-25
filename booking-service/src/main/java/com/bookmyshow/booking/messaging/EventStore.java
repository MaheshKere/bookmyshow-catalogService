package com.bookmyshow.booking.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class EventStore {
    private final JdbcTemplate jdbc;
    private final EventCodec codec;
    public EventStore(JdbcTemplate jdbc, EventCodec codec) { this.jdbc = jdbc; this.codec = codec; }
    public void append(String topic, PaymentEvent event) {
        // JdbcTemplate joins the JPA transaction on the same service-owned DataSource.
        jdbc.update("insert into outbox_events(event_id,booking_reference,topic,payload) values(?,?,?,?)",
                event.eventId(), event.bookingReference(), topic, codec.write(event));
    }
    public boolean claim(PaymentEvent event) {
        // ON CONFLICT waits for a concurrent claimant; rollback also removes this claim.
        return jdbc.update("insert into consumed_events(event_id) values(?) on conflict do nothing", event.eventId()) == 1;
    }
}
