package com.bookmyshow.notification.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class EventStore {
    private final JdbcTemplate jdbc;
    public EventStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean claim(UUID eventId) {
        // Concurrent duplicates wait for the first transaction. A rollback releases the claim.
        return jdbc.update("insert into consumed_events(event_id) values(?) on conflict do nothing", eventId) == 1;
    }
}
