package com.bookmyshow.payment.payment;

import com.bookmyshow.payment.messaging.*;
import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final PaymentRepository repository;
    private final EventStore events;
    private final PaymentProcessor processor;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public PaymentService(PaymentRepository repository, EventStore events, PaymentProcessor processor,
                          JdbcTemplate jdbc, Clock clock) {
        this.repository = repository; this.events = events; this.processor = processor; this.jdbc = jdbc; this.clock = clock;
    }
    @Transactional
    public void accept(PaymentEvent event) {
        if (!"BookingCreated".equals(event.eventType()) || event.paymentReference() != null)
            throw new PermanentEventException("Expected BookingCreated");
        if (!events.claim(event)) {
            log.info("duplicate eventId={} bookingReference={}", event.eventId(), event.bookingReference());
            return;
        }
        // Serialize even distinct event IDs for one booking across consumer instances.
        // Hash collisions only serialize unrelated bookings; the unique constraint is the final guard.
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))", event.bookingReference());
        var existing = repository.findByBookingReference(event.bookingReference());
        if (existing.isPresent()) {
            var payment = existing.get();
            if (!payment.getSubject().equals(event.subject()) || payment.getAmount().compareTo(event.amount()) != 0
                    || !payment.getCurrency().equals(event.currency()) || !payment.getExpiresAt().equals(event.expiresAt()))
                throw new PermanentEventException("Conflicting booking payment request");
            return;
        }
        var payment = repository.save(new Payment(event));
        payment.complete(processor.process(payment.getPaymentReference(), payment.getAmount(), payment.getCurrency(), payment.getExpiresAt()));
        events.append(PaymentEvent.RESULTS, new PaymentEvent(UUID.randomUUID(), 1,
                payment.getStatus() == PaymentStatus.SUCCESS ? "PaymentSucceeded" : "PaymentFailed",
                payment.getBookingReference(), payment.getSubject(), payment.getAmount(), payment.getCurrency(),
                payment.getPaymentReference(), clock.instant(), payment.getExpiresAt()));
        log.info("paymentProcessed eventId={} bookingReference={} paymentReference={} status={}",
                event.eventId(), event.bookingReference(), payment.getPaymentReference(), payment.getStatus());
    }
    @Transactional(readOnly = true)
    public PaymentView get(String bookingReference, String subject) {
        var payment = repository.findByBookingReference(bookingReference)
                .filter(p -> p.getSubject().equals(subject))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        return new PaymentView(payment.getPaymentReference(), payment.getBookingReference(), payment.getAmount(),
                payment.getCurrency(), payment.getStatus(), payment.getCreatedAt(), payment.getUpdatedAt());
    }
    public record PaymentView(String paymentReference, String bookingReference, java.math.BigDecimal amount,
                              String currency, PaymentStatus status, java.time.Instant createdAt, java.time.Instant updatedAt) {}
}