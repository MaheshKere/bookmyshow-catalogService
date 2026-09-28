package com.bookmyshow.booking;

import com.bookmyshow.booking.booking.*;
import com.bookmyshow.booking.booking.dto.BookingRequest;
import com.bookmyshow.booking.reservation.*;
import com.bookmyshow.booking.reservation.dto.ReservationRequest;
import com.bookmyshow.booking.seat.*;
import com.bookmyshow.booking.seat.dto.InitializeSeatsRequest;
import com.bookmyshow.booking.messaging.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {"booking.expiration.enabled=false",
        "spring.kafka.listener.auto-startup=true", "spring.kafka.admin.auto-create=true"})
@Testcontainers
class BookingKafkaIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka-native:3.9.1");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
    @Autowired PaymentResultHandler handler;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Autowired BookingService bookings;
    @Autowired ReservationService reservations;
    @Autowired ShowSeatService seats;
    @Autowired EventCodec codec;
    @Autowired OutboxPublisher publisher;
    @Autowired KafkaTemplate<String, String> template;
    @Autowired JdbcTemplate jdbc;
    PaymentEvent create() {
        var seat = seats.initialize(Math.abs(new Random().nextLong() / 2) + 1, new InitializeSeatsRequest(List.of("A1"))).get(0);
        var reservation = reservations.reserve(new ReservationRequest(seat.showId(), List.of(seat.id())));
        var booking = bookings.create(new BookingRequest(reservation.reservationReference()), "1");
        return codec.read(booking.bookingReference(), jdbc.queryForObject(
                "select payload from outbox_events where booking_reference=?", String.class, booking.bookingReference()));
    }
    PaymentEvent result(PaymentEvent event, String type) {
        return new PaymentEvent(UUID.randomUUID(), 1, type, event.bookingReference(), event.subject(), event.amount(),
                event.currency(), UUID.randomUUID().toString(), Instant.now(), event.expiresAt());
    }
    @Test void bookingOutboxPublishesWireContractWithBookingKey() {
        var event = create();
        try (var consumer = consumer(PaymentEvent.BOOKINGS)) {
            while (publisher.publishOne()) { }
            assertThat(codec.read(event.bookingReference(), read(consumer, event.bookingReference()).value())).isEqualTo(event);
        }
    }
    @Test void brokerSuccessAndFailureDriveExistingLifecycle() throws Exception {
        for (String type : List.of("PaymentSucceeded", "PaymentFailed")) {
            var event = result(create(), type);
            template.send(PaymentEvent.RESULTS, event.bookingReference(), codec.write(event)).get(10, TimeUnit.SECONDS);
            template.send(PaymentEvent.RESULTS, event.bookingReference(), codec.write(event)).get(10, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(bookings.getByReference(event.bookingReference()).status()).isEqualTo(
                            type.equals("PaymentSucceeded") ? BookingStatus.CONFIRMED : BookingStatus.CANCELLED));
        }
    }
    @Test void unknownBookingResultIsPermanentlyRejectedToDlt() throws Exception {
        var event = new PaymentEvent(UUID.randomUUID(), 1, "PaymentSucceeded", UUID.randomUUID().toString(), "1",
                new java.math.BigDecimal("100.00"), "INR", UUID.randomUUID().toString(), Instant.now(), Instant.now().plusSeconds(300));
        try (var consumer = consumer(PaymentEvent.RESULTS + ".DLT")) {
            template.send(PaymentEvent.RESULTS, event.bookingReference(), codec.write(event)).get(10, TimeUnit.SECONDS);
            assertThat(read(consumer, event.bookingReference()).value()).isEqualTo(codec.write(event));
        }
        assertThat(jdbc.queryForObject("select count(*) from consumed_events where event_id=?", Long.class, event.eventId())).isZero();
    }
    @Test void confirmationCreatesOneOutboxWithPersistedBookingFacts() throws Exception {
        var event = result(create(), "PaymentSucceeded");
        handler.accept(event);
        handler.accept(event);
        handler.accept(new PaymentEvent(UUID.randomUUID(), 1, event.eventType(), event.bookingReference(),
                event.subject(), event.amount(), event.currency(), event.paymentReference(), event.occurredAt(), event.expiresAt()));
        String payload = jdbc.queryForObject("select payload from outbox_events where booking_reference=? and topic=?",
                String.class, event.bookingReference(), BookingConfirmedEvent.TOPIC);
        var confirmed = mapper.readValue(payload, BookingConfirmedEvent.class);
        assertThat(confirmed.eventId()).isNotEqualTo(event.eventId());
        assertThat(confirmed.eventType()).isEqualTo("BookingConfirmed");
        assertThat(confirmed.schemaVersion()).isEqualTo(1);
        assertThat(confirmed.bookingReference()).isEqualTo(event.bookingReference());
        assertThat(confirmed.subject()).isEqualTo(event.subject());
        assertThat(confirmed.amount()).isEqualByComparingTo(event.amount());
        assertThat(confirmed.currency()).isEqualTo("INR");
        assertThat(confirmed.occurredAt()).isNotNull();
        assertThat(confirmed.showId()).isEqualTo(bookings.getByReference(event.bookingReference()).showId());
        assertLifecycle(event, "CONFIRMED", "CONFIRMED", "BOOKED");
        try (var consumer = consumer(BookingConfirmedEvent.TOPIC)) {
            while (publisher.publishOne()) { }
            assertThat(read(consumer, event.bookingReference()).value()).isEqualTo(payload);
        }
    }
    @Test void confirmationRollbackRemovesOutboxAndRestoresAllThreeStates() {
        var event = result(create(), "PaymentSucceeded");
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    handler.accept(event);
                    throw new IllegalStateException("force rollback after confirmation and outbox insert");
                })).isInstanceOf(IllegalStateException.class);
        assertLifecycle(event, "PENDING", "ACTIVE", "HELD");
        assertThat(confirmationCount(event)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from consumed_events where event_id=?", Long.class, event.eventId())).isZero();
        handler.accept(event);
        assertThat(confirmationCount(event)).isEqualTo(1);
    }
    @Test void failedOutboxInsertRollsBackConfirmation() {
        var event = result(create(), "PaymentSucceeded");
        // A PostgreSQL trigger scoped to this booking injects a real insert failure.
        jdbc.execute("create function reject_test_confirmation() returns trigger language plpgsql as $$ begin "
                + "if NEW.topic = '" + BookingConfirmedEvent.TOPIC + "' and NEW.booking_reference = '"
                + event.bookingReference() + "' then raise exception 'test outbox unavailable'; end if; return NEW; end $$");
        jdbc.execute("create trigger reject_test_confirmation before insert on outbox_events for each row execute function reject_test_confirmation()");
        try {
            assertThatThrownBy(() -> handler.accept(event)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertLifecycle(event, "PENDING", "ACTIVE", "HELD");
            assertThat(confirmationCount(event)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from consumed_events where event_id=?", Long.class, event.eventId())).isZero();
        } finally {
            jdbc.execute("drop trigger reject_test_confirmation on outbox_events");
            jdbc.execute("drop function reject_test_confirmation()");
        }
        handler.accept(event);
        assertThat(confirmationCount(event)).isEqualTo(1);
    }
    @Test void failedOrLatePaymentDoesNotEmitBookingConfirmed() {
        var failed = result(create(), "PaymentFailed");
        handler.accept(failed);
        assertThat(confirmationCount(failed)).isZero();
        var late = result(create(), "PaymentSucceeded");
        bookings.cancel(late.bookingReference());
        assertThatThrownBy(() -> handler.accept(late)).isInstanceOf(PermanentEventException.class);
        assertThat(confirmationCount(late)).isZero();
    }
    @Test void brokerFailureRetainsConfirmationOutboxAndConfirmedBooking() {
        var event = result(create(), "PaymentSucceeded");
        // Drain earlier events so the failed send below specifically targets BookingConfirmed.
        while (publisher.publishOne()) { }
        handler.accept(event);
        @SuppressWarnings("unchecked") KafkaTemplate<String, String> broken = org.mockito.Mockito.mock(KafkaTemplate.class);
        org.mockito.Mockito.when(broken.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("broker unavailable")));
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(status -> new OutboxPublisher(jdbc, broken).publishOne())).isInstanceOf(IllegalStateException.class);
        assertLifecycle(event, "CONFIRMED", "CONFIRMED", "BOOKED");
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where booking_reference=? and topic=? and published_at is null",
                Long.class, event.bookingReference(), BookingConfirmedEvent.TOPIC)).isEqualTo(1);
    }
    long confirmationCount(PaymentEvent event) {
        return jdbc.queryForObject("select count(*) from outbox_events where booking_reference=? and topic=?",
                Long.class, event.bookingReference(), BookingConfirmedEvent.TOPIC);
    }
    void assertLifecycle(PaymentEvent event, String bookingStatus, String reservationStatus, String seatStatus) {
        var row = jdbc.queryForMap("select b.status as booking, r.status as reservation, s.status as seat "
                + "from bookings b join reservations r on r.id=b.reservation_id "
                + "join show_seats s on s.current_reservation_id=r.id where b.booking_reference=?", event.bookingReference());
        assertThat(row).containsEntry("booking", bookingStatus).containsEntry("reservation", reservationStatus).containsEntry("seat", seatStatus);
    }
    KafkaConsumer<String, String> consumer(String topic) {
        var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false), new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(topic));
        return consumer;
    }
    ConsumerRecord<String, String> read(KafkaConsumer<String, String> consumer, String reference) {
        var deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            for (var record : consumer.poll(Duration.ofMillis(500)))
                if (reference.equals(record.key())) return record;
        }
        throw new AssertionError("No broker record for " + reference);
    }
}
