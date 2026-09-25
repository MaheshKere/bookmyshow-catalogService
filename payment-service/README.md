# Payment Service

Java 17 / Spring Boot 3.5.16, port 8084, PostgreSQL payment_db on 5435.

Consumes BookingCreated v1, owns Payment and durable inbox/outbox records, and publishes PaymentSucceeded/PaymentFailed v1. The mock succeeds for an unexpired total below INR 1000; otherwise it fails. No external provider is called.

GET /api/v1/payments/booking/{bookingReference} requires a valid USER/ADMIN JWT and returns only that subject's payment. There is no public mutation or success simulation endpoint.

See the root [README](../README.md#payment-and-reliable-kafka-communication-2026-09-25) for contracts, exact Podman/startup commands, retry/idempotency design and limitations.

Run mvn -pl payment-service test for unit/security tests or mvn -pl payment-service -Pintegration verify for those plus real PostgreSQL/Kafka integration tests.