package com.bookmyshow.notification.notification;

public interface NotificationSender {
    Channel channel();
    // Adapters should use eventId + channel as a stable provider idempotency key.
    // Throw PermanentEventException for permanent rejection; other failures are retried.
    void send(Notification notification);
}
