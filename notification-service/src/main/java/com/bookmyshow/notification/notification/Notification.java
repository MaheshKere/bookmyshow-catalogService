package com.bookmyshow.notification.notification;

import com.bookmyshow.notification.messaging.BookingConfirmedEvent;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications", uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "channel"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private UUID eventId;
    @Column(nullable = false, length = 36) private String bookingReference;
    @Column(nullable = false, length = 100) private String subject;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Channel channel;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private NotificationStatus status;
    @Column(nullable = false, length = 150) private String recipient;
    @Column(nullable = false, columnDefinition = "text") private String message;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    private Instant sentAt;

    public Notification(BookingConfirmedEvent event, Channel channel) {
        this.eventId = event.eventId();
        this.bookingReference = event.bookingReference();
        this.subject = event.subject();
        this.channel = channel;
        this.status = NotificationStatus.PENDING;
        // Explicit placeholders: no contact information is present in this domain event.
        this.recipient = channel == Channel.EMAIL ? "user-" + subject + "@example.invalid" : "mock-sms:user-" + subject;
        this.message = "Booking " + bookingReference + " confirmed for show " + event.showId()
                + ". Amount: " + event.amount().toPlainString() + " " + event.currency() + ".";
        this.createdAt = Instant.now();
    }
    public void markSent() { this.status = NotificationStatus.SENT; this.sentAt = Instant.now(); }
}
