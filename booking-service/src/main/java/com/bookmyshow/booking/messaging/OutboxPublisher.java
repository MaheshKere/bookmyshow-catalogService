package com.bookmyshow.booking.messaging;

import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    public OutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka) { this.jdbc = jdbc; this.kafka = kafka; }
    @Transactional
    public boolean publishOne() {
        var rows = jdbc.queryForList("select * from outbox_events where published_at is null order by id limit 1 for update skip locked");
        if (rows.isEmpty()) return false;
        var row = rows.get(0);
        try {
            // Keep the row locked until broker acknowledgement. A crash after send can replay it.
            kafka.send((String) row.get("topic"), (String) row.get("booking_reference"), (String) row.get("payload"))
                    .get(10, TimeUnit.SECONDS);
            jdbc.update("update outbox_events set published_at=now() where id=?", row.get("id"));
            log.info("outboxPublished eventId={} bookingReference={}", row.get("event_id"), row.get("booking_reference"));
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Outbox publication interrupted", exception);
        } catch (Exception exception) {
            log.warn("outboxUnacknowledged eventId={} bookingReference={}", row.get("event_id"), row.get("booking_reference"));
            throw new IllegalStateException("Outbox publication unacknowledged; retained for retry", exception);
        }
    }
}
