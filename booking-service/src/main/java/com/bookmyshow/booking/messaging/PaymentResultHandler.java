package com.bookmyshow.booking.messaging;

import com.bookmyshow.booking.booking.*;
import com.bookmyshow.booking.reservation.ReservationService;
import com.bookmyshow.booking.exception.ConflictException;
import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentResultHandler {
    private static final Logger log = LoggerFactory.getLogger(PaymentResultHandler.class);
    private final BookingRepository repository;
    private final ReservationService reservations;
    private final BookingService bookings;
    private final EventStore events;
    private final JdbcTemplate jdbc;
    public PaymentResultHandler(BookingRepository repository, ReservationService reservations,
                                BookingService bookings, EventStore events, JdbcTemplate jdbc) {
        this.repository = repository; this.reservations = reservations; this.bookings = bookings;
        this.events = events; this.jdbc = jdbc;
    }
    @Transactional
    public void accept(PaymentEvent event) {
        if (!("PaymentSucceeded".equals(event.eventType()) || "PaymentFailed".equals(event.eventType()))
                || event.paymentReference() == null || event.paymentReference().isBlank())
            throw new PermanentEventException("Expected a payment result with paymentReference");
        if (!events.claim(event)) {
            log.info("duplicate eventId={} bookingReference={} paymentReference={}",
                    event.eventId(), event.bookingReference(), event.paymentReference());
            return;
        }
        var reservationId = repository.findReservationId(event.bookingReference())
                .orElseThrow(() -> new PermanentEventException("Unknown booking"));
        var reservation = reservations.lockById(reservationId);
        var booking = repository.findByBookingReference(event.bookingReference()).orElseThrow();
        if (!event.subject().equals(booking.getSubject()) || booking.getAmount() == null
                || event.amount().compareTo(booking.getAmount()) != 0 || !event.currency().equals(booking.getCurrency())
                || !event.expiresAt().equals(reservation.getExpiresAt()))
            throw new PermanentEventException("Payment does not match booking");
        var prior = jdbc.queryForList("select * from booking_payment_results where booking_reference=?", event.bookingReference());
        if (!prior.isEmpty()) {
            if (!event.paymentReference().equals(prior.get(0).get("payment_reference"))
                    || !event.eventType().equals(prior.get(0).get("result")))
                throw new PermanentEventException("Conflicting terminal payment result");
            return;
        }
        // Preserve the established Reservation -> seat lock order and deadline checks.
        try {
            if ("PaymentSucceeded".equals(event.eventType())) bookings.confirm(event.bookingReference());
            else bookings.cancel(event.bookingReference());
        } catch (ConflictException exception) {
            // Late success cannot resurrect released seats; DLT requires reconciliation/refund.
            throw new PermanentEventException("Payment result conflicts with booking lifecycle");
        }
        jdbc.update("insert into booking_payment_results(booking_reference,payment_reference,result) values(?,?,?)",
                event.bookingReference(), event.paymentReference(), event.eventType());
        log.info("paymentApplied eventId={} bookingReference={} paymentReference={} result={}",
                event.eventId(), event.bookingReference(), event.paymentReference(), event.eventType());
    }
}