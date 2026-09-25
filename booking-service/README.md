# Current payment phase

New bookings atomically enqueue BookingCreated. Payment results drive the existing confirmation/cancellation lifecycle; HTTP confirm returns 409. See the root [Payment/Kafka documentation](../README.md#payment-and-reliable-kafka-communication-2026-09-25). The original notes below describe the earlier learning phase; statements that Kafka/Outbox/Payment are absent are historical.

# Booking Service ? reservations and database concurrency

Security update: Booking now independently validates RSA JWTs. Seat browsing remains public; seat initialization requires ADMIN; reservation/booking endpoints require USER or ADMIN. Set JWT_PUBLIC_KEY_LOCATION before startup, including dev. Authorization is currently role-level, not per-user ownership. See the [root security/setup guide](../README.md). Reservation/booking business behavior and migrations are unchanged.

Java 17, Spring Boot 3.5.16, Spring Data JPA/Hibernate, PostgreSQL, Flyway, Bean Validation, record DTOs, JUnit/Mockito, and PostgreSQL Testcontainers. HTTP port: **8082**. Database: **booking_db**.

## Architecture and ownership

```text
Catalog Service (catalog_db, HTTP 8081)
    |
    | showId supplied by caller; future validation via REST
    v
Booking Service (booking_db, HTTP 8082)
    +-- ShowSeat: show-specific inventory and current ownership
    +-- Reservation: temporary five-minute hold
    +-- ReservationSeat: immutable historical seat membership
    +-- Booking: pending or confirmed booking
```

Booking owns seat inventory because checking availability, taking/releasing ownership, and confirming a booking must commit together in one database transaction. Separate databases make ownership explicit and prevent one service depending on another service's schema.

showId is a positive external identifier, not a JPA relationship or foreign key to Catalog. Booking never accesses Catalog tables and duplicates none of its Movie, Theater, Screen, or Show entities. Catalog can be offline while these APIs run. Show existence, active state, start time, and Screen capacity/layout are intentionally not validated here; a future explicit REST client can validate initialization against Catalog. No REST call to Catalog is implemented in this phase.

## Start PostgreSQL with Podman

Commands below are setup instructions; integration tests use their own disposable containers. They do not create this local development database.

On Windows, use the existing Podman machine. If it is stopped:

```powershell
podman machine start
```

For a new dedicated local PostgreSQL container (port 5433 avoids Catalog's 5432):

```powershell
podman volume create booking_pgdata
podman run --name booking-postgres -d -p 5433:5432 -e POSTGRES_DB=booking_db -e POSTGRES_USER=booking_user -e POSTGRES_PASSWORD=booking_password -v booking_pgdata:/var/lib/postgresql/data docker.io/library/postgres:17-alpine
podman exec booking-postgres pg_isready -U booking_user -d booking_db
```

The image initializes booking_db and booking_user on the volume's first use. Wait for pg_isready to report accepting connections before starting the service. Reusing a nonempty volume does not reapply initialization environment variables.

After stopping this container, start it again with:

```powershell
podman start booking-postgres
```

Optional connectivity check:

```powershell
podman exec -it booking-postgres psql -U booking_user -d booking_db
```

These credentials and the image's bootstrap role are for local learning. The two services use different database names, credentials, ports, and volumes.

## Run the application

From the repository root:

```powershell
mvn -pl booking-service spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Or, inside booking-service:

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

The explicit dev profile connects to jdbc:postgresql://[::1]:5433/booking_db with booking_user / booking_password for Windows + Podman. It enables application DEBUG, Hibernate SQL DEBUG and bind TRACE. No profile is globally activated. Optional runtime DevTools follows Catalog's setup: compile changed Java classes to trigger restart during spring-boot:run; executable production JARs exclude DevTools.

Without dev:

```powershell
$env:BOOKING_DB_URL = 'jdbc:postgresql://localhost:5433/booking_db'
$env:BOOKING_DB_USERNAME = 'booking_user'
$env:BOOKING_DB_PASSWORD = 'your-password'
$env:BOOKING_SERVER_PORT = '8082'
mvn -pl booking-service spring-boot:run
```

Flyway creates/migrates the schema before Hibernate validates it. ddl-auto=validate, open-in-view=false, and UTC JDBC handling remain enabled. The Hikari connection initialization sets PostgreSQL lock_timeout to 5 seconds so a blocked operation does not wait indefinitely. Database connection/startup is not the responsibility of Hibernate schema generation.

## REST API

All paths below are relative to http://localhost:8082. References are server-generated UUID strings. APIs return DTOs, never JPA entities. GET lists are zero-based with size 1?100, default 20, and ID ascending.

| Method | Path | Body / result |
|---|---|---|
| POST | /api/v1/shows/{showId}/seats | Initialize a batch; 201, array of seat DTOs, Location points to collection |
| GET | /api/v1/shows/{showId}/seats?status=AVAILABLE&page=0&size=20 | Paginated inventory, optional AVAILABLE/HELD/BOOKED filter |
| POST | /api/v1/reservations | Reserve all requested seats atomically; 201 + resource Location |
| GET | /api/v1/reservations/{reservationReference} | Reservation and historical seat IDs |
| POST | /api/v1/reservations/{reservationReference}/cancel | Cancel ACTIVE reservation; 200 |
| POST | /api/v1/bookings | Ensure one Booking exists for a Reservation; 200 + resource Location, including replays |
| GET | /api/v1/bookings/{bookingReference} | Current Booking DTO |
| POST | /api/v1/bookings/{bookingReference}/confirm | Simulate successful payment; 200 |
| POST | /api/v1/bookings/{bookingReference}/cancel | Cancel PENDING booking and release its reservation; 200 |

Initialize inventory (maximum 1000 per request). Numbers are case-sensitive uppercase letters/digits/hyphens, 1?20 characters:

```http
POST /api/v1/shows/100/seats
Content-Type: application/json

{"seatNumbers":["A1","A2","A3","B1","B2"]}
```

Use generated seat IDs from that response (maximum 20 distinct seats per reservation):

```http
POST /api/v1/reservations
Content-Type: application/json

{"showId":100,"seatIds":[1,2]}
```

Create Booking using the returned reference:

```http
POST /api/v1/bookings
Content-Type: application/json

{"reservationReference":"replace-with-returned-reservation-reference"}
```

Confirm or cancel with an empty body:

```http
POST /api/v1/bookings/{bookingReference}/confirm

POST /api/v1/bookings/{bookingReference}/cancel

POST /api/v1/reservations/{reservationReference}/cancel
```

GETs have no request body. A show with no initialized inventory yields an empty page; that does not assert anything about Catalog existence.

## Lifecycle and error rules

```text
ShowSeat:     AVAILABLE -> HELD -> BOOKED
                              -> AVAILABLE (expiration/cancellation)
Reservation: ACTIVE -> CONFIRMED | EXPIRED | CANCELLED
Booking:     PENDING -> CONFIRMED | CANCELLED
```

- New booking requires an ACTIVE, unexpired Reservation.
- Confirm requires PENDING Booking, ACTIVE/unexpired Reservation, and every historical seat still HELD by that Reservation for the same show.
- Confirmation updates all three kinds of state in one transaction.
- Repeating confirmation of an already CONFIRMED Booking returns its current DTO (200).
- Cancelling an ACTIVE Reservation releases its HELD seats and cancels any PENDING Booking. If its deadline has passed but cleanup has not run, cancellation still closes the ACTIVE state as CANCELLED.
- PENDING Booking cancellation delegates to the same reservation lifecycle. Cancellation retries on CANCELLED resources return 200 and never release a later owner's seats.
- Confirmed Booking/Reservation cancellation is rejected with 409 in this phase. No refunds or reopening BOOKED seats.
- EXPIRED Reservation cancellation and confirmation of a CANCELLED Booking return 409.
- Reads report stored lifecycle status. An ACTIVE record past its deadline can exist until cleanup; write operations check the deadline directly.

Problem Details follow Catalog's style:

| Status | Examples |
|---|---|
| 400 | Invalid DTO/path/filter, duplicate seat IDs/numbers within a request, seat from wrong show |
| 404 | Missing requested seat, Reservation, or Booking |
| 409 | HELD/BOOKED seat, expired/ineligible Reservation, forbidden lifecycle transition, duplicate inventory in DB, optimistic conflict, pessimistic lock/deadlock/timeout conflict |

Constraint errors do not expose SQL. No blind retries are performed. After a conflict, read current state before deciding what to retry. Reservation creation itself has no client idempotency key in this phase: retrying a successful request with the same seats receives 409.

## Schema and relationships

V1__create_booking_inventory.sql belongs solely to booking_db.

| Table | Important columns and constraints |
|---|---|
| reservations | identity PK, unique reservation_reference, positive external show_id, checked status, expires_at, required audit timestamps |
| show_seats | identity PK, external show_id, seat_number, checked status, @Version version, nullable current_reservation_id FK, timestamps; UNIQUE(show_id, seat_number) |
| reservation_seats | identity PK, required reservation_id and seat_id FKs; UNIQUE(reservation_id, seat_id) |
| bookings | identity PK, unique booking_reference, required UNIQUE reservation_id FK, external show_id, checked status, timestamps |

The seat check constraint requires AVAILABLE to have no current owner and HELD/BOOKED to have a current owner. Cross-row invariants (matching show IDs, historical membership/current owner agreement, and parent lifecycle state) are checked under locks in services; no database triggers are introduced.

| JPA relationship | Owning side | Fetch / lifecycle |
|---|---|---|
| ShowSeat -> current Reservation | ShowSeat.currentReservation | nullable LAZY ManyToOne |
| ReservationSeat -> Reservation | ReservationSeat.reservation | required LAZY ManyToOne |
| ReservationSeat -> ShowSeat | ReservationSeat.seat | required LAZY ManyToOne |
| Booking -> Reservation | Booking.reservation | required LAZY OneToOne, unique FK |

Each owner writes its FK. There are no parent collections, mappedBy, cascades, or orphanRemoval. A direct current-ownership relationship alone would lose membership when releasing seats. A join table alone would mix historical membership with present ownership. ReservationSeat preserves history while the nullable current owner on the single ShowSeat row establishes one current owner. Re-reservation creates new historical membership; the old membership remains. No CascadeType.ALL or entity @Data is used.

Booking detail reads use EntityGraph for its Reservation reference. Other responses use scalar seat IDs or fields already inside service transactions. OSIV is not needed.

Important indexes:

- UNIQUE(show_id, seat_number): prevents duplicate inventory even under concurrent initialization; supports show-prefix lookups.
- show_seats(show_id, status, id): filtered inventory listing with stable order.
- reservations.reservation_reference and bookings.booking_reference: unique constraints supply reference-lookup indexes.
- UNIQUE bookings(reservation_id): one Booking per Reservation, lookup and idempotency safeguard.
- UNIQUE reservation_seats(reservation_id, seat_id): ordered membership lookup and duplicate-membership prevention.
- reservation_seats(seat_id), show_seats(current_reservation_id): reverse FK checks.
- reservations(expires_at, id) WHERE status='ACTIVE': bounded expiry candidate scans.
- Every primary key already has its own index.

## Reservation request and transaction flow

1. MVC parses ReservationRequest and validates its showId and seatIds.
2. Controller calls the proxied ReservationService.reserve method.
3. Spring's transaction interceptor starts one transaction before entering the method and binds an EntityManager/connection.
4. Duplicate seat IDs are rejected. ShowSeatRepository.findAllForUpdate locks all requested rows ordered by immutable ID.
5. Service checks that all IDs exist, every seat belongs to the requested show, and all are AVAILABLE. No changes occur until the full set passes.
6. A Reservation with a UUID reference and clock.instant() + five minutes is inserted. The deadline is computed after lock acquisition.
7. New ReservationSeat records preserve membership; managed ShowSeats become HELD with the new current owner.
8. flush sends inserts/dirty updates and increments seat versions. It does not commit.
9. Service maps a DTO. On normal method return, the transaction proxy commits before returning to the controller; only then does MVC return 201. Exceptions roll back all changes.

Read operations inherit @Transactional(readOnly=true). Initialization, reservation, cancellation, expiration, create Booking, and confirm Booking each have write @Transactional at the service layer. Controllers have no transactions. Existing managed entities rely on dirty checking; save is used for newly created rows only. Explicit flush is used where the response must contain updated audit/version values.

Reservation lock helpers use Propagation.MANDATORY when called from BookingService to guarantee an existing outer transaction. Internal calls inside ReservationService rely on the already-active reserve/cancel/expire transaction; their self-invocation does not create another transaction. The scheduler calls the service proxy externally, once per candidate, so each expiry commits independently.

## The race, PostgreSQL locks, and @Version

Without locks, two transactions can both read AVAILABLE before either writes HELD. A Java synchronized block only coordinates threads inside one JVM; a second application instance would still race.

The real reservation query uses @Lock(PESSIMISTIC_WRITE) and an ORDER BY id. Conceptually:

```sql
BEGIN;
SELECT * FROM show_seats WHERE id IN (...) ORDER BY id FOR UPDATE;
-- validate all rows, insert Reservation/membership
UPDATE show_seats
SET status = 'HELD', current_reservation_id = ?, version = version + 1, updated_at = ?
WHERE id = ? AND version = ?;
COMMIT;
```

Hibernate's PostgreSQL dialect may express the write lock as FOR NO KEY UPDATE; it still conflicts with another reservation's write lock. Locks remain held through commit/rollback, not just until the SELECT returns. At PostgreSQL's default READ COMMITTED isolation, a waiter sees the committed seat state after obtaining its lock. If the first transaction commits HELD, the second rejects with 409. If the first rolls back, the second can reserve. Taking multiple seat locks in ascending ID order reduces deadlock risk for overlapping requests.

@Version additionally makes Hibernate include the previously loaded version in UPDATE's WHERE clause and increment it. Without a pessimistic lock, two transactions can load the same version; only the first update commits, while the stale writer raises an optimistic conflict and rolls back. Optimistic locking detects the collision at flush/commit; pessimistic locking serializes the high-contention check-and-hold operation before making changes. Both mechanisms remain present. Bulk/native SQL does not automatically honor @Version and is not used for production seat transitions.

Existing-reservation operations always lock Reservation first, then historical seat IDs ascending. All Booking writers serialize on that Reservation, so Booking does not require a separate lock/version in this phase. New reservations lock seats only and reject HELD seats without acquiring an old Reservation lock, avoiding an inverted lock order.

## Expiration and confirmation

ReservationExpirationScheduler runs every 30 seconds by default. It fetches up to 100 ACTIVE reservations whose expiresAt <= current time, then calls ReservationService.expire(id) in separate transactions. The service rechecks state/time after locking the Reservation, locks and verifies owned HELD seats, releases them, marks EXPIRED, and cancels a PENDING Booking. Failed items are logged and retried in a later sweep. Multiple application instances can run the scheduler safely because the lock/state recheck serializes their work; this is not a globally coordinated scheduler.

Confirmation takes the same Reservation lock, validates lifecycle/deadline, locks all associated seats, verifies current ownership/show/status, and rechecks the clock after possible seat-lock waits. If confirmation succeeds first while valid, cleanup sees CONFIRMED and releases nothing. If cleanup expires first, confirmation fails. The deadline is checked at the final locked validation; the transaction may commit shortly after that instant.

A hold may stay HELD briefly after its deadline until cleanup runs. It cannot be confirmed during that interval. Direct tests can call expire(id); no public administrative expiry endpoint was added. Tests disable scheduling via booking.expiration.enabled=false and inject a controllable Clock, avoiding five-minute sleeps.

## Idempotency

POST /bookings uses reservationReference as its natural idempotency identity. The service locks that Reservation, checks for an existing Booking, and returns it if present. UNIQUE(bookings.reservation_id) is the database backstop preventing duplicate rows if another writer bypasses that coordination. Replays return the same reference and current state, including CONFIRMED or CANCELLED, without creating a new Booking. Both initial creation and replay return 200 with Location.

New creation, unlike replay, requires ACTIVE and unexpired. Confirm and cancel are also idempotent for their matching terminal states. This is scoped idempotency, not a general Idempotency-Key implementation, and reservation creation itself is not idempotent.

## Tests and verification

From the repository root:

```powershell
# Unit/MVC tests for both modules (no coverage tools)
mvn test

# Booking unit/MVC tests only
mvn -pl booking-service test

# Booking unit/MVC + PostgreSQL integration tests
mvn -pl booking-service -Pintegration verify

# Existing Catalog unit/MVC + PostgreSQL integration tests
mvn -pl catalog-service -Pintegration verify

# Complete reactor including both modules' integration tests
mvn -Pintegration verify
```

Testcontainers requires a running Docker-compatible API (the existing Podman machine on Windows). Tests dynamically supply their own database URL/credentials, require no local booking_db, and do not silently skip without a container engine. No H2 or coverage agent is used. A restricted agent sandbox may require approval to access Podman's named pipe.

Verified on 2026-09-19:

| Suite | Tests | Result |
|---|---:|---|
| Booking unit/MVC | 32 | Passed |
| Booking PostgreSQL integration | 19 | Passed |
| Catalog unit/MVC | 37 | Passed |
| Catalog PostgreSQL integration | 10 | Passed |
| Total | 98 | Zero failures/errors/skips |

Both module -Pintegration verify commands completed successfully, including executable-JAR packaging. The root combined command is provided for convenience; the two modules were verified separately.

Mockito tests prove validation/orchestration/DTO behavior, not persistence, proxies, or locks. MVC tests prove HTTP validation and 400/404/409 Problem Details, including optimistic and pessimistic exception translation.

BookingApiIT runs against real postgres:17-alpine with no enclosing test transaction. It proves Flyway/schema validation, lazy mappings, unique/FK/check constraints, committed lifecycle behavior, multi-seat atomicity, wrong-show/missing/HELD/BOOKED rejection, expiry, cancellation, pagination/filtering, and idempotency.

Its concurrency cases include:

- Same-seat reservation race repeated three times: exactly one HTTP 201 and one 409; one ACTIVE owner, one HELD seat, one membership row.
- Overlapping multi-seat requests: exactly one success, no partial holds by the loser.
- Concurrent Booking creation: the same Booking reference and only one row.
- Expiration versus confirmation: expired hold released, confirmation rejected.
- Confirmation versus cancellation: exactly one transition succeeds and all final states agree.
- A deliberately held PostgreSQL seat lock: pg_stat_activity shows the other transaction waiting; it proceeds after commit.
- Two stale managed ShowSeat writes without pessimistic locks: exactly one commits and one raises an optimistic conflict.

The barrier-controlled tests use independent threads, service-proxy transactions/connections, bounded waits, and committed-state assertions. This is evidence for the implemented PostgreSQL paths, not a claim about untested future distributed workflows.

## Deliberate limits

JWT authentication and role authorization are now provided by Identity/Gateway and this service. No real payments, refunds, seat prices, Catalog HTTP validation, Kafka, Redis, Notification Service, Saga, Outbox, Kubernetes, or distributed Redis locks. Confirmation is an explicit simulation. References do not establish caller identity. Inventory initialization is an additive batch API, not a full replacement or externally authorized administration workflow.

No runtime database, container, or background application was left running for local use by this implementation; tests used disposable Testcontainers resources. Podman commands above are ready for the user to create the separate development database.

## Files created in this phase

```text
booking-service/.gitignore
booking-service/README.md
booking-service/pom.xml
booking-service/src/main/java/com/bookmyshow/booking/BookingServiceApplication.java
booking-service/src/main/java/com/bookmyshow/booking/booking/Booking.java
booking-service/src/main/java/com/bookmyshow/booking/booking/BookingController.java
booking-service/src/main/java/com/bookmyshow/booking/booking/BookingRepository.java
booking-service/src/main/java/com/bookmyshow/booking/booking/BookingService.java
booking-service/src/main/java/com/bookmyshow/booking/booking/BookingStatus.java
booking-service/src/main/java/com/bookmyshow/booking/booking/dto/BookingRequest.java
booking-service/src/main/java/com/bookmyshow/booking/booking/dto/BookingResponse.java
booking-service/src/main/java/com/bookmyshow/booking/exception/BusinessValidationException.java
booking-service/src/main/java/com/bookmyshow/booking/exception/ConflictException.java
booking-service/src/main/java/com/bookmyshow/booking/exception/GlobalExceptionHandler.java
booking-service/src/main/java/com/bookmyshow/booking/exception/ResourceNotFoundException.java
booking-service/src/main/java/com/bookmyshow/booking/reservation/Reservation.java
booking-service/src/main/java/com/bookmyshow/booking/reservation/ReservationController.java
booking-service/src/main/java/com/bookmyshow/booking/reservation/ReservationExpirationScheduler.java
booking-service/src/main/java/com/bookmyshow/booking/reservation/ReservationRepository.java
booking-service/src/main/java/com/bookmyshow/booking/reservation/ReservationSeat.java
booking-service/src/main/java/com/bookmyshow/booking/reservation/ReservationSeatRepository.java
booking-service/src/main/java/com/bookmyshow/booking/reservation/ReservationService.java
booking-service/src/main/java/com/bookmyshow/booking/reservation/ReservationStatus.java
booking-service/src/main/java/com/bookmyshow/booking/reservation/dto/ReservationRequest.java
booking-service/src/main/java/com/bookmyshow/booking/reservation/dto/ReservationResponse.java
booking-service/src/main/java/com/bookmyshow/booking/seat/SeatStatus.java
booking-service/src/main/java/com/bookmyshow/booking/seat/ShowSeat.java
booking-service/src/main/java/com/bookmyshow/booking/seat/ShowSeatController.java
booking-service/src/main/java/com/bookmyshow/booking/seat/ShowSeatRepository.java
booking-service/src/main/java/com/bookmyshow/booking/seat/ShowSeatService.java
booking-service/src/main/java/com/bookmyshow/booking/seat/dto/InitializeSeatsRequest.java
booking-service/src/main/java/com/bookmyshow/booking/seat/dto/ShowSeatPageResponse.java
booking-service/src/main/java/com/bookmyshow/booking/seat/dto/ShowSeatResponse.java
booking-service/src/main/resources/application-dev.yml
booking-service/src/main/resources/application.yml
booking-service/src/main/resources/db/migration/V1__create_booking_inventory.sql
booking-service/src/test/java/com/bookmyshow/booking/BookingApiIT.java
booking-service/src/test/java/com/bookmyshow/booking/BookingControllerTest.java
booking-service/src/test/java/com/bookmyshow/booking/booking/BookingServiceTest.java
booking-service/src/test/java/com/bookmyshow/booking/reservation/ReservationExpirationSchedulerTest.java
booking-service/src/test/java/com/bookmyshow/booking/reservation/ReservationServiceTest.java
booking-service/src/test/java/com/bookmyshow/booking/seat/ShowSeatServiceTest.java
```

Also created root README.md, added booking-service to root pom.xml, and appended EARLIER_ANSWERS.md. Existing Catalog and IDE changes were preserved.
