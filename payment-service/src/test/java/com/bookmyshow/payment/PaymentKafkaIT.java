package com.bookmyshow.payment;

import com.bookmyshow.payment.messaging.*;
import com.bookmyshow.payment.payment.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=true", "spring.kafka.admin.auto-create=true"})
@Testcontainers
class PaymentKafkaIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka-native:3.9.1");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
    @Autowired PaymentService service;
    @Autowired PaymentRepository payments;
    @Autowired EventCodec codec;
    @Autowired EventStore events;
    @Autowired OutboxPublisher publisher;
    @Autowired KafkaTemplate<String, String> template;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean MockPaymentProcessor processor;
    @BeforeEach void clean() {
        jdbc.update("delete from outbox_events");
        jdbc.update("delete from consumed_events");
        jdbc.update("delete from payments");
        reset(processor);
    }
    PaymentEvent event(String amount) {
        return new PaymentEvent(UUID.randomUUID(), 1, "BookingCreated", UUID.randomUUID().toString(), "1",
                new BigDecimal(amount), "INR", null, Instant.now(), Instant.now().plusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
    long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
    @Test void creationSuccessFailureAndOwnerPrivacy() {
        var success = event("100.00");
        service.accept(success);
        assertThat(service.get(success.bookingReference(), "1").status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThatThrownBy(() -> service.get(success.bookingReference(), "2"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var failed = event("1000.00");
        service.accept(failed);
        assertThat(service.get(failed.bookingReference(), "1").status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(count("outbox_events")).isEqualTo(2);
        assertThat(count("consumed_events")).isEqualTo(2);
    }
    @Test void repeatedAndConcurrentRequestsOnlyProcessOnce() throws Exception {
        var event = event("100.00");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.accept(event));
            var second = executor.submit(() -> service.accept(event));
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        service.accept(new PaymentEvent(UUID.randomUUID(), 1, event.eventType(), event.bookingReference(),
                event.subject(), event.amount(), event.currency(), null, event.occurredAt(), event.expiresAt()));
        assertThat(payments.count()).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
        verify(processor, times(1)).process(anyString(), any(), anyString(), any());
    }
    @Test void failedTransactionRollsBackPaymentInboxAndOutbox() {
        var event = event("100.00");
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.accept(event);
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(payments.count()).isZero();
        assertThat(count("consumed_events")).isZero();
        assertThat(count("outbox_events")).isZero();
        service.accept(event);
        assertThat(payments.count()).isEqualTo(1);
    }
    @Test void brokerDeliveryAndDuplicateDeliveryProduceOnePayment() throws Exception {
        var event = event("100.00");
        template.send(PaymentEvent.BOOKINGS, event.bookingReference(), codec.write(event)).get(10, TimeUnit.SECONDS);
        var sent = template.send(PaymentEvent.BOOKINGS, event.bookingReference(), codec.write(event)).get(10, TimeUnit.SECONDS);
        try (var admin = org.apache.kafka.clients.admin.AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            var metadata = sent.getRecordMetadata();
            var partition = new org.apache.kafka.common.TopicPartition(metadata.topic(), metadata.partition());
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var offsets = admin.listConsumerGroupOffsets("payment-service-v1").partitionsToOffsetAndMetadata().get();
                assertThat(offsets.get(partition)).isNotNull();
                assertThat(offsets.get(partition).offset()).isGreaterThan(metadata.offset());
            });
        }
        assertThat(payments.count()).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
    }
    @Test void acknowledgedOutboxPublicationAndCrashReplayKeepStableEventId() {
        var event = event("100.00");
        service.accept(event);
        String json = jdbc.queryForObject("select payload from outbox_events", String.class);
        try (var consumer = consumer(PaymentEvent.RESULTS)) {
            assertThat(publisher.publishOne()).isTrue();
            var first = read(consumer, event.bookingReference());
            assertThat(first.value()).isEqualTo(json);
            assertThat(publisher.publishOne()).isFalse();
            // Simulate broker acknowledgement followed by DB rollback/crash.
            jdbc.update("update outbox_events set published_at=null");
            assertThat(publisher.publishOne()).isTrue();
            assertThat(read(consumer, event.bookingReference()).value()).isEqualTo(json);
        }
    }
    @Test void outboxSendFailureLeavesRecordAvailableForRetry() {
        service.accept(event("100.00"));
        @SuppressWarnings("unchecked") KafkaTemplate<String, String> broken = mock(KafkaTemplate.class);
        when(broken.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                new OutboxPublisher(jdbc, broken).publishOne())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where published_at is null", Long.class)).isEqualTo(1);
        assertThat(publisher.publishOne()).isTrue();
    }
    @Test void transientFailuresRetryThenSucceed() throws Exception {
        var attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.incrementAndGet() < 3) throw new org.springframework.dao.TransientDataAccessResourceException("temporary");
            return call.callRealMethod();
        }).when(processor).process(anyString(), any(), anyString(), any());
        var event = event("100.00");
        template.send(PaymentEvent.BOOKINGS, event.bookingReference(), codec.write(event)).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(payments.count()).isEqualTo(1));
        assertThat(attempts.get()).isEqualTo(3);
    }
    @Test void exhaustedTransientFailuresReachDltAndRollbackDatabase() throws Exception {
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("temporary"))
                .when(processor).process(anyString(), any(), anyString(), any());
        var event = event("100.00");
        try (var consumer = consumer(PaymentEvent.BOOKINGS + ".DLT")) {
            template.send(PaymentEvent.BOOKINGS, event.bookingReference(), codec.write(event)).get(10, TimeUnit.SECONDS);
            assertThat(read(consumer, event.bookingReference()).value()).isEqualTo(codec.write(event));
        }
        verify(processor, times(3)).process(anyString(), any(), anyString(), any());
        assertThat(payments.count()).isZero();
        assertThat(count("consumed_events")).isZero();
        assertThat(count("outbox_events")).isZero();
    }
    @Test void permanentMalformedEventGoesDirectlyToDlt() throws Exception {
        var reference = UUID.randomUUID().toString();
        try (var consumer = consumer(PaymentEvent.BOOKINGS + ".DLT")) {
            template.send(PaymentEvent.BOOKINGS, reference, "{broken").get(10, TimeUnit.SECONDS);
            assertThat(read(consumer, reference).value()).isEqualTo("{broken");
        }
        verifyNoInteractions(processor);
        assertThat(payments.count()).isZero();
    }
    KafkaConsumer<String, String> consumer(String topic) {
        var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
                new StringDeserializer(), new StringDeserializer());
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

    @Test void postgresUniquenessPreventsSecondPaymentForBooking() {
        service.accept(event("100.00"));
        assertThatThrownBy(() -> jdbc.update("""
                insert into payments(payment_reference,booking_reference,subject,amount,currency,status,expires_at,created_at,updated_at,version)
                select ?,booking_reference,subject,amount,currency,status,expires_at,now(),now(),0 from payments
                """, UUID.randomUUID().toString())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(payments.count()).isEqualTo(1);
    }}