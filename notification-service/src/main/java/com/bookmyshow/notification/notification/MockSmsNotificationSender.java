package com.bookmyshow.notification.notification;

import org.slf4j.*;
import org.springframework.stereotype.Component;

@Component
public class MockSmsNotificationSender implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(MockSmsNotificationSender.class);
    public Channel channel() { return Channel.SMS; }
    public void send(Notification notification) {
        log.info("mockSms eventId={} bookingReference={} recipient={} message={}",
                notification.getEventId(), notification.getBookingReference(),
                notification.getRecipient(), notification.getMessage());
    }
}
