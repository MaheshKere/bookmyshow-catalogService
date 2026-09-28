# Notification Service

Java 17 / Spring Boot 3.5.16, port **8085**, PostgreSQL **notification_db** on local port **5436**.
This service has no business HTTP endpoints or Gateway route. It consumes only confirmed bookings;
it never consumes PaymentSucceeded. No JWT signing key or synchronous Identity/Catalog lookup is needed.

## Complete flow and transaction boundaries

```text
Client -> Gateway -> Booking
  Booking DB transaction [PENDING booking + BookingCreated outbox] -> COMMIT
  separate outbox publisher -> Kafka bookmyshow.booking.created.v1
Payment
  Payment DB transaction [inbox + payment result + result outbox] -> COMMIT
  separate outbox publisher -> Kafka bookmyshow.payment.results.v1
Booking (PaymentSucceeded)
  Booking DB transaction [payment inbox/result + Booking CONFIRMED
    + Reservation CONFIRMED + HELD seats -> BOOKED + BookingConfirmed outbox] -> COMMIT
  separate existing outbox publisher -> Kafka bookmyshow.booking.confirmed.v1
Notification
  Notification DB transaction [claim eventId + EMAIL and SMS notifications
    + mock sender calls + notifications SENT] -> COMMIT
  Kafka offset advances after processing returns
```

Payment success is not proof of booking confirmation. Late success after cancellation/expiration
still follows Booking's existing permanent-failure/reconciliation path, with no confirmation event.
PaymentFailed creates no BookingConfirmed. Matching payment replays do not create another confirmation.
Booking's existing reservation/seat lock order, lifecycle rules and outbox publisher are retained.
No REST notification call, XA transaction or cross-service database access is introduced.

## Contract and Kafka

| Topic | Producer | Consumer |
|---|---|---|
| `bookmyshow.booking.confirmed.v1` | Booking's existing outbox | `notification-service-v1` |
| `bookmyshow.booking.confirmed.v1.DLT` | Notification error handler | Manual investigation/replay only |

Both topics have **3 partitions**, **replication factor 1** for local development. Booking and
Notification declare them through KafkaAdmin. The key is **bookingReference**. Existing BookingCreated,
PaymentResult topics and groups are unchanged. JSON uses an explicit local record in each service;
no persistence entity or Java type header crosses Kafka.

```json
{
  "eventId": "118bfb2a-42d7-48ec-829a-433b6a2e239b",
  "schemaVersion": 1,
  "eventType": "BookingConfirmed",
  "bookingReference": "73d895ff-18d8-42bc-b4a1-2014ec3bbad7",
  "subject": "1",
  "amount": 100.00,
  "currency": "INR",
  "occurredAt": "2030-01-01T10:00:00Z",
  "showId": 100
}
```

Fields come from the persisted Booking; showId is available locally. No movie title, showtime,
contact details or new synchronous lookup is added. Required fields, schema version, exact event type,
positive numeric subject/showId, amount, INR currency and key/reference agreement are validated.
The event ID is generated once at confirmation and stored with serialized JSON in the existing outbox.
Publication retries reuse it. Breaking contract changes need a new topic/version.

## Persistence and idempotency

Flyway `V1__notifications_and_inbox.sql` creates:

* `consumed_events`: event_id UUID primary key, consumed_at timestamp.
* `notifications`: generated ID, event_id, booking_reference, subject, channel, status,
  recipient, message, created_at, sent_at. Unique `(event_id, channel)` and an inbox foreign key.

The service records one EMAIL and one SMS row per event. Status goes PENDING -> SENT within one
transaction; SENT means the mock returned successfully, not that a real recipient received anything.
Failed attempts roll back both rows and the claim. Failure details are carried by Kafka DLT exception
headers and logs rather than a misleading committed FAILED row in a rolled-back transaction.
Hibernate uses `ddl-auto=validate`, UTC timestamps, and `open-in-view=false`.

`INSERT ... ON CONFLICT DO NOTHING` claims eventId using JdbcTemplate in the same JPA/DataSource
transaction as notifications. Concurrent duplicates wait on the PostgreSQL unique constraint;
after the first commit they return as successful no-ops. If the first transaction rolls back,
the next claimant can process it. A crash after DB commit before offset commit is safe on redelivery.
Inbox IDs are retained indefinitely for now. Different event IDs are different events; Booking prevents
duplicate confirmation emission, and operators must preserve IDs when replaying.

## Mock senders and limitations

`NotificationSender` owns channel selection and a `send(Notification)` operation. Two components,
`MockEmailNotificationSender` and `MockSmsNotificationSender`, log the event, booking, synthetic recipient
and message. Email uses `user-<subject>@example.invalid`; SMS uses `mock-sms:user-<subject>`.
These are deliberately non-deliverable placeholders, not resolved user contact information.

Committed duplicate delivery does not call the senders again. **Logs cannot be rolled back**:
if SMS fails after EMAIL logs, or the DB commit fails after both calls, retries may repeat mock log lines.
There are still no duplicate committed notification rows. This is not an exactly-once guarantee for
external email/SMS delivery. Before enabling a real provider, add verified contact resolution, a stable
provider idempotency key (`eventId + channel`), durable dispatch/reconciliation for ambiguous outcomes,
and appropriate timeouts. The abstraction allows provider adapters without changing the Kafka consumer
or event-processing policy, but simply swapping in an external SDK is insufficient for reliable delivery.

An adapter throws `PermanentEventException` for a permanent rejection; other runtime failures are retried.
Tests inject both kinds without adding production-only failure toggles.

## Retry, DLT and failure scenarios

The service follows Booking/Payment's `DefaultErrorHandler` and `DeadLetterPublishingRecoverer` policy:
initial attempt plus **2 retries, 1 second apart**. Permanent malformed/invalid events and permanent
sender rejection skip retries. Duplicates are successful no-ops. DLT keeps the source partition and
original key/value, plus failure headers. `setFailIfSendResultIsError(true)` prevents successful recovery
when publishing to the DLT fails; the source record remains unacknowledged and recovery is retried.
Repeated recovery failures may repeat processing attempts; avoiding silent loss takes precedence over
the normal bounded-attempt policy while Kafka is unavailable. There is no DLT consumer or replay loop.

| Failure | Outcome |
|---|---|
| Notification stopped | Kafka retains confirmed events within configured retention; the same group resumes from committed offsets after restart. |
| notification_db unavailable | Transaction cannot complete; consumer retries, then sends to DLT if Kafka is available. Claim and rows do not partially commit. Startup failure requires restarting the service after DB recovery. |
| Kafka unavailable during confirmation publication | Booking remains CONFIRMED. Its unpublished outbox row is retained and retried by the existing scheduler. |
| Duplicate Kafka delivery | Committed inbox claim makes processing a no-op; no extra rows or sender calls. |
| Transient mock sender failure | Whole Notification transaction rolls back; retry, then DLT after exhaustion. |
| Permanent mock sender failure | Whole transaction rolls back; direct DLT. |
| DLT publication fails | Source recovery is not acknowledged; retry later, never silently discard. |

Notification failure cannot roll back an already committed booking. DLT messages do not automatically
resume after a DB fix. Inspect the cause, then deliberately republish original key/JSON/eventId to the
source topic. Replays can be out of order. Broker retention, durable storage and operations matter:
deleting the local Kafka container loses its data in the existing development setup.

## Configuration

| Environment variable | Default / requirement |
|---|---|
| `NOTIFICATION_SERVER_PORT` | `8085` |
| `NOTIFICATION_DB_URL` | `jdbc:postgresql://localhost:5436/notification_db` |
| `NOTIFICATION_DB_USERNAME` | `notification_user` |
| `NOTIFICATION_DB_PASSWORD` | Required outside dev |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` (dev defaults to `[::1]:9092`) |

Explicit dev profile supplies IPv6 localhost DB access and `notification_user` / `notification_password`,
matching the repository's Windows/Podman conventions. No profile is enabled globally. No JWT configuration
is required for Notification. Keep internal service/Kafka access private in a real deployment.

## Podman and startup

From the repository root; reuse existing Kafka and database containers (see root README).
Start the Podman machine only if stopped:

```powershell
podman machine start
podman volume create notification_pgdata
podman run --name notification-postgres -d -p 5436:5432 -e POSTGRES_DB=notification_db -e POSTGRES_USER=notification_user -e POSTGRES_PASSWORD=notification_password -v notification_pgdata:/var/lib/postgresql/data docker.io/library/postgres:17-alpine
podman exec notification-postgres pg_isready -U notification_user -d notification_db
```

For an already-created stopped container use `podman start notification-postgres`.
If Kafka has not been created yet:

```powershell
podman run --name bookmyshow-kafka -d -p 9092:9092 docker.io/apache/kafka:3.9.1
podman exec bookmyshow-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

Run each service in a separate terminal. Configure JWT public keys for the existing five services and
the private key only for Identity, as described in the root README. Notification needs no keys.

```powershell
mvn -pl identity-service spring-boot:run "-Dspring-boot.run.profiles=dev"
mvn -pl catalog-service spring-boot:run "-Dspring-boot.run.profiles=dev"
mvn -pl payment-service spring-boot:run "-Dspring-boot.run.profiles=dev"
mvn -pl booking-service spring-boot:run "-Dspring-boot.run.profiles=dev"
mvn -pl notification-service spring-boot:run "-Dspring-boot.run.profiles=dev"
mvn -pl api-gateway spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Without the dev profile, configure the variables explicitly in the Notification terminal:

```powershell
$env:NOTIFICATION_DB_URL = 'jdbc:postgresql://localhost:5436/notification_db'
$env:NOTIFICATION_DB_USERNAME = 'notification_user'
$env:NOTIFICATION_DB_PASSWORD = 'notification_password'
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
$env:NOTIFICATION_SERVER_PORT = '8085'
mvn -pl notification-service spring-boot:run
```

## End-to-end example

Use the root README's login, inventory and reservation commands to get `$headers`, `$base` and a reservation
response in `$reservation`. Choose fewer than ten seats to exercise the existing successful mock payment.

```powershell
$body = @{ reservationReference = $reservation.reservationReference } | ConvertTo-Json
$booking = Invoke-RestMethod "$base/api/v1/bookings" -Method Post -Headers $headers -ContentType 'application/json' -Body $body
Invoke-RestMethod "$base/api/v1/bookings/$($booking.bookingReference)" -Headers $headers
Invoke-RestMethod "$base/api/v1/payments/booking/$($booking.bookingReference)" -Headers $headers
podman exec notification-postgres psql -U notification_user -d notification_db -c "select event_id,booking_reference,channel,status,recipient,sent_at from notifications order by id desc limit 10;"
podman exec bookmyshow-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic bookmyshow.booking.confirmed.v1
podman exec bookmyshow-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group notification-service-v1
podman exec bookmyshow-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic bookmyshow.booking.confirmed.v1.DLT --from-beginning --property print.key=true
```

Processing is asynchronous: poll the booking until CONFIRMED, then expect two SENT rows and mock Email/SMS
logs. PaymentFailed/cancelled bookings produce no notifications. To demonstrate catch-up, stop Notification,
create a successful booking, then restart Notification with the same group and database.

## Verification and deferred work

```powershell
mvn test
mvn -Pintegration verify
mvn -pl notification-service,booking-service -Pintegration verify
```

Real PostgreSQL and Kafka Testcontainers verify confirmation/outbox atomicity, failed outbox insert,
broker publication, rollback of inbox/notifications, concurrent duplicates, retry success/exhaustion,
permanent rejection and DLT. Unit tests verify contract validation and failed DLT recovery.
Tests require a running Docker-compatible Podman API and do not skip when it is absent.
See `../Earlier_answer.md` for exact final results and file inventory.

Deferred: real providers and contact lookup, dispatch recovery, templates/localization/preferences,
retention cleanup, DLT operator tooling, monitoring, schema registry, Kafka TLS/SASL/ACLs and production
replication/storage. Existing learning-project pricing, booking ownership and refund limitations remain.
