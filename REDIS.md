# Redis: caching, leases and rate limiting

Java 17 / Spring Boot 3.5.16 remain unchanged. Catalog adds Spring Data Redis (Lettuce);
Gateway adds its reactive starter so Redis calls do not block WebFlux event-loop threads.
PostgreSQL remains the source of truth. No Redis dependency is added to Booking, Payment,
Identity or Notification, and their JWT/Kafka/outbox/inbox/retry/DLT behavior is preserved.

## Architecture

```text
Client
  -> Gateway :8080 -> Redis :6379 [movie-read rate-limit window]
       -> Catalog :8081 -> Redis [movie cache HIT -> return DTO]
                        -> PostgreSQL catalog_db [MISS -> load -> cache -> return DTO]
       -> Booking :8082 -> PostgreSQL booking_db + BookingCreated outbox
            -> Kafka BookingCreated -> Payment :8084 -> payment_db + result outbox
            -> Kafka PaymentResult -> Booking CONFIRMED + Reservation CONFIRMED
                 + Seats BOOKED + BookingConfirmed outbox
            -> Kafka BookingConfirmed -> Notification :8085 -> notification_db
                 + transactional inbox -> Mock Email/SMS

Catalog optional reporting job -> Redis lease -> PostgreSQL COUNT -> log
```

Existing topics/groups are unchanged: bookmyshow.booking.created.v1 / payment-service-v1,
bookmyshow.payment.results.v1 / booking-service-v1, and bookmyshow.booking.confirmed.v1 /
notification-service-v1, with their existing DLTs. See [Notification architecture](notification-service/README.md).

## What Redis is, and why it is more than a cache

Redis is a networked in-memory data store. Applications share its keys and data structures across
processes. Here, strings hold cached JSON, counters track requests, and short-lived keys represent
lock leases. Atomic commands and short Lua scripts provide coordination as well as fast reads.
Redis also supports other data structures and messaging use cases; this project retains Kafka
for durable domain events rather than introducing another event transport.

Redis Lua scripts execute atomically relative to other Redis operations. Keep them short because
execution blocks other server work. The rate-limit and unlock scripts use a single explicit key
and argument values, with no application-generated script text. See [Redis Lua documentation](https://redis.io/docs/latest/develop/programmability/eval-intro/).

## Cache-aside, hit, miss and TTL

Only `GET /api/v1/movies/{id}` is cached. Lists, other Catalog APIs and booking/seat state are not cached.
`MovieCache` stores MovieResponse JSON, never a JPA entity or Java-serialized object.

```text
GET movie/101 -> GET movie:101
  HIT  -> deserialize DTO -> return, without repository lookup or a new DB transaction
  MISS -> MovieRepository/PostgreSQL -> serialize DTO -> SET movie:101 with TTL -> return

movie:101 -> TTL expires -> key is unavailable
next GET -> PostgreSQL -> fresh cache entry
```

The default TTL is five minutes, configurable through MOVIE_CACHE_TTL. Reads do not extend the TTL.
Missing database records remain 404 and are not negatively cached. Missing/expired/corrupt JSON cache
entries fall back to PostgreSQL. If getById joins an existing database transaction, it bypasses Redis
entirely, avoiding reads or publication of uncommitted movie changes through the cache.

## Invalidation and stale-cache trade-offs

Create, update and delete publish a local MovieChanged Spring event inside the database transaction.
MovieCacheInvalidator listens AFTER_COMMIT and deletes movie:{id}. Rollback does not evict the existing
entry. The next GET loads the new committed row, or returns 404 after delete. This is a local application
event, not a new Kafka contract. Existing write authorization and database constraints remain unchanged.

This is intentionally eventual consistency, not an atomic PostgreSQL/Redis transaction:

* A concurrent read can return the old cache entry while a write is committing.
* A cache-miss reader can fetch an old database value, pause, then populate it after a writer evicts.
* The process can stop after database commit but before invalidation, or Redis can reject invalidation.
* Direct database edits bypass the application event and are visible after expiration/reload.

TTL limits the lifetime of each populated entry; it is not a strict five-minute bound from the database
write if an old reader is delayed before filling the cache. During an invalidation outage a cached deleted
movie may remain visible until expiry. This is acceptable for the learning Catalog display example,
not for seat availability, payment decisions or authoritative state. Versioned cache fills and durable
invalidation/CDC would be future improvements where stronger freshness is required. A stampede of
concurrent misses can hit PostgreSQL more than once; no distributed lock is used to claim correctness here.

## Redis failure behavior

Catalog catches Redis data-access failures on get/put/evict, logs them, and preserves database behavior.
Reads fall back to PostgreSQL; successful writes remain committed even if after-commit eviction fails.
Connect and command timeouts default to 500ms each. During an outage, a miss may pay for both attempted
get and put; there is no circuit breaker in this learning implementation.

Gateway movie-read rate limiting fails open with a warning if Redis is unavailable: requests continue
through the existing JWT/security/routing chain, with no rate-limit headers claiming a quota was enforced.
The reporting job fails closed for acquisition: no confirmed lease means no report work. None of these
policies weaken PostgreSQL seat/reservation locking. Redis restart/loss can reset caches, leases and quotas.

## Distributed lock learning example

Enable the optional Catalog MovieReportJob to log the database movie count. It is disabled by default,
runs with a 60-second fixed delay per instance, and changes no business data.

* Key: `lock:catalog:movie-count-report`.
* Owner: a fresh UUID for every acquisition attempt, not just an instance name.
* Acquire: one atomic `SET key token NX PX leaseMillis` (Spring setIfAbsent with Duration).
* Lease: 30 seconds by default, configurable. No automatic renewal.
* Unlock: Lua compares the current token and deletes only if it still matches.
* Crash: the key expires so another instance can acquire it. No TTL would leave an abandoned lock indefinitely.

An unconditional DEL can erase another owner's lock after the original lease expires. Separate GET and
DEL operations have the same race; compare-and-delete must be atomic. A paused instance can outlive its
lease and run alongside a successor. Token-based unlock prevents deleting the successor's key but cannot
stop the old process's work. Redis restart, eviction or failover can also invalidate lease assumptions.
This is a single-Redis learning lease, not a fenced or consensus-backed correctness lock.
See [Redis distributed-lock guidance](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/).

Duplicate log reports are harmless. The lock prevents overlap only while the lease remains valid; it does
not guarantee one report per interval across instances. Sequential instances may each report after release.
For financial or inventory work, use database transactions/constraints or a design with resource-enforced
fencing rather than relying on this lease.

| PostgreSQL PESSIMISTIC_WRITE | Redis lease |
|---|---|
| Protects authoritative rows within a database transaction | Coordinates a named activity across processes |
| Existing Booking locks Reservation then seats; state/constraints commit together | TTL may expire while the worker is still active |
| Database releases row locks on commit/rollback/connection failure | Expiry recovers an abandoned lease; token check protects unlock |
| Preferred for seat availability and preventing double booking | Useful for best-effort scheduled work where duplicate execution is safe |

## Distributed rate limiting

Gateway limits only GET `/api/v1/movies` and `/api/v1/movies/**`, including list queries. All those paths
share a movie-read budget per client; changing movie IDs/query parameters cannot create a new bucket.
Writes, authentication endpoints, other Catalog paths and Booking/Payment routes retain existing behavior.

The algorithm is a fixed-duration window starting with the client's first accepted request:

1. Lua atomically creates counter 1 with window TTL, or reads an existing counter.
2. Below the limit it increments; at the limit it rejects without extending the TTL.
3. It returns allowed/remaining/remaining TTL. Expiration starts a fresh window on the next request.

Default: 60 allowed requests per 60 seconds. Multiple Gateway instances use the same Redis key, avoiding
the multiplied quotas that separate in-memory Java counters would allow. Redis TTL supplies window timing;
the limiter does not depend on synchronized JVM clocks.

Key: `rate:gateway:movies:<SHA-256(identity)>`. Identity is `user:<verified JWT subject>` when authenticated,
otherwise `ip:<socket peer address>`. Hashing keeps keys bounded and avoids putting raw IDs in key names;
it is not anonymization. X-User-Id and arbitrary X-Forwarded-For headers are not trusted. Behind a proxy,
anonymous clients may share the proxy's socket address and quota; trusted-proxy identity extraction is
deliberately deferred. Do not enable framework forwarding/address rewriting for untrusted inbound headers.
Users behind NAT share an anonymous bucket, and switching between anonymous and authenticated use provides
different buckets. This example is not a comprehensive anti-abuse system.

Allowed responses include X-RateLimit-Limit and X-RateLimit-Remaining. Rejected requests get HTTP **429**
and Retry-After in whole seconds, rounded up from Redis TTL, without calling the backend. JWT failures
remain 401/403 before routing. Downstream errors are not swallowed by the limiter's Redis fallback.

Fixed windows allow bursts near boundaries (up to twice the limit over a short boundary-spanning period).
Token buckets/sliding windows can smooth bursts. Fail-open behavior, counter eviction/restart and direct
Catalog access can bypass this learning protection. Production ingress controls, separate login quotas,
metrics and a deliberate fail-closed policy for sensitive endpoints would be separate work.

## Local Podman setup and configuration

Start the Podman machine if stopped, then create a local Redis container. No persistent data is required
for this example; PostgreSQL and Kafka continue using the existing setup.

```powershell
podman machine start
podman run --name bookmyshow-redis -d -p 6379:6379 docker.io/library/redis:7.4-alpine
podman exec bookmyshow-redis redis-cli PING
```

Expected reply: PONG. For an existing stopped container use `podman start bookmyshow-redis`.
This unauthenticated local container is for development on a trusted machine. Use private networking,
authentication/ACLs, TLS, capacity planning and deployment-specific key isolation in production.

In the Catalog and Gateway terminals:

```powershell
$env:REDIS_HOST = 'localhost'
$env:REDIS_PORT = '6379'
$env:REDIS_CONNECT_TIMEOUT = '500ms'
$env:REDIS_COMMAND_TIMEOUT = '500ms'
# Set REDIS_PASSWORD only when Redis itself is configured with authentication.
```

| Variable | Service | Default |
|---|---|---|
| REDIS_HOST / REDIS_PORT | Catalog, Gateway | localhost / 6379 |
| REDIS_PASSWORD | Catalog, Gateway | Empty (local Redis without auth) |
| REDIS_CONNECT_TIMEOUT / REDIS_COMMAND_TIMEOUT | Catalog, Gateway | 500ms / 500ms |
| MOVIE_CACHE_ENABLED | Catalog | true |
| MOVIE_CACHE_TTL | Catalog | PT5M |
| MOVIE_REPORT_ENABLED | Catalog | false |
| MOVIE_REPORT_INTERVAL_MS | Catalog | 60000 |
| MOVIE_REPORT_LEASE | Catalog | PT30S |
| MOVIE_RATE_LIMIT_ENABLED | Gateway | true |
| MOVIE_RATE_LIMIT | Gateway | 60 |
| MOVIE_RATE_WINDOW | Gateway | PT1M |

Use positive TTL/lease/window durations and positive limits. All Gateway instances sharing the counter
must use the same settings. Changing a configured window affects new keys; existing keys retain their TTL.
For a quick 429 demonstration set MOVIE_RATE_LIMIT=3 and MOVIE_RATE_WINDOW=PT10S in the Gateway terminal.
To demonstrate the lock set MOVIE_REPORT_ENABLED=true in two Catalog instances with different server ports.

Configure JWT public keys and existing database/Kafka variables exactly as in the root README, then run
each service in its own terminal. Notification needs no JWT keys.

```powershell
mvn -pl identity-service spring-boot:run "-Dspring-boot.run.profiles=dev"
mvn -pl catalog-service spring-boot:run "-Dspring-boot.run.profiles=dev"
mvn -pl booking-service spring-boot:run "-Dspring-boot.run.profiles=dev"
mvn -pl payment-service spring-boot:run "-Dspring-boot.run.profiles=dev"
mvn -pl notification-service spring-boot:run "-Dspring-boot.run.profiles=dev"
mvn -pl api-gateway spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Use a real movie ID from the existing Catalog setup:

```powershell
curl.exe -i http://localhost:8080/api/v1/movies/1
podman exec bookmyshow-redis redis-cli GET movie:1
podman exec bookmyshow-redis redis-cli PTTL movie:1
podman exec bookmyshow-redis redis-cli --scan --pattern 'rate:gateway:movies:*'
podman exec bookmyshow-redis redis-cli GET lock:catalog:movie-count-report
```

The report is quick and releases its lease immediately, so the last command often returns nil. Integration
tests exercise lease contention/expiry deterministically. Repeat the HTTP request beyond the configured
limit to see 429; wait for the window to expire and try again. Use the existing authenticated ADMIN movie
PUT/DELETE commands to observe invalidation. A nonexistent movie returns 404 and creates no movie cache entry.

## Tests and verification

```powershell
mvn test
mvn -Pintegration verify
mvn -pl catalog-service,api-gateway -Pintegration verify
```

Gateway now has the same integration Maven profile as the database services. Ordinary preexisting tests
disable the new Redis features in test properties, keeping routing/domain tests independent of local Redis;
the new integration classes explicitly enable them with disposable Redis containers. CatalogRedisIT uses
both real PostgreSQL and Redis. GatewayRedisIT uses real Redis and a disposable HTTP backend.

Coverage includes cache miss/hit, configured expiration, after-commit create/update/delete invalidation,
rollback/no uncommitted cache pollution, database fallback, owner-only unlock, abandoned-lease expiry,
concurrent acquisition, shared atomic quotas, expiry reset, 429 without backend invocation, spoofed headers,
verified JWT bucket separation and Redis-failure policy. Outages are injected through the Redis template;
expiration, concurrency and scripts use real Redis. These are not multi-node Redis failover tests.

The full Maven run also executes existing Booking, Payment, Notification, JWT, Gateway and PostgreSQL/Kafka
tests. Exact counts and final verification status are recorded in [Earlier_answer.md](Earlier_answer.md).
There are no database migrations or domain-event schema changes in this Redis phase.
