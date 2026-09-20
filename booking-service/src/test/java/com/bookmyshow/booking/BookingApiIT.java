package com.bookmyshow.booking;

import com.bookmyshow.booking.booking.*;
import com.bookmyshow.booking.booking.dto.*;
import com.bookmyshow.booking.reservation.*;
import com.bookmyshow.booking.reservation.dto.*;
import com.bookmyshow.booking.seat.*;
import com.bookmyshow.booking.seat.dto.*;
import com.fasterxml.jackson.databind.*;
import jakarta.persistence.*;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.dao.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "booking.expiration.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@Import(BookingApiIT.TimeConfiguration.class)
class BookingApiIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    static class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        void set(Instant instant) { now.set(instant); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }
    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary TestClock testClock() { return new TestClock(); }
    }

    @Autowired MockMvc mvc;
    @Autowired org.springframework.web.context.WebApplicationContext webContext;

    @BeforeEach void authenticateRequests() {
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .defaultRequest(get("/").header("Authorization", "Bearer " + com.bookmyshow.booking.TestTokens.token("ADMIN")))
                .build();
    }
    @Autowired ObjectMapper mapper;
    @Autowired ShowSeatService seatService;
    @Autowired ReservationService reservations;
    @Autowired BookingService bookings;
    @Autowired ShowSeatRepository seats;
    @Autowired ReservationRepository reservationRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach void clean() {
        jdbc.update("delete from bookings");
        jdbc.update("delete from reservation_seats");
        jdbc.update("delete from show_seats");
        jdbc.update("delete from reservations");
        clock.set(NOW);
    }

    private List<Long> initialize(long showId, String... numbers) {
        return seatService.initialize(showId, new InitializeSeatsRequest(List.of(numbers)))
                .stream().map(ShowSeatResponse::id).toList();
    }
    private ReservationResponse reserve(long showId, List<Long> ids) {
        return reservations.reserve(new ReservationRequest(showId, ids));
    }
    private BookingResponse createBooking(ReservationResponse reservation) {
        return bookings.create(new BookingRequest(reservation.reservationReference()));
    }
    private int reserveHttp(long showId, List<Long> ids) throws Exception {
        return mvc.perform(post("/api/v1/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new ReservationRequest(showId, ids))))
                .andReturn().getResponse().getStatus();
    }
    private int postAction(String path) throws Exception {
        return mvc.perform(post(path)).andReturn().getResponse().getStatus();
    }
    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }

    // Barriers synchronize starts; Future timeouts bound deadlocks. No test-level transaction.
    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { ready.countDown(); assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return first.call(); });
            var b = executor.submit(() -> { ready.countDown(); assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return second.call(); });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void initializesSeatsAndListsWithPaginationAndStatusFilter() throws Exception {
        var result = mvc.perform(post("/api/v1/shows/100/seats").contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatNumbers\":[\"A1\",\"A2\"]}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "http://localhost/api/v1/shows/100/seats"))
                .andExpect(jsonPath("$[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$[0].version").value(0)).andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andReturn();
        long id = mapper.readTree(result.getResponse().getContentAsString()).get(0).get("id").asLong();
        reserve(100L, List.of(id));
        mvc.perform(get("/api/v1/shows/100/seats?status=HELD&size=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].version").value(1));
        mvc.perform(get("/api/v1/shows/100/seats?page=1&size=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.content[0].seatNumber").value("A2"));
        mvc.perform(get("/api/v1/shows/999/seats")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        initialize(101L, "A1"); // Number uniqueness is per show.
    }

    @Test void initializationIsAtomicAndRejectsDuplicateNumbers() throws Exception {
        initialize(100L, "A1");
        mvc.perform(post("/api/v1/shows/100/seats").contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatNumbers\":[\"A2\",\"A1\"]}")).andExpect(status().isConflict());
        assertThat(seats.count()).isEqualTo(1);
        mvc.perform(post("/api/v1/shows/100/seats").contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatNumbers\":[\"A2\",\"A2\"]}")).andExpect(status().isBadRequest());
    }

    @Test void reservationBookingAndConfirmationWorkThroughHttpAndPersistAllStates() throws Exception {
        var ids = initialize(100L, "A1", "A2");
        var result = mvc.perform(post("/api/v1/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new ReservationRequest(100L, ids))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expiresAt").value("2030-01-01T10:05:00Z")).andReturn();
        var reservation = mapper.readValue(result.getResponse().getContentAsString(), ReservationResponse.class);
        assertThat(result.getResponse().getHeader("Location")).endsWith("/" + reservation.reservationReference());
        var body = mapper.writeValueAsString(new BookingRequest(reservation.reservationReference()));
        var created = mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
        var booking = mapper.readValue(created.getResponse().getContentAsString(), BookingResponse.class);
        mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bookingReference").value(booking.bookingReference()));
        mvc.perform(post("/api/v1/bookings/{reference}/confirm", booking.bookingReference()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(post("/api/v1/bookings/{reference}/confirm", booking.bookingReference()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(get("/api/v1/bookings/{reference}", booking.bookingReference()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reservationReference").value(reservation.reservationReference()));
        mvc.perform(get("/api/v1/reservations/{reference}", reservation.reservationReference()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.seatIds.length()").value(2));
        assertThat(count("select count(*) from show_seats where status = 'BOOKED'")).isEqualTo(2);
        assertThat(count("select count(*) from reservation_seats")).isEqualTo(2);
        assertThat(bookingRepository.count()).isEqualTo(1);
        assertThat(reserveHttp(100L, ids)).isEqualTo(409);
        assertThat(postAction("/api/v1/bookings/" + booking.bookingReference() + "/cancel")).isEqualTo(409);
        assertThat(postAction("/api/v1/reservations/" + reservation.reservationReference() + "/cancel")).isEqualTo(409);
    }

    @Test void rejectsWrongShowMissingSeatAndHeldSeatWithoutPartialWrites() throws Exception {
        var own = initialize(100L, "A1", "A2");
        var other = initialize(101L, "A1");
        assertThat(reserveHttp(100L, List.of(own.get(0), other.get(0)))).isEqualTo(400);
        assertThat(reserveHttp(100L, List.of(own.get(0), 999999L))).isEqualTo(404);
        assertThat(reserveHttp(100L, List.of(own.get(0), own.get(0)))).isEqualTo(400);
        assertThat(reservationRepository.count()).isZero();
        reserve(100L, List.of(own.get(1)));
        assertThat(reserveHttp(100L, own)).isEqualTo(409);
        assertThat(seats.findById(own.get(0)).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(reservationRepository.count()).isEqualTo(1);
        mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reservationReference\":\"missing\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/bookings/missing/confirm")).andExpect(status().isNotFound());
    }

    @Test void expirationReleasesSeatsCancelsPendingBookingAndRetainsHistory() throws Exception {
        var ids = initialize(100L, "A1", "A2");
        var reservation = reserve(100L, ids);
        var booking = createBooking(reservation);
        assertThat(reservations.findExpirationCandidates()).isEmpty();
        assertThat(reservations.expire(reservation.id())).isFalse();
        clock.set(reservation.expiresAt());
        assertThat(reservations.findExpirationCandidates()).containsExactly(reservation.id());
        assertThat(postAction("/api/v1/bookings/" + booking.bookingReference() + "/confirm")).isEqualTo(409);
        assertThat(reservations.expire(reservation.id())).isTrue();
        assertThat(reservations.expire(reservation.id())).isFalse();
        assertThat(reservations.getByReference(reservation.reservationReference()).status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(bookings.getByReference(booking.bookingReference()).status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(count("select count(*) from show_seats where status = 'AVAILABLE' and current_reservation_id is null")).isEqualTo(2);
        var replacement = reserve(100L, ids);
        assertThat(replacement.id()).isNotEqualTo(reservation.id());
        assertThat(count("select count(*) from reservation_seats")).isEqualTo(4);
        assertThat(reservations.expire(reservation.id())).isFalse();
        assertThat(count("select count(*) from show_seats where status = 'HELD'")).isEqualTo(2);
    }

    @Test void expiredReservationCannotCreateBookingEvenBeforeCleanup() throws Exception {
        var reservation = reserve(100L, initialize(100L, "A1"));
        clock.set(reservation.expiresAt().plusSeconds(1));
        mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new BookingRequest(reservation.reservationReference()))))
                .andExpect(status().isConflict());
        assertThat(bookingRepository.count()).isZero();
    }

    @Test void cancellationReleasesHeldSeatsAndIsIdempotent() throws Exception {
        var ids = initialize(100L, "A1");
        var reservation = reserve(100L, ids);
        var booking = createBooking(reservation);
        assertThat(postAction("/api/v1/bookings/" + booking.bookingReference() + "/cancel")).isEqualTo(200);
        assertThat(postAction("/api/v1/bookings/" + booking.bookingReference() + "/cancel")).isEqualTo(200);
        assertThat(reservations.getByReference(reservation.reservationReference()).status()).isEqualTo(ReservationStatus.CANCELLED);
        reserve(100L, ids);
        assertThat(postAction("/api/v1/reservations/" + reservation.reservationReference() + "/cancel")).isEqualTo(200);
        assertThat(seats.findById(ids.get(0)).orElseThrow().getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(createBooking(reservation).id()).isEqualTo(booking.id()); // Replay does not create a new booking.
    }

    @Test void standaloneReservationCanBeCancelled() throws Exception {
        var reservation = reserve(100L, initialize(100L, "A1"));
        assertThat(postAction("/api/v1/reservations/" + reservation.reservationReference() + "/cancel")).isEqualTo(200);
        assertThat(count("select count(*) from show_seats where status = 'AVAILABLE'")).isEqualTo(1);
        assertThat(bookingRepository.count()).isZero();
    }

    @RepeatedTest(3)
    void exactlyOneConcurrentReservationOfSameSeatSucceeds() throws Exception {
        var ids = initialize(100L, "A1");
        var results = race(() -> reserveHttp(100L, ids), () -> reserveHttp(100L, ids));
        assertThat(results).containsExactlyInAnyOrder(201, 409);
        assertThat(count("select count(*) from reservations where status = 'ACTIVE'")).isEqualTo(1);
        assertThat(count("select count(*) from show_seats where status = 'HELD'")).isEqualTo(1);
        assertThat(count("select count(*) from reservation_seats")).isEqualTo(1);
        assertThat(count("select count(*) from show_seats s join reservations r on r.id=s.current_reservation_id where r.status='ACTIVE'"))
                .isEqualTo(1);
    }

    @Test void overlappingMultiSeatRequestsDoNotPartiallyReserveOrDeadlock() throws Exception {
        var ids = initialize(100L, "A1", "A2", "A3");
        var results = race(() -> reserveHttp(100L, List.of(ids.get(1), ids.get(0))),
                () -> reserveHttp(100L, List.of(ids.get(2), ids.get(1))));
        assertThat(results).containsExactlyInAnyOrder(201, 409);
        assertThat(count("select count(*) from show_seats where status='HELD'")).isEqualTo(2);
        assertThat(count("select count(*) from show_seats where status='AVAILABLE'")).isEqualTo(1);
        assertThat(reservationRepository.count()).isEqualTo(1);
    }

    @Test void concurrentBookingCreationReturnsSameResource() throws Exception {
        var reservation = reserve(100L, initialize(100L, "A1"));
        var results = race(() -> createBooking(reservation), () -> createBooking(reservation));
        assertThat(results.get(0).bookingReference()).isEqualTo(results.get(1).bookingReference());
        assertThat(bookingRepository.count()).isEqualTo(1);
    }

    @Test void expirationAndConfirmationCannotBothWin() throws Exception {
        var reservation = reserve(100L, initialize(100L, "A1"));
        var booking = createBooking(reservation);
        clock.set(reservation.expiresAt());
        var results = race(() -> { reservations.expire(reservation.id()); return 200; },
                () -> postAction("/api/v1/bookings/" + booking.bookingReference() + "/confirm"));
        assertThat(results).containsExactly(200, 409);
        assertThat(bookings.getByReference(booking.bookingReference()).status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(reservations.getByReference(reservation.reservationReference()).status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(count("select count(*) from show_seats where status='AVAILABLE'")).isEqualTo(1);
    }

    @Test void confirmedReservationIsNeverReleasedByLaterExpiration() {
        var reservation = reserve(100L, initialize(100L, "A1"));
        var booking = createBooking(reservation);
        bookings.confirm(booking.bookingReference());
        clock.set(reservation.expiresAt().plusSeconds(1));
        assertThat(reservations.expire(reservation.id())).isFalse();
        assertThat(count("select count(*) from show_seats where status='BOOKED'")).isEqualTo(1);
    }

    @Test void cancellationRacingConfirmationLeavesOneCoherentOutcome() throws Exception {
        var reservation = reserve(100L, initialize(100L, "A1"));
        var booking = createBooking(reservation);
        var results = race(() -> postAction("/api/v1/bookings/" + booking.bookingReference() + "/confirm"),
                () -> postAction("/api/v1/bookings/" + booking.bookingReference() + "/cancel"));
        assertThat(results).containsExactlyInAnyOrder(200, 409);
        var finalBooking = bookings.getByReference(booking.bookingReference());
        var finalReservation = reservations.getByReference(reservation.reservationReference());
        if (finalBooking.status() == BookingStatus.CONFIRMED) {
            assertThat(finalReservation.status()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(count("select count(*) from show_seats where status='BOOKED'")).isEqualTo(1);
        } else {
            assertThat(finalBooking.status()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(finalReservation.status()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(count("select count(*) from show_seats where status='AVAILABLE'")).isEqualTo(1);
        }
    }

    @Test void pessimisticSeatQueryWaitsUntilTheOtherTransactionCommits() throws Exception {
        var ids = initialize(100L, "A1");
        var executor = Executors.newSingleThreadExecutor();
        var worker = new AtomicReference<Future<ReservationResponse>>();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                em.find(ShowSeat.class, ids.get(0), LockModeType.PESSIMISTIC_WRITE);
                worker.set(executor.submit(() -> reserve(100L, ids)));
                await().atMost(Duration.ofSeconds(4)).untilAsserted(() ->
                        assertThat(count("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%show_seats%'"))
                                .isGreaterThanOrEqualTo(1));
                assertThat(worker.get().isDone()).isFalse();
            }); // Lock is released by commit here; worker can now reserve.
            assertThat(worker.get().get(10, TimeUnit.SECONDS).status()).isEqualTo(ReservationStatus.ACTIVE);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void versionRejectsOneOfTwoStaleManagedEntityUpdates() throws Exception {
        long id = initialize(100L, "A1").get(0);
        var loaded = new CountDownLatch(2);
        Callable<Boolean> update = () -> {
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    var seat = em.find(ShowSeat.class, id); // Deliberately no pessimistic lock.
                    loaded.countDown();
                    try {
                        if (!loaded.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("load barrier timed out");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    // Test-only field mutation isolates @Version without a new production update API.
                    ReflectionTestUtils.setField(seat, "seatNumber", "B" + Thread.currentThread().getId());
                });
                return true;
            } catch (OptimisticLockingFailureException exception) {
                return false;
            }
        };
        assertThat(race(update, update)).containsExactlyInAnyOrder(true, false);
        assertThat(seats.findById(id).orElseThrow().getVersion()).isEqualTo(1L);
    }

    @Test void migrationMappingsAndDatabaseConstraintsAreReal() {
        assertThat(count("select count(*) from flyway_schema_history where version='1' and success")).isEqualTo(1);
        var ids = initialize(100L, "A1");
        var reservation = reserve(100L, ids);
        var booking = createBooking(reservation);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            em.clear();
            var seat = em.find(ShowSeat.class, ids.get(0));
            assertThat(Hibernate.isInitialized(seat.getCurrentReservation())).isFalse();
            assertThat(seat.getCurrentReservation().getReservationReference()).isEqualTo(reservation.reservationReference());
            em.clear();
            var entity = em.find(Booking.class, booking.id());
            assertThat(Hibernate.isInitialized(entity.getReservation())).isFalse();
            assertThat(entity.getReservation().getId()).isEqualTo(reservation.id());
        });
        assertThatThrownBy(() -> jdbc.update("insert into show_seats(show_id,seat_number,status,version,created_at,updated_at) values(100,'A1','AVAILABLE',0,now(),now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into bookings(booking_reference,reservation_id,show_id,status,created_at,updated_at) values(?, ?, 100, 'PENDING',now(),now())",
                UUID.randomUUID().toString(), reservation.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update show_seats set current_reservation_id=999999 where id=?", ids.get(0)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update show_seats set current_reservation_id=null where id=?", ids.get(0)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update reservations set status='UNKNOWN' where id=?", reservation.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into reservation_seats(reservation_id,seat_id) values(?,?)", reservation.id(), ids.get(0)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into reservation_seats(reservation_id,seat_id) values(?,999999)", reservation.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from reservations where id=?", reservation.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("select count(*) from information_schema.tables where table_schema='public' and table_name in ('movies','theaters','screens','shows')"))
                .isZero();
    }
}
