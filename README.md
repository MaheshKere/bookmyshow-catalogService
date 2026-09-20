# BookMyShow learning project

Java 17 / Spring Boot 3.5.16, four independently runnable Maven modules, three independently owned PostgreSQL databases.

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
| booking-service | Seat inventory, reservations, bookings, PostgreSQL concurrency | [Booking README](booking-service/README.md) |

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

Run these in separate terminals:

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
| JWT_PUBLIC_KEY_LOCATION | Required Spring Resource URI, all four modules |
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

Booking authorization is deliberately **role-level**, preserving its existing domain and database. Reservations and Bookings do not yet have a user ownership field. Any authenticated USER/ADMIN knowing a reference can operate on it. This phase does not claim owner-only booking privacy/authorization; add subject ownership checks and a migration before exposing real users' bookings. The existing explicit booking-confirm operation still simulates payment.

For a larger deployment, keep service ports private, terminate HTTPS correctly, use secure key storage and key rotation/JWKS, add per-user ownership authorization, and define revocation/account-state policies. Do not treat this learning phase as a complete production identity platform. No Kafka, Redis, Payment/Notification Service, Saga, Outbox, Kubernetes, social login, refresh tokens, service discovery, or complex permissions were added.

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

Gateway tests run the actual reactive gateway/security stack against three disposable local HTTP backends. They verify routing/path/query preservation, Show-seat precedence, authorization, original bearer forwarding, and stripped identity headers. They are not a full four-live-service end-to-end deployment test.

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
