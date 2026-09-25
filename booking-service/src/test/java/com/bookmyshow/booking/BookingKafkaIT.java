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