# BookMyShow learning project

Java 17 / Spring Boot 3.5.16, five independently runnable Maven modules, four independently owned PostgreSQL databases, Apache Kafka.

```text
Client -- Authorization: Bearer <JWT> --> API Gateway :8080
                                            |
                     +----------------------+--------------------+
                     |                      |                    |
               Identity :8083         Catalog :8081        Booking :8082
                     |                      |                    |
                 identity_db            catalog_db           booking_db
               localhost:5434         localhost:5432        localhost:5433
```

| Module | Responsibility | Documentation |
|---|---|---|
| identity-service | Register/login, BCrypt passwords, RSA JWT issuance, own-user details | [Identity README](identity-service/README.md) |
| api-gateway | Routing and early JWT/role rejection | [Gateway README](api-gateway/README.md) |
| catalog-service | Movie, Theater, Screen, Show; public reads and ADMIN writes | [Catalog README](catalog-service/README.md) |
| booking-service | Seat inventory, reservations, bookings, PostgreSQL concurrency, outbox | [Booking README](booking-service/README.md) |
| payment-service | Mock payments, payment_db, Kafka and result outbox | [Payment README](payment-service/README.md) |

Gateway uses Spring Cloud 2025.0.3 (Gateway 4.3.5), compatible with Boot 3.5.x. Versions are pinned through the Spring Cloud BOM; service discovery is not used. See the [official compatibility table](https://spring.io/projects/spring-cloud/) and [2025.0 release notes](https://github.com/spring-cloud/spring-cloud-release/wiki/Spring-Cloud-2025.0-Release-Notes).

## Local setup and signing keys

Existing Catalog and Booking databases retain their previous setup. Start the Podman machine if stopped, then create Identity's separate development database:

```powershell
podman machine start
podman volume create identity_pgdata
podman run --name identity-postgres -d -p 5434:5432 -e POSTGRES_DB=identity_db -e POSTGRES_USER=identity_user -e POSTGRES_PASSWORD=identity_password -v identity_pgdata:/var/lib/postgresql/data docker.io/library/postgres:17-alpine
podman exec identity-postgres pg_isready -U identity_user -d identity_db
```

Skip machine start if already running. For an existing stopped container use podman start identity-postgres. The image initializes the user/database only on first use of an empty volume. The documented password is for this explicitly activated local dev profile.

From the repository root, generate a fresh local RSA pair using only JDK 17:

```powershell
java scripts/GenerateDevKeys.java
```

This writes .local/jwt/private.pem (PKCS#8) and public.pem (X.509), using a 3072-bit RSA key. .local is gitignored. The helper refuses to overwrite existing keys so restarting development does not silently invalidate existing tokens.

In **each service terminal**, from the repository root, configure the public key:

```powershell
$env:JWT_PUBLIC_KEY_LOCATION = 'file:///' + (Resolve-Path .local/jwt/public.pem).Path.Replace('\', '/')
```

In **only the Identity terminal**, additionally configure the private key:

```powershell
$env:JWT_PRIVATE_KEY_LOCATION = 'file:///' + (Resolve-Path .local/jwt/private.pem).Path.Replace('\', '/')
```

Start Kafka and Payment PostgreSQL using the Payment phase commands below before starting Booking. The complete five-service startup sequence is also below. Earlier service commands:

```powershell
mvn -pl identity-service spring-boot:run "-Dspring-boot.run.profiles=dev"
mvn -pl catalog-service spring-boot:run "-Dspring-boot.run.profiles=dev"
mvn -pl booking-service spring-boot:run "-Dspring-boot.run.profiles=dev"
mvn -pl api-gateway spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Configure the key variable(s) in an IDE run configuration too if starting there. Keys have no runtime default, including in dev: missing key configuration fails startup rather than disabling authentication. No production secret or runtime private key is committed. PEM files under src/test/resources are intentionally public **test-only fixtures**, never loaded by a runtime profile or packaged in executable JARs. Do not point a runtime at these test keys.

No persistent local database/container/application was created by the implementation; the commands above are user setup instructions. Tests use disposable PostgreSQL containers.

## Configuration

| Environment variable | Default / use |
|---|---|
| JWT_PUBLIC_KEY_LOCATION | Required Spring Resource URI, all five modules |
| JWT_PRIVATE_KEY_LOCATION | Required PKCS#8 key URI, Identity only |
| JWT_ISSUER | bookmyshow-identity, consistent across all modules |
| JWT_AUDIENCE | bookmyshow-api, consistent across all modules |
| JWT_ACCESS_TOKEN_TTL | PT15M, Identity; must be 1 second through 1 hour |
| IDENTITY_DB_URL | jdbc:postgresql://localhost:5434/identity_db |
| IDENTITY_DB_USERNAME | identity_user |
| IDENTITY_DB_PASSWORD | Required without dev |
| IDENTITY_SERVER_PORT | 8083 |
| GATEWAY_SERVER_PORT | 8080 |
| IDENTITY_SERVICE_URL | http://localhost:8083 |
| CATALOG_SERVICE_URL | http://localhost:8081 |
| BOOKING_SERVICE_URL | http://localhost:8082 |

Identity dev uses jdbc:postgresql://[::1]:5434/identity_db and the local identity_user / identity_password credentials. No dev profile is globally activated. Catalog and Booking retain their existing DB variables/dev profiles. Gateway has no database.

All database services retain Flyway, Hibernate ddl-auto=validate, and open-in-view=false. Identity intentionally does not enable SQL bind logging, since registration parameters include password hashes.

## Authentication flow

Registration:

```text
RegisterRequest -> validation and email normalization -> BCrypt encode
                -> User(role=USER, active=true) -> identity_db
```

Email is stripped and lowercased with Locale.ROOT. A database uniqueness constraint handles concurrent duplicate registrations. Registration does not accept a role field; even an extra JSON role=ADMIN cannot change the server-selected USER role. Names, email and password are validated; BCrypt's 72-byte UTF-8 input bound is checked explicitly. Password DTO toString methods redact credentials. UserResponse never contains password/passwordHash.

Login:

```text
AuthController -> AuthService -> AuthenticationManager (ProviderManager)
    -> DaoAuthenticationProvider -> DatabaseUserDetailsService -> identity_db
    -> PasswordEncoder.matches (BCrypt) -> authenticated UserPrincipal
    -> JwtTokenService / NimbusJwtEncoder -> signed access token
```

AuthService never compares raw password strings. BCrypt is a salted, deliberately expensive one-way password hash (cost 12 here); there is nothing to decrypt. Verification checks the submitted password against the stored hash. Wrong passwords, unknown users and inactive accounts receive the same generic 401 body. Login responses have Cache-Control: no-store.

Subsequent requests in servlet services:

```text
Authorization: Bearer <JWT>
    -> SecurityFilterChain
    -> Spring's BearerTokenAuthenticationFilter (a OncePerRequestFilter)
    -> JwtAuthenticationProvider / NimbusJwtDecoder
    -> verify RS256 signature, issuer, audience, expiration, subject and role
    -> JwtAuthenticationToken in SecurityContext
    -> role authorization -> Controller
```

Generation lives in JwtTokenService/JwtSigningConfiguration. Parsing and validation live in JwtValidationConfiguration. Login's username/password flow lives in AuthService and the standard provider stack. No handwritten token parser or large custom authentication filter is needed. See [Spring Security's JWT resource-server mechanism](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).

## Spring Security concepts

**Authentication** determines who is making the request. **Authorization** decides whether that authenticated identity may perform a specific operation.

A JWT has encoded header, claims, and signature. Our claims are sub (numeric Identity user ID), role, iss, aud, iat, and exp. It contains no password/hash and no unnecessary profile details. The signature proves origin/integrity; it does **not** encrypt the readable claims. Anyone holding a bearer token can use it, so production transport must use HTTPS.

SecurityFilterChain explicitly disables form login, HTTP Basic, CSRF for this header-only API, and saved-request caching. SessionCreationPolicy.STATELESS means the servlet services neither create nor depend on a login session for authentication. SecurityContext is the current request's authenticated identity; it is not persistent login storage. Each new request must provide a valid bearer token.

OncePerRequestFilter is the servlet filter base used by Spring's BearerTokenAuthenticationFilter. The filter extracts the bearer token and delegates verification to the resource-server provider/decoder, then makes the authenticated principal available to authorization and controllers. Invalid tokens do not establish Authentication.

Gateway is reactive WebFlux: it uses SecurityWebFilterChain, Spring's reactive bearer authentication mechanism, and Reactor's security context instead of servlet filters/ThreadLocal assumptions. NoOpServerSecurityContextRepository and NoOpServerRequestCache prevent session-based authentication there.

401 means authentication is absent, invalid, expired, or otherwise rejected. 403 means the authenticated role is insufficient. Security failures return application/problem+json with generic details and no token/secret/stack trace. Validation uses 400, duplicate email 409, and existing downstream business 404/409 responses remain intact. An invalid bearer token returns 401 even on an otherwise-public route.

## Access policy and routing

Gateway and the owning downstream service enforce corresponding rules:

| Operation | Access / destination |
|---|---|
| POST /api/v1/auth/register, /api/v1/auth/login | Public; Identity |
| GET /api/v1/users/me | USER or ADMIN; Identity |
| GET /api/v1/users/admin/status | ADMIN; Identity |
| GET movies/theaters/screens/shows | Public; Catalog |
| Catalog writes | ADMIN; Catalog |
| GET /api/v1/shows/{showId}/seats | Public; Booking |
| POST /api/v1/shows/{showId}/seats | ADMIN; Booking |
| /api/v1/reservations/**, /api/v1/bookings/** | USER or ADMIN; Booking |

The specific /api/v1/shows/{showId}/seats route has order -10, before Catalog's broader /api/v1/shows/** route. Paths and query strings are preserved. No prefix stripping, service discovery, Feign, or OAuth2 login flow is introduced. ADMIN is explicitly allowed on USER booking endpoints without a role hierarchy.

## Trust boundary and deliberate limits

Gateway rejects invalid JWTs early and forwards the original Authorization header. Every downstream service independently validates that JWT using the public key and derives its identity/roles from verified claims. Calling a downstream service directly therefore does not bypass authentication/role rules.

X-User-Id, X-Role, X-Roles and X-Authorities are removed by the Gateway. More importantly, downstream services never use such headers for authentication. A client could bypass a gateway filter or contact a service directly; trusting those headers would let it impersonate someone. A compromised validating service holding only the public key cannot mint signed tokens.

The deliberately small JWT configuration is local to each module rather than a new shared security framework. Tests cover matching policies; avoid letting them drift when adding endpoints.

Access tokens expire after 15 minutes by default, using zero clock-skew allowance (keep machine clocks synchronized). There are no refresh tokens or server-side token sessions. Logging in again issues a new token. Disabling a user stops new logins and /users/me checks the current active flag, but already-issued tokens may continue to authorize **other endpoints, including downstream services, until expiration**. Role changes also take effect on newly issued tokens, not existing signed claims. Immediate revocation and key rotation/JWKS are deferred.

Booking authorization is deliberately **role-level**, preserving its existing domain and database. Reservations have no ownership field; new Bookings record the initiating JWT subject for payment correlation. Any authenticated USER/ADMIN knowing a reference can operate on it. This phase does not claim owner-only booking privacy/authorization; add subject ownership checks and a migration before exposing real users' bookings. The HTTP confirm operation now returns 409; payment results drive confirmation through the existing internal lifecycle.

For a larger deployment, keep service ports private, terminate HTTPS correctly, use secure key storage and key rotation/JWKS, add per-user ownership authorization, and define revocation/account-state policies. Do not treat this learning phase as a complete production identity platform. Payment, Kafka and transactional outboxes are now added as described below. Notification, Redis, Saga orchestration, Kubernetes, social login, refresh tokens and service discovery remain deferred.

## Try it through Gateway

Register in PowerShell:

```powershell
$base = 'http://localhost:8080'
$registration = @{
  email = 'mahesh@example.com'
  password = 'Password@123'
  firstName = 'Mahesh'
  lastName = 'Kere'
} | ConvertTo-Json
Invoke-RestMethod "$base/api/v1/auth/register" -Method Post -ContentType 'application/json' -Body $registration

$login = @{ email = 'mahesh@example.com'; password = 'Password@123' } | ConvertTo-Json
$auth = Invoke-RestMethod "$base/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body $login
$headers = @{ Authorization = "Bearer $($auth.accessToken)" }
Invoke-RestMethod "$base/api/v1/users/me" -Headers $headers
Invoke-RestMethod "$base/api/v1/movies"
```

After an administrator initializes existing Show inventory, use returned seat IDs:

```powershell
$reservation = @{ showId = 100; seatIds = @(1, 2) } | ConvertTo-Json
Invoke-RestMethod "$base/api/v1/reservations" -Method Post -Headers $headers -ContentType 'application/json' -Body $reservation
```

For local ADMIN learning, register a separate account normally, then deliberately promote that specific account using the database administrator connection:

```powershell
podman exec -it identity-postgres psql -U identity_user -d identity_db
```

```sql
UPDATE users SET role = 'ADMIN', updated_at = CURRENT_TIMESTAMP
WHERE email = 'admin@example.com';
```

Log in again to obtain the new ADMIN claim. No public promotion API or default ADMIN password exists. USER access to /api/v1/users/admin/status returns 403; an ADMIN token gets 200.

## Tests

From the root, with a running Docker-compatible engine for Testcontainers:

```powershell
mvn test
mvn -Pintegration verify
mvn -pl identity-service test
mvn -pl identity-service -Pintegration verify
mvn -pl api-gateway test
mvn -pl catalog-service -Pintegration verify
mvn -pl booking-service -Pintegration verify
```

No coverage tools or H2 are used. Test keys are isolated under test resources. Existing HTTP tests now supply an authenticated ADMIN fixture instead of disabling security; their domain/concurrency assertions are preserved. New direct-service security tests independently check missing/invalid/expired tokens, spoofed headers, and roles.

Identity tests cover normalization, duplicate/concurrent registration, BCrypt storage, provider authentication, wrong/inactive credentials, minimal JWT claims, signature/expiry/issuer/audience checks, role authorization, stateless /me access, and real PostgreSQL/Flyway constraints.

Gateway tests run the actual reactive gateway/security stack against four disposable local HTTP backends. They verify routing/path/query preservation, Show-seat precedence, authorization, original bearer forwarding, and stripped identity headers. They are not a full five-live-service end-to-end deployment test.

Implementation history and exact file changes are recorded in [EARLIER_ANSWERS.md](EARLIER_ANSWERS.md).

Verification on 2026-09-20: root `mvn -Pintegration verify` executed all suites below successfully (no failures, errors, or skips).

| Module | Unit / MVC / routing | PostgreSQL integration | Total |
|---|---:|---:|---:|
| catalog-service | 40 | 10 | 50 |
| booking-service | 35 | 19 | 54 |
| identity-service | 21 | 6 | 27 |
| api-gateway | 8 | 0 | 8 |
| **Total** | **104** | **35** | **139** |

The Booking integration suite includes the real concurrent reservation test. No coverage tooling was run.

## Payment and reliable Kafka communication (2026-09-25)

```text
Client -> Gateway :8080 -> Identity :8083 -> identity_db :5434
                      -> Catalog  :8081 -> catalog_db  :5432
                      -> Booking  :8082 -> booking_db  :5433
                      -> Payment  :8084 -> payment_db  :5435

Booking transaction [PENDING booking + BookingCreated outbox]
 -> publisher -> Kafka -> Payment transaction
    [inbox claim + Payment PENDING -> SUCCESS/FAILED + result outbox]
 -> publisher -> Kafka -> Booking transaction
    [inbox claim + payment result + booking/reservation/seat transition]
```

The existing implementations and separate databases remain intact. Payment shares no Booking entity, table or database connection. Root Maven remains an aggregator; each service independently inherits the existing Boot parent.

### Topics and contracts

| Topic (3 partitions, local replication factor 1) | Producer | Consumer |
|---|---|---|
| bookmyshow.booking.created.v1 | Booking outbox | Payment, group payment-service-v1 |
| bookmyshow.payment.results.v1 | Payment outbox | Booking, group booking-service-v1 |
| bookmyshow.booking.created.v1.DLT | Payment error handler | Manual investigation/replay |
| bookmyshow.payment.results.v1.DLT | Booking error handler | Manual investigation/replay |

Success and failure share the results topic. All messages use bookingReference as their Kafka key. Kafka ordering is **within a partition**, not global or across topics. Stable keys put records for one booking in the same partition. Changing partition counts can change that mapping. This phase emits one request and one terminal result per booking; future multi-event aggregates also require publication sequencing across outbox workers.

PaymentEvent is an explicit JSON record maintained locally in both services, without shared domain entities or Java polymorphic type headers. Required fields are eventId (UUID), schemaVersion (1), eventType, bookingReference, subject, decimal amount, currency (INR), occurredAt and expiresAt (UTC). paymentReference is null for BookingCreated and required for PaymentSucceeded/PaymentFailed. Consumers validate Bean Validation constraints, version, type and key/reference agreement. Booking also compares result subject, amount, currency and deadline with its persisted data. Breaking changes require a new contract/topic version.

```json
{
  "eventId": "118bfb2a-42d7-48ec-829a-433b6a2e239b",
  "schemaVersion": 1,
  "eventType": "BookingCreated",
  "bookingReference": "73d895ff-18d8-42bc-b4a1-2014ec3bbad7",
  "subject": "1",
  "amount": 100.00,
  "currency": "INR",
  "paymentReference": null,
  "occurredAt": "2030-01-01T10:00:00Z",
  "expiresAt": "2030-01-01T10:05:00Z"
}
```

### Payment lifecycle and security

1. Booking creation derives subject from the verified JWT, never a client-supplied user ID/header. Because Catalog has no pricing model, the server uses a **learning-only INR 100 per reserved seat**. Creation atomically saves PENDING and the request outbox row.
2. Seats remain HELD with their existing five-minute deadline. Creating an event does not complete a booking.
3. Payment owns its ID/reference, booking reference, subject, amount/currency, status, audit timestamps and optimistic version. Its provider-independent PaymentProcessor mock succeeds for unexpired amounts below INR 1000. Totals of INR 1000 or more, or already-expired requests, fail. Reserve ten seats to exercise deterministic failure. PENDING is an internal transition; the mock completes in the same transaction.
4. Payment persists its terminal state, durable inbox claim and result outbox together.
5. PaymentSucceeded invokes the existing Reservation-first, ascending-seat locking lifecycle, rechecks expiration after lock waits, confirms Booking/Reservation and marks seats BOOKED.
6. PaymentFailed cancels a pending Booking/Reservation and releases held seats. Matching replays are harmless; contradictory terminal results are permanent failures.
7. HTTP POST /api/v1/bookings/{reference}/confirm returns 409. Internal confirmation remains available to the payment consumer and lifecycle tests.
8. Success arriving after expiration/cancellation cannot reclaim released seats. It goes to the DLT for manual reconciliation. Payment success and Booking confirmation are distinct facts; automated refunds are deferred.

GET /api/v1/payments/booking/{bookingReference} requires USER/ADMIN and independently validates the same RS256 signature, issuer, audience, expiry, subject and role as existing services. Only the payment's JWT subject can read it; another subject receives 404, including ADMIN. Gateway independently validates the bearer token and routes /api/v1/payments/**, preserving the existing seat route precedence. Payment receives only the public key. No JWT or private key travels in an event.

Booking/Reservation APIs retain their existing role-level access limitation. Recording the initiating payment subject does not implement reservation ownership. Migrated old Bookings have null payment metadata and produce no retroactive events; their pending reservations can expire/cancel normally. Deadlines are normalized to PostgreSQL microsecond precision for stable event comparisons.

### Transactional outbox, idempotency and retries

JPA state changes and JDBC inbox/outbox writes share the same service-owned DataSource and PostgreSQL transaction. Failure rolls all of them back. There is no XA transaction between Kafka and PostgreSQL.

The scheduler locks one unpublished row with FOR UPDATE SKIP LOCKED, sends the stored JSON using the booking key, waits for broker acknowledgement, marks published_at, and commits. Kafka producer idempotence and acks=all are enabled. A failed send leaves the record pending for a later sweep. A crash after send but before database commit can duplicate publication with the same eventId. This is **at-least-once delivery**, not end-to-end exactly once. Waiting for Kafka holds a database transaction; this is a throughput tradeoff for explicit, readable behavior. Outbox outages retry durably rather than discard events.

The inbox primary key is consumed_events.event_id. INSERT ON CONFLICT DO NOTHING coordinates concurrent duplicate claims; the claim only commits with business processing. Payment additionally uses a transaction-scoped PostgreSQL advisory lock by booking reference and UNIQUE(payments.booking_reference), preventing duplicate processing even for different event IDs. Booking serializes on the Reservation and stores one booking_payment_results row per booking, with a unique payment reference. A matching result is a no-op; a conflicting result is rejected. Inbox and outbox records survive restarts and are retained in this phase.

Kafka offsets advance after the service transaction returns. Transient database/lock failures get two retries one second apart (three total attempts). Permanent contract/business failures go directly to the source topic's .DLT. Duplicates return normally. DLT publication preserves the source partition and must succeed before recovery is acknowledged. If the DLT broker is unavailable, recovery remains unacknowledged and retries later: bounded business retries must not silently lose the record.

There is no automatic DLT replay loop. Inspect the cause and current lifecycle before deliberately republishing the original key/JSON with its eventId to the original topic. Recovery lets later source records proceed, so replay is not guaranteed to restore original business ordering. Logs include eventId, bookingReference and paymentReference where available, without credentials.

Reference: Spring Kafka [DefaultErrorHandler and DeadLetterPublishingRecoverer](https://docs.spring.io/spring-kafka/reference/3.3-SNAPSHOT/kafka/annotation-error-handling.html).

### Exact local commands: Windows and Podman

From the repository root, start Podman only if stopped. Reuse the existing Catalog, Booking and Identity databases.

```powershell
podman machine start
podman volume create payment_pgdata
podman run --name payment-postgres -d -p 5435:5432 -e POSTGRES_DB=payment_db -e POSTGRES_USER=payment_user -e POSTGRES_PASSWORD=payment_password -v payment_pgdata:/var/lib/postgresql/data docker.io/library/postgres:17-alpine
podman exec payment-postgres pg_isready -U payment_user -d payment_db

podman run --name bookmyshow-kafka -d -p 9092:9092 docker.io/apache/kafka:3.9.1
podman exec bookmyshow-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

Wait until topic listing succeeds. This uses the official [single-node KRaft image](https://kafka.apache.org/39/getting-started/docker/) through Podman, without ZooKeeper. Stop/start preserves this Kafka container's writable layer, but deleting/recreating it loses broker data and offsets. Outbox rows already marked published are not an automatic backup of lost broker data.

For existing stopped containers, use:

```powershell
podman start payment-postgres bookmyshow-kafka
```

Start earlier database containers using their existing names; original creation instructions remain in their module READMEs. In every service terminal, configure the same public key (generate once with java scripts/GenerateDevKeys.java only if keys do not already exist):

```powershell
$env:JWT_PUBLIC_KEY_LOCATION = 'file:///' + (Resolve-Path .local/jwt/public.pem).Path.Replace('\', '/')
$env:JWT_ISSUER = 'bookmyshow-identity'
$env:JWT_AUDIENCE = 'bookmyshow-api'
```

In Booking and Payment terminals:

```powershell
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
```

Payment configuration variables, with their defaults except the required non-dev password:

```powershell
$env:PAYMENT_DB_URL = 'jdbc:postgresql://localhost:5435/payment_db'
$env:PAYMENT_DB_USERNAME = 'payment_user'
$env:PAYMENT_DB_PASSWORD = 'payment_password'
$env:PAYMENT_SERVER_PORT = '8084'
```

The explicitly selected dev profile supplies the same local database credentials. In Gateway, its default route can be overridden with:

```powershell
$env:PAYMENT_SERVICE_URL = 'http://localhost:8084'
```

Only Identity receives the private key:

```powershell
$env:JWT_PRIVATE_KEY_LOCATION = 'file:///' + (Resolve-Path .local/jwt/private.pem).Path.Replace('\', '/')
```

Start databases and Kafka first, then run each command in its own configured terminal. Flyway runs at service startup; KafkaAdmin creates the four topics.

```powershell
mvn -pl identity-service spring-boot:run "-Dspring-boot.run.profiles=dev"
mvn -pl catalog-service spring-boot:run "-Dspring-boot.run.profiles=dev"
mvn -pl payment-service spring-boot:run "-Dspring-boot.run.profiles=dev"
mvn -pl booking-service spring-boot:run "-Dspring-boot.run.profiles=dev"
mvn -pl api-gateway spring-boot:run "-Dspring-boot.run.profiles=dev"
```

After login and reservation creation, capture the reservation response in $reservation and use:

```powershell
$body = @{ reservationReference = $reservation.reservationReference } | ConvertTo-Json
$booking = Invoke-RestMethod "$base/api/v1/bookings" -Method Post -Headers $headers -ContentType 'application/json' -Body $body
Invoke-RestMethod "$base/api/v1/bookings/$($booking.bookingReference)" -Headers $headers
Invoke-RestMethod "$base/api/v1/payments/booking/$($booking.bookingReference)" -Headers $headers
```

Payment may initially return 404 before asynchronous processing; poll again. Booking initially returns PENDING, then CONFIRMED or CANCELLED. Inspect topics/DLT:

```powershell
podman exec bookmyshow-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe
podman exec bookmyshow-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic bookmyshow.payment.results.v1.DLT --from-beginning --property print.key=true
```

### Tests and remaining limitations

```powershell
mvn test
mvn -Pintegration verify
```

The complete verify command runs every module's tests, including disposable real PostgreSQL and Kafka Testcontainers. No external application database or Kafka is required, only a working Docker-compatible Podman API and image downloads. Tests do not silently skip without the engine. They cover rollback, duplicate delivery, publication/replay, retry success and exhaustion, DLT, payment/security/routing, invalid transitions and seat concurrency.

This phase does **not** implement real payment gateway integration, Notification Service, Redis, a Saga orchestration framework, Kubernetes, or production Kafka cluster configuration. Also deferred: automated refunds/reconciliation, real pricing, reservation ownership, outbox/inbox cleanup, DLT tooling, Kafka TLS/SASL/ACLs, monitoring, multi-node replication and schema registry. Local Kafka is a trusted development boundary; HTTP JWT validation does not authenticate Kafka producers. A real provider adapter needs provider idempotency, webhook verification and recovery for ambiguous network outcomes; simply replacing the mock with a synchronous SDK call inside this transaction is insufficient.

See [Earlier_answer.md](Earlier_answer.md) for file/schema inventory and final verification counts.
### Current phase verification

Final root mvn -Pintegration verify completed with BUILD SUCCESS on 2026-09-25 at 20:59 IST. All 165 tests passed, with zero failures, errors or skips. Disposable PostgreSQL and Kafka containers were used; no persistent development service was started. Initial sandbox access and one later transient Podman connection failure were resolved by elevated reruns. Maven reports and the final reactor log confirm all five modules succeeded.

| Module | Unit / MVC / security / routing | PostgreSQL / Kafka integration | Total |
|---|---:|---:|---:|
| catalog-service | 40 | 10 | 50 |
| booking-service | 35 | 28 | 63 |
| identity-service | 21 | 6 | 27 |
| api-gateway | 9 | 0 | 9 |
| payment-service | 6 | 10 | 16 |
| **Total** | **111** | **54** | **165** |
