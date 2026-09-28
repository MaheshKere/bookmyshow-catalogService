# Payment and Kafka phase â€” 2026-09-25

## Changes

- Inspected the existing root/module Maven structure, README, security, Flyway migrations, Booking/Reservation/seat lifecycle, unit/MVC and PostgreSQL concurrency tests before extending the project.
- Added independently runnable payment-service on Java 17 / Spring Boot 3.5.16, owning payment_db. It includes JPA Payment with PENDING/SUCCESS/FAILED, unique booking/payment references, subject, amount/currency, audit timestamps and optimistic version; a deterministic provider boundary; owner-scoped read API; and the existing public-key-only JWT validation approach.
- Added explicit v1 JSON Kafka contracts, keyed by bookingReference, with BookingCreated and PaymentSucceeded/PaymentFailed types. No shared JPA entities or database access.
- Added transactional outbox/inbox implementations to Booking and Payment. Business changes and event records share one local database transaction. Publishers wait for Kafka acknowledgement, retain failed sends and tolerate crash/restart duplicate publication.
- Booking creation now persists JWT-derived subject and a server-calculated INR 100/seat learning fare. Payment results reuse the existing Reservation-first/seat-lock confirmation and cancellation methods. The HTTP confirmation simulation returns 409. Late success cannot resurrect expired/cancelled seats.
- Added database-backed duplicate detection and natural-key constraints, finite transient processing retries, immediate permanent-failure DLT routing, and acknowledgement-checked DLT recovery.
- Added Gateway Payment route/security coverage while retaining Catalog/Identity/Booking routes and specific Show-seat precedence.
- Added meaningful PostgreSQL/Kafka Testcontainers tests plus Payment domain, contract and JWT tests. Preserved earlier lifecycle/concurrency assertions, adapting HTTP confirmation assertions to the new behavior.
- Updated root/Booking documentation and added Payment documentation with exact Windows/Podman commands, environment variables, contracts, startup order and tradeoffs.

## Schema changes

Booking V1 is unchanged. New V2__payment_events.sql adds nullable legacy-compatible subject/amount/currency columns to bookings, outbox_events with unique event_id and pending index, consumed_events keyed by event_id, and booking_payment_results keyed by booking reference with unique payment reference.

Payment V1__payments_and_events.sql creates payments with unique payment and booking references, status/amount/currency checks and version, plus independent outbox_events and consumed_events tables. No Catalog/Identity schema changed.

## Topics and flow

| Topic | Producer | Consumer |
|---|---|---|
| bookmyshow.booking.created.v1 | Booking outbox | Payment, payment-service-v1 |
| bookmyshow.payment.results.v1 | Payment outbox | Booking, booking-service-v1 |
| bookmyshow.booking.created.v1.DLT | Payment error handler | Manual investigation |
| bookmyshow.payment.results.v1.DLT | Booking error handler | Manual investigation |

Booking transaction commits PENDING plus request outbox. Payment consumes, claims eventId, creates/processes one payment per booking and commits its result outbox atomically. Booking consumes the result and atomically claims eventId, records the result and confirms or cancels using its established seat locks. Failed payment releases held seats; expired/cancelled success goes to DLT for reconciliation.

## Remaining limitations

No real payment provider, Notification Service, Redis, Saga orchestration framework, Kubernetes or production Kafka cluster setup. No automated refunds, reservation ownership, real pricing, retention/cleanup, DLT replay tooling or Kafka TLS/SASL/ACLs. Outbox delivery is at least once; consumers are idempotent. Broker acknowledgement and DB commit cannot be atomic without a different architecture. Publishing holds a DB row lock during a bounded broker wait. The local Kafka container is disposable and loses broker data if removed. Existing legacy Bookings are not retroactively charged. Existing Booking/Reservation authorization remains role-level.

## Local execution

Exact infrastructure creation/restart commands, key setup, environment variables, five-service startup order, endpoint examples and DLT inspection commands are in [README.md](README.md#exact-local-commands-windows-and-podman).

## Verification

Final root mvn -Pintegration verify completed with BUILD SUCCESS on 2026-09-25 at 20:59 IST. All 165 tests passed, with zero failures, errors or skips. Disposable PostgreSQL and Kafka containers were used; no persistent development service was started. Initial sandbox access and one later transient Podman connection failure were resolved by elevated reruns. Maven reports and the final reactor log confirm all five modules succeeded.

| Module | Unit / MVC / security / routing | PostgreSQL / Kafka integration | Total |
|---|---:|---:|---:|
| catalog-service | 40 | 10 | 50 |
| booking-service | 35 | 28 | 63 |
| identity-service | 21 | 6 | 27 |
| api-gateway | 9 | 0 | 9 |
| payment-service | 6 | 10 | 16 |
| **Total** | **111** | **54** | **165** |


## File inventory

The following files were added or edited for this phase. Existing EARLIER_ANSWERS.md is preserved; this file uses the requested Earlier_answer.md name. Pre-existing scripts/.local and concurrent IDE metadata changes are not part of the implementation.
- [api-gateway/src/main/java/com/bookmyshow/gateway/GatewayRoutes.java](api-gateway/src/main/java/com/bookmyshow/gateway/GatewayRoutes.java)
- [api-gateway/src/main/java/com/bookmyshow/gateway/security/SecurityConfiguration.java](api-gateway/src/main/java/com/bookmyshow/gateway/security/SecurityConfiguration.java)
- [api-gateway/src/main/resources/application.yml](api-gateway/src/main/resources/application.yml)
- [api-gateway/src/test/java/com/bookmyshow/gateway/GatewaySecurityRoutingTest.java](api-gateway/src/test/java/com/bookmyshow/gateway/GatewaySecurityRoutingTest.java)
- [booking-service/pom.xml](booking-service/pom.xml)
- [booking-service/README.md](booking-service/README.md)
- [booking-service/src/main/java/com/bookmyshow/booking/booking/Booking.java](booking-service/src/main/java/com/bookmyshow/booking/booking/Booking.java)
- [booking-service/src/main/java/com/bookmyshow/booking/booking/BookingController.java](booking-service/src/main/java/com/bookmyshow/booking/booking/BookingController.java)
- [booking-service/src/main/java/com/bookmyshow/booking/booking/BookingService.java](booking-service/src/main/java/com/bookmyshow/booking/booking/BookingService.java)
- [booking-service/src/main/java/com/bookmyshow/booking/messaging/EventCodec.java](booking-service/src/main/java/com/bookmyshow/booking/messaging/EventCodec.java)
- [booking-service/src/main/java/com/bookmyshow/booking/messaging/EventStore.java](booking-service/src/main/java/com/bookmyshow/booking/messaging/EventStore.java)
- [booking-service/src/main/java/com/bookmyshow/booking/messaging/KafkaConfiguration.java](booking-service/src/main/java/com/bookmyshow/booking/messaging/KafkaConfiguration.java)
- [booking-service/src/main/java/com/bookmyshow/booking/messaging/OutboxPublisher.java](booking-service/src/main/java/com/bookmyshow/booking/messaging/OutboxPublisher.java)
- [booking-service/src/main/java/com/bookmyshow/booking/messaging/OutboxScheduler.java](booking-service/src/main/java/com/bookmyshow/booking/messaging/OutboxScheduler.java)
- [booking-service/src/main/java/com/bookmyshow/booking/messaging/PaymentEvent.java](booking-service/src/main/java/com/bookmyshow/booking/messaging/PaymentEvent.java)
- [booking-service/src/main/java/com/bookmyshow/booking/messaging/PaymentResultHandler.java](booking-service/src/main/java/com/bookmyshow/booking/messaging/PaymentResultHandler.java)
- [booking-service/src/main/java/com/bookmyshow/booking/messaging/PaymentResultListener.java](booking-service/src/main/java/com/bookmyshow/booking/messaging/PaymentResultListener.java)
- [booking-service/src/main/java/com/bookmyshow/booking/messaging/PermanentEventException.java](booking-service/src/main/java/com/bookmyshow/booking/messaging/PermanentEventException.java)
- [booking-service/src/main/java/com/bookmyshow/booking/reservation/Reservation.java](booking-service/src/main/java/com/bookmyshow/booking/reservation/Reservation.java)
- [booking-service/src/main/resources/application.yml](booking-service/src/main/resources/application.yml)
- [booking-service/src/main/resources/db/migration/V2__payment_events.sql](booking-service/src/main/resources/db/migration/V2__payment_events.sql)
- [booking-service/src/test/java/com/bookmyshow/booking/booking/BookingServiceTest.java](booking-service/src/test/java/com/bookmyshow/booking/booking/BookingServiceTest.java)
- [booking-service/src/test/java/com/bookmyshow/booking/BookingApiIT.java](booking-service/src/test/java/com/bookmyshow/booking/BookingApiIT.java)
- [booking-service/src/test/java/com/bookmyshow/booking/BookingControllerTest.java](booking-service/src/test/java/com/bookmyshow/booking/BookingControllerTest.java)
- [booking-service/src/test/java/com/bookmyshow/booking/BookingKafkaIT.java](booking-service/src/test/java/com/bookmyshow/booking/BookingKafkaIT.java)
- [booking-service/src/test/resources/application.properties](booking-service/src/test/resources/application.properties)
- [payment-service/.gitignore](payment-service/.gitignore)
- [payment-service/pom.xml](payment-service/pom.xml)
- [payment-service/README.md](payment-service/README.md)
- [payment-service/src/main/java/com/bookmyshow/payment/messaging/BookingCreatedListener.java](payment-service/src/main/java/com/bookmyshow/payment/messaging/BookingCreatedListener.java)
- [payment-service/src/main/java/com/bookmyshow/payment/messaging/EventCodec.java](payment-service/src/main/java/com/bookmyshow/payment/messaging/EventCodec.java)
- [payment-service/src/main/java/com/bookmyshow/payment/messaging/EventStore.java](payment-service/src/main/java/com/bookmyshow/payment/messaging/EventStore.java)
- [payment-service/src/main/java/com/bookmyshow/payment/messaging/KafkaConfiguration.java](payment-service/src/main/java/com/bookmyshow/payment/messaging/KafkaConfiguration.java)
- [payment-service/src/main/java/com/bookmyshow/payment/messaging/OutboxPublisher.java](payment-service/src/main/java/com/bookmyshow/payment/messaging/OutboxPublisher.java)
- [payment-service/src/main/java/com/bookmyshow/payment/messaging/OutboxScheduler.java](payment-service/src/main/java/com/bookmyshow/payment/messaging/OutboxScheduler.java)
- [payment-service/src/main/java/com/bookmyshow/payment/messaging/PaymentEvent.java](payment-service/src/main/java/com/bookmyshow/payment/messaging/PaymentEvent.java)
- [payment-service/src/main/java/com/bookmyshow/payment/messaging/PermanentEventException.java](payment-service/src/main/java/com/bookmyshow/payment/messaging/PermanentEventException.java)
- [payment-service/src/main/java/com/bookmyshow/payment/payment/MockPaymentProcessor.java](payment-service/src/main/java/com/bookmyshow/payment/payment/MockPaymentProcessor.java)
- [payment-service/src/main/java/com/bookmyshow/payment/payment/Payment.java](payment-service/src/main/java/com/bookmyshow/payment/payment/Payment.java)
- [payment-service/src/main/java/com/bookmyshow/payment/payment/PaymentController.java](payment-service/src/main/java/com/bookmyshow/payment/payment/PaymentController.java)
- [payment-service/src/main/java/com/bookmyshow/payment/payment/PaymentProcessor.java](payment-service/src/main/java/com/bookmyshow/payment/payment/PaymentProcessor.java)
- [payment-service/src/main/java/com/bookmyshow/payment/payment/PaymentRepository.java](payment-service/src/main/java/com/bookmyshow/payment/payment/PaymentRepository.java)
- [payment-service/src/main/java/com/bookmyshow/payment/payment/PaymentService.java](payment-service/src/main/java/com/bookmyshow/payment/payment/PaymentService.java)
- [payment-service/src/main/java/com/bookmyshow/payment/payment/PaymentStatus.java](payment-service/src/main/java/com/bookmyshow/payment/payment/PaymentStatus.java)
- [payment-service/src/main/java/com/bookmyshow/payment/PaymentServiceApplication.java](payment-service/src/main/java/com/bookmyshow/payment/PaymentServiceApplication.java)
- [payment-service/src/main/java/com/bookmyshow/payment/security/JwtValidationConfiguration.java](payment-service/src/main/java/com/bookmyshow/payment/security/JwtValidationConfiguration.java)
- [payment-service/src/main/java/com/bookmyshow/payment/security/SecurityConfiguration.java](payment-service/src/main/java/com/bookmyshow/payment/security/SecurityConfiguration.java)
- [payment-service/src/main/java/com/bookmyshow/payment/security/SecurityProblemSupport.java](payment-service/src/main/java/com/bookmyshow/payment/security/SecurityProblemSupport.java)
- [payment-service/src/main/resources/application.yml](payment-service/src/main/resources/application.yml)
- [payment-service/src/main/resources/application-dev.yml](payment-service/src/main/resources/application-dev.yml)
- [payment-service/src/main/resources/db/migration/V1__payments_and_events.sql](payment-service/src/main/resources/db/migration/V1__payments_and_events.sql)
- [payment-service/src/test/java/com/bookmyshow/payment/EventCodecTest.java](payment-service/src/test/java/com/bookmyshow/payment/EventCodecTest.java)
- [payment-service/src/test/java/com/bookmyshow/payment/PaymentDomainTest.java](payment-service/src/test/java/com/bookmyshow/payment/PaymentDomainTest.java)
- [payment-service/src/test/java/com/bookmyshow/payment/PaymentKafkaIT.java](payment-service/src/test/java/com/bookmyshow/payment/PaymentKafkaIT.java)
- [payment-service/src/test/java/com/bookmyshow/payment/PaymentSecurityTest.java](payment-service/src/test/java/com/bookmyshow/payment/PaymentSecurityTest.java)
- [payment-service/src/test/java/com/bookmyshow/payment/TestTokens.java](payment-service/src/test/java/com/bookmyshow/payment/TestTokens.java)
- [payment-service/src/test/resources/application.properties](payment-service/src/test/resources/application.properties)
- [payment-service/src/test/resources/keys/test-private.pem](payment-service/src/test/resources/keys/test-private.pem)
- [payment-service/src/test/resources/keys/test-public.pem](payment-service/src/test/resources/keys/test-public.pem)
- [pom.xml](pom.xml)
- [README.md](README.md)
- [Earlier_answer.md](Earlier_answer.md)

## Prompt 8 - Redis caching, TTL, invalidation, distributed locking and rate limiting (2026-09-28)

### Inspection and preserved architecture

Inspected the clean current repository, Java 17 / Spring Boot 3.5.16 module conventions, MovieService and
DTOs, transaction behavior, Gateway routes/JWT chain, existing integration setup and Notification tests.
The Redis phase adds no database migrations or changes to domain events. Booking/Payment/Identity/
Notification source is unchanged, as are Gateway route definitions, security configuration, seat locking,
Kafka topics, outboxes, inbox idempotency and retry/DLT processing. PostgreSQL remains authoritative.

```text
Client -> Gateway -> Redis shared movie-read quota
                  -> Catalog -> Redis movie cache (HIT)
                             -> PostgreSQL (MISS) -> populate cache

Booking DB + Outbox -> Kafka BookingCreated -> Payment DB + Outbox
 -> Kafka PaymentResult -> Booking CONFIRMED + Reservation CONFIRMED + Seats BOOKED + Outbox
 -> Kafka BookingConfirmed -> Notification DB + Inbox -> Mock Email/SMS
```

### Dependencies and configuration

* Catalog: spring-boot-starter-data-redis, using StringRedisTemplate and Boot-managed Lettuce.
* Gateway: spring-boot-starter-data-redis-reactive, using ReactiveStringRedisTemplate without blocking
  WebFlux threads. Added Testcontainers JUnit dependency and the standard integration/Failsafe profile.
* No extra distributed-lock library or Redis-specific Testcontainers artifact: GenericContainer runs
  redis:7.4-alpine; existing PostgreSQL and Kafka Testcontainers remain in use.
* REDIS_HOST=localhost, REDIS_PORT=6379, optional REDIS_PASSWORD, REDIS_CONNECT_TIMEOUT=500ms and
  REDIS_COMMAND_TIMEOUT=500ms in Catalog/Gateway. Redis repositories are disabled; JPA repositories remain.
* MOVIE_CACHE_ENABLED=true, MOVIE_CACHE_TTL=PT5M.
* MOVIE_RATE_LIMIT_ENABLED=true, MOVIE_RATE_LIMIT=60, MOVIE_RATE_WINDOW=PT1M.
* Optional MOVIE_REPORT_ENABLED=false, MOVIE_REPORT_INTERVAL_MS=60000, MOVIE_REPORT_LEASE=PT30S.
* Podman setup: `podman run --name bookmyshow-redis -d -p 6379:6379 docker.io/library/redis:7.4-alpine`.
  Check with `podman exec bookmyshow-redis redis-cli PING`; use `podman start bookmyshow-redis` if it exists.

### Cache-aside and transaction boundaries

Movie-by-ID GET checks movie:{id}, returns a cached MovieResponse JSON DTO on hit, or queries PostgreSQL,
populates Redis with configured TTL and returns on miss. Cache hits do not create a database transaction.
Read calls inside an existing transaction bypass Redis so uncommitted entity changes are not cached.
Missing database rows remain 404 and are not negatively cached. Lists and other domain data are not cached.
Expired/missing/corrupt cache entries fall back to PostgreSQL. Reads do not refresh TTL.

Movie create/update/delete publish MovieChanged, a local Spring event. A transactional AFTER_COMMIT
listener invalidates the ID key only after successful commit. Rollbacks retain the existing cache value.
Redis access failures are logged and caught on get/put/evict: reads fall back and database writes remain
successful. Existing MovieService unit tests now inject mocked cache/event collaborators.

Invalidation is eventual consistency: concurrent readers, delayed old cache fills, failed eviction,
process crash after DB commit and direct SQL writes can leave stale entries. TTL bounds an entry's lifetime
after population, not a strict bound after a concurrent DB write. This is acceptable for display data;
seat availability and booking decisions never use this cache. Durable invalidation/versioned fills and
stampede protection are deferred.

### Distributed lease implementation

RedisLeaseLock uses atomic SET NX with TTL, a fresh UUID per acquisition, and atomic Lua compare-and-delete
for owner-only unlock. Expiry frees abandoned leases after process failure. An old owner cannot delete a
successor's lease. The optional MovieReportJob coordinates a harmless database COUNT/log operation under
lock:catalog:movie-count-report; no lease or unavailable Redis means skip work. Release runs in finally.

There is no renewal, fencing or consensus guarantee. A paused/slow worker can outlive its lease, and Redis
restart/failover/eviction can break exclusivity. Duplicate reports are harmless. This prevents simultaneous
work only while the lease is valid, not exactly one execution per reporting interval. Existing PostgreSQL
PESSIMISTIC_WRITE seat locks, transaction boundaries and constraints remain the correctness mechanism.

### Gateway rate limiting

An atomic Lua fixed-window counter limits only GET /api/v1/movies and /api/v1/movies/**. The first accepted
request starts the TTL; later requests increment up to the limit without extending expiry. Rejected requests
also do not extend the window. All Gateway instances use the shared Redis counter rather than Java state.

Keys are rate:gateway:movies:<SHA-256(identity)> using user:<verified JWT subject> or ip:<socket peer address>.
Changing movie IDs/query strings does not create new buckets. Arbitrary X-Forwarded-For and X-User-Id are not
trusted. JWT validation still runs before routing. Rejection returns 429, Retry-After and remaining=0
without calling the backend; allowed responses include limit/remaining headers.

Redis failure deliberately fails open for these Catalog reads with a warning and without claiming quota
enforcement. The fallback is scoped to Redis evaluation, not downstream errors. Fixed-window boundary bursts,
NAT/proxy bucket sharing, anonymous/authenticated bucket switching, Redis counter loss and direct-service
bypass are documented. Trusted-proxy support, sensitive endpoint policies, smooth token buckets and abuse
protection are deferred.

### Tests added

28 new tests: 12 unit tests and 16 real Redis integration tests.

* MovieCacheTest (5): DB fallback, malformed cache eviction, disabled cache, failed invalidation and TTL validation.
* MovieReportJobTest (3): no work on Redis failure/contention and release after report failure.
* CatalogRedisIT (10): real PostgreSQL + Redis cache miss/hit, TTL expiration/repopulation, after-commit
  create/update/delete invalidation, rollback/no uncommitted cache pollution, Redis-failure DB safety,
  lock owner checks, abandoned lease/successor safety and concurrent instance contention.
* MovieRateLimitFilterTest (4): Booking bypass, downstream error preservation, key identity and property validation.
* GatewayRedisIT (6): allowed/429 HTTP behavior without extra backend calls, spoofed headers, independent verified
  JWT subjects, atomic shared counters under concurrent callers, window expiry and Redis-outage fail-open.

Redis scripts, TTL and contention use real Redis. Cache transaction assertions use real PostgreSQL. Redis
outages are injected at the template boundary rather than shutting down a shared test container. Existing
unit/routing tests disable Redis features through test properties; the new integration tests explicitly
enable them. No tests skip automatically when containers are unavailable.

### Final verification

Complete root command:

```powershell
mvn -Pintegration verify -l C:\Users\kerem\bookmyshow-redis-verify.log
```

**BUILD SUCCESS** on 2026-09-28 at 17:06:40 +05:30; total time **3:56 minutes**.
All six services plus the root aggregator succeeded. All modules compiled and packaged. Zero failures,
errors or skipped tests. Surefire and Failsafe XML reports were inspected for the exact counts below.
| Module | Unit/MVC/security/routing | Integration | Total |
|---|---:|---:|---:|
| catalog-service | 48 | 20 | 68 |
| booking-service | 35 | 33 | 68 |
| identity-service | 21 | 6 | 27 |
| api-gateway | 13 | 6 | 19 |
| payment-service | 6 | 10 | 16 |
| notification-service | 4 | 11 | 15 |
| **Total** | **127** | **86** | **213** |

The 11 Notification integration tests that were previously blocked by Testcontainers environment discovery
also passed in this complete run, resolving the earlier verification gap without changing Notification code.
Initial Catalog/Gateway preexisting unit tests passed; an ambiguous import in the new integration test was
corrected before the successful full run. `git diff --check` passed. No persistent development service or
database was created; tests used disposable Redis, PostgreSQL and Kafka containers through Podman.

### Documentation

REDIS.md explains Redis beyond caching, cache-aside/hit/miss/TTL, invalidation/staleness, failure policies,
safe leases vs PostgreSQL locking, shared rate limits, source-of-truth boundaries, full event architecture,
Podman commands, environment variables and startup/demo/test commands. Root/Catalog/Gateway READMEs link
to it. This entry records implementation inventory and exact results while preserving earlier history.

### Files created
- [REDIS.md](REDIS.md)
- [api-gateway/src/main/java/com/bookmyshow/gateway/ratelimit/MovieRateLimitFilter.java](api-gateway/src/main/java/com/bookmyshow/gateway/ratelimit/MovieRateLimitFilter.java)
- [api-gateway/src/main/java/com/bookmyshow/gateway/ratelimit/MovieRateLimitProperties.java](api-gateway/src/main/java/com/bookmyshow/gateway/ratelimit/MovieRateLimitProperties.java)
- [api-gateway/src/main/java/com/bookmyshow/gateway/ratelimit/RedisMovieRateLimiter.java](api-gateway/src/main/java/com/bookmyshow/gateway/ratelimit/RedisMovieRateLimiter.java)
- [api-gateway/src/test/java/com/bookmyshow/gateway/GatewayRedisIT.java](api-gateway/src/test/java/com/bookmyshow/gateway/GatewayRedisIT.java)
- [api-gateway/src/test/java/com/bookmyshow/gateway/ratelimit/MovieRateLimitFilterTest.java](api-gateway/src/test/java/com/bookmyshow/gateway/ratelimit/MovieRateLimitFilterTest.java)
- [catalog-service/src/main/java/com/bookmyshow/catalog/cache/MovieCache.java](catalog-service/src/main/java/com/bookmyshow/catalog/cache/MovieCache.java)
- [catalog-service/src/main/java/com/bookmyshow/catalog/cache/MovieCacheInvalidator.java](catalog-service/src/main/java/com/bookmyshow/catalog/cache/MovieCacheInvalidator.java)
- [catalog-service/src/main/java/com/bookmyshow/catalog/cache/MovieChanged.java](catalog-service/src/main/java/com/bookmyshow/catalog/cache/MovieChanged.java)
- [catalog-service/src/main/java/com/bookmyshow/catalog/coordination/MovieReportJob.java](catalog-service/src/main/java/com/bookmyshow/catalog/coordination/MovieReportJob.java)
- [catalog-service/src/main/java/com/bookmyshow/catalog/coordination/RedisLeaseLock.java](catalog-service/src/main/java/com/bookmyshow/catalog/coordination/RedisLeaseLock.java)
- [catalog-service/src/test/java/com/bookmyshow/catalog/cache/CatalogRedisIT.java](catalog-service/src/test/java/com/bookmyshow/catalog/cache/CatalogRedisIT.java)
- [catalog-service/src/test/java/com/bookmyshow/catalog/cache/MovieCacheTest.java](catalog-service/src/test/java/com/bookmyshow/catalog/cache/MovieCacheTest.java)
- [catalog-service/src/test/java/com/bookmyshow/catalog/coordination/MovieReportJobTest.java](catalog-service/src/test/java/com/bookmyshow/catalog/coordination/MovieReportJobTest.java)

### Files modified

- [api-gateway/pom.xml](api-gateway/pom.xml)
- [api-gateway/README.md](api-gateway/README.md)
- [api-gateway/src/main/resources/application.yml](api-gateway/src/main/resources/application.yml)
- [api-gateway/src/test/resources/application.properties](api-gateway/src/test/resources/application.properties)
- [catalog-service/pom.xml](catalog-service/pom.xml)
- [catalog-service/README.md](catalog-service/README.md)
- [catalog-service/src/main/java/com/bookmyshow/catalog/movie/MovieService.java](catalog-service/src/main/java/com/bookmyshow/catalog/movie/MovieService.java)
- [catalog-service/src/main/resources/application.yml](catalog-service/src/main/resources/application.yml)
- [catalog-service/src/test/java/com/bookmyshow/catalog/movie/MovieServiceTest.java](catalog-service/src/test/java/com/bookmyshow/catalog/movie/MovieServiceTest.java)
- [catalog-service/src/test/resources/application.properties](catalog-service/src/test/resources/application.properties)
- [Earlier_answer.md](Earlier_answer.md)
- [README.md](README.md)

No schema migrations, real providers, multi-node Redis failover, production TLS/ACL deployment, circuit breaker, durable invalidation, lease fencing or automated benchmark were added. See REDIS.md for the consistency and operational limits. PostgreSQL remains the source of truth.
