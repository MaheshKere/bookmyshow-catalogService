package com.bookmyshow.notification.notification;

import com.bookmyshow.notification.messaging.*;
import jakarta.validation.Validator;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private final EventStore events;
    private final NotificationRepository notifications;
    private final Validator validator;
    private final Map<Channel, NotificationSender> senders = new EnumMap<>(Channel.class);
    public NotificationService(EventStore events, NotificationRepository notifications,
                               Validator validator, List<NotificationSender> adapters) {
        this.events = events; this.notifications = notifications; this.validator = validator;
        for (var adapter : adapters) {
            if (senders.put(adapter.channel(), adapter) != null)
                throw new IllegalStateException("Multiple senders for " + adapter.channel());
        }
        if (senders.size() != Channel.values().length) throw new IllegalStateException("Every channel requires a sender");
    }
    @Transactional
    public void accept(BookingConfirmedEvent event) {
        if (event == null || !validator.validate(event).isEmpty())
            throw new PermanentEventException("Expected a valid BookingConfirmed v1 event");
        if (!events.claim(event.eventId())) {
            log.info("duplicate eventId={} bookingReference={}", event.eventId(), event.bookingReference());
            return;
        }
        for (var channel : Channel.values()) {
            var notification = notifications.save(new Notification(event, channel));
            senders.get(channel).send(notification);
            notification.markSent();
        }
        // The inbox and both notifications commit together. Mock log lines cannot be rolled back;
        // real delivery requires provider idempotency and recovery for ambiguous send outcomes.
    }
}
