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
