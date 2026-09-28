package com.bookmyshow.notification;

import com.bookmyshow.notification.messaging.*;
import com.bookmyshow.notification.notification.*;
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

@SpringBootTest
@Testcontainers
class NotificationKafkaIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka-native:3.9.1");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
    @Autowired org.springframework.kafka.config.KafkaListenerEndpointRegistry listeners;
    @Autowired NotificationService service;
    @Autowired NotificationRepository notifications;
    @Autowired EventCodec codec;
    @Autowired KafkaTemplate<String, String> template;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean MockEmailNotificationSender email;
    @MockitoSpyBean MockSmsNotificationSender sms;
    @BeforeEach void clean() {
        jdbc.update("delete from notifications");
        jdbc.update("delete from consumed_events");
        reset(email, sms);
    }
    BookingConfirmedEvent event() {
        return new BookingConfirmedEvent(UUID.randomUUID(), 1, "BookingConfirmed", UUID.randomUUID().toString(),
                "1", new BigDecimal("100.00"), "INR", Instant.now(), 100L);
    }
    long claims() { return jdbc.queryForObject("select count(*) from consumed_events", Long.class); }
    @Test void recordsBothChannelsWithSyntheticRecipientsAndSentState() {
        var event = event();
        service.accept(event);
        assertThat(notifications.findAll()).hasSize(2).allSatisfy(notification -> {
            assertThat(notification.getEventId()).isEqualTo(event.eventId());
            assertThat(notification.getBookingReference()).isEqualTo(event.bookingReference());
            assertThat(notification.getSubject()).isEqualTo("1");
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
            assertThat(notification.getCreatedAt()).isNotNull();
            assertThat(notification.getSentAt()).isNotNull();
            assertThat(notification.getMessage()).contains(event.bookingReference(), "100", "INR");
        });
        assertThat(notifications.findAll()).extracting(Notification::getRecipient)
                .containsExactlyInAnyOrder("user-1@example.invalid", "mock-sms:user-1");
        assertThat(claims()).isEqualTo(1);
    }
    @Test void concurrentDuplicateClaimsProcessOnlyOnce() throws Exception {
        var event = event();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.accept(event));
            var second = executor.submit(() -> service.accept(event));
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        service.accept(event);
        assertThat(notifications.count()).isEqualTo(2);
        assertThat(claims()).isEqualTo(1);
        verify(email, times(1)).send(any());
        verify(sms, times(1)).send(any());
    }
    @Test void enclosingRollbackRemovesNotificationsAndClaim() {
        var event = event();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.accept(event);
            throw new IllegalStateException("force commit failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(notifications.count()).isZero();
        assertThat(claims()).isZero();
        service.accept(event);
        assertThat(notifications.count()).isEqualTo(2);
    }
    @Test void secondSenderFailureRollsBackFirstNotificationAndClaimAndAllowsRetry() {
        var event = event();
        doThrow(new IllegalStateException("temporary SMS failure")).when(sms).send(any());
        assertThatThrownBy(() -> service.accept(event)).isInstanceOf(IllegalStateException.class);
        assertThat(notifications.count()).isZero();
        assertThat(claims()).isZero();
        reset(sms);
        service.accept(event);
        assertThat(notifications.count()).isEqualTo(2);
        assertThat(claims()).isEqualTo(1);
    }
    @Test void brokerDuplicateDeliveryIsAcknowledgedWithoutSendingAgain() throws Exception {
        var event = event();
        template.send(BookingConfirmedEvent.TOPIC, event.bookingReference(), codec.write(event)).get(10, TimeUnit.SECONDS);
        var sent = template.send(BookingConfirmedEvent.TOPIC, event.bookingReference(), codec.write(event)).get(10, TimeUnit.SECONDS);
        try (var admin = org.apache.kafka.clients.admin.AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            var metadata = sent.getRecordMetadata();
            var partition = new org.apache.kafka.common.TopicPartition(metadata.topic(), metadata.partition());
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var offsets = admin.listConsumerGroupOffsets("notification-service-v1").partitionsToOffsetAndMetadata().get();
                assertThat(offsets.get(partition)).isNotNull();
                assertThat(offsets.get(partition).offset()).isGreaterThan(metadata.offset());
            });
        }
        assertThat(notifications.count()).isEqualTo(2);
        verify(email, times(1)).send(any());
        verify(sms, times(1)).send(any());
    }
    @Test void transientSenderFailureRetriesThenSucceeds() throws Exception {
        var attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.incrementAndGet() < 3) throw new IllegalStateException("temporary sender outage");
            return call.callRealMethod();
        }).when(email).send(any());
        var event = event();
        template.send(BookingConfirmedEvent.TOPIC, event.bookingReference(), codec.write(event)).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(notifications.count()).isEqualTo(2));
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(claims()).isEqualTo(1);
    }
    @Test void transientExhaustionGoesToDltWithNoCommittedClaim() throws Exception {
        doThrow(new IllegalStateException("temporary sender failure"))
                .when(email).send(any());
        var event = event();
        assertDlt(event, codec.write(event));
        verify(email, times(3)).send(any());
        assertThat(notifications.count()).isZero();
        assertThat(claims()).isZero();
    }
    @Test void permanentSenderFailureGoesDirectlyToDlt() throws Exception {
        doThrow(new PermanentEventException("recipient permanently rejected")).when(email).send(any());
        var event = event();
        assertDlt(event, codec.write(event));
        verify(email, times(1)).send(any());
        verify(sms, never()).send(any());
        assertThat(notifications.count()).isZero();
        assertThat(claims()).isZero();
    }
    @Test void malformedEventGoesToDltWithoutSending() throws Exception {
        assertDlt(event(), "{broken");
        verify(email, never()).send(any());
        verify(sms, never()).send(any());
        assertThat(claims()).isZero();
    }
    @Test void eventsPublishedWhileConsumerStoppedAreProcessedOnRestart() throws Exception {
        var stopped = new CountDownLatch(1);
        listeners.stop(stopped::countDown);
        assertThat(stopped.await(15, TimeUnit.SECONDS)).isTrue();
        var event = event();
        try {
            template.send(BookingConfirmedEvent.TOPIC, event.bookingReference(), codec.write(event)).get(10, TimeUnit.SECONDS);
            assertThat(notifications.count()).isZero();
        } finally { listeners.start(); }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(notifications.count()).isEqualTo(2));
        assertThat(claims()).isEqualTo(1);
    }
    @Test void databaseClaimFailureRetriesToDltWithoutSending() throws Exception {
        var event = event();
        // Real PostgreSQL claim failure, without taking down other tests' shared container.
        jdbc.execute("create function reject_test_claim() returns trigger language plpgsql as $$ begin "
                + "if NEW.event_id = '" + event.eventId() + "'::uuid then raise exception 'test database unavailable' "
                + "using errcode = '08006'; end if; return NEW; end $$");
        jdbc.execute("create trigger reject_test_claim before insert on consumed_events for each row execute function reject_test_claim()");
        try {
            assertDlt(event, codec.write(event));
            assertThat(notifications.count()).isZero();
            assertThat(claims()).isZero();
            verify(email, never()).send(any());
            verify(sms, never()).send(any());
        } finally {
            jdbc.execute("drop trigger reject_test_claim on consumed_events");
            jdbc.execute("drop function reject_test_claim()");
        }
        // Explicit replay after DB recovery can claim the original event ID.
        service.accept(event);
        assertThat(notifications.count()).isEqualTo(2);
    }
    void assertDlt(BookingConfirmedEvent event, String payload) throws Exception {
        try (var consumer = consumer(BookingConfirmedEvent.TOPIC + ".DLT")) {
            template.send(BookingConfirmedEvent.TOPIC, event.bookingReference(), payload).get(10, TimeUnit.SECONDS);
            assertThat(read(consumer, event.bookingReference()).value()).isEqualTo(payload);
        }
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
}
