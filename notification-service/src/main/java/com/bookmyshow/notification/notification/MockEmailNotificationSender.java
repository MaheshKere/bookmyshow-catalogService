package com.bookmyshow.notification.notification;

import org.slf4j.*;
import org.springframework.stereotype.Component;

@Component
public class MockEmailNotificationSender implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(MockEmailNotificationSender.class);
    public Channel channel() { return Channel.EMAIL; }
    public void send(Notification notification) {
        log.info("mockEmail eventId={} bookingReference={} recipient={} message={}",
                notification.getEventId(), notification.getBookingReference(),
                notification.getRecipient(), notification.getMessage());
    }
}
