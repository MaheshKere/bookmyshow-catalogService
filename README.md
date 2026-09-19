# BookMyShow learning project

Two independently runnable Java 17 / Spring Boot services share a Maven repository, not a database.

| Service | HTTP port | Database | Responsibility |
|---|---:|---|---|
| [Catalog](catalog-service/README.md) | 8081 | catalog_db | Movie, Theater, Screen, Show |
| [Booking](booking-service/README.md) | 8082 | booking_db | ShowSeat, Reservation, ReservationSeat history, Booking |

```text
Catalog Service -- showId (future REST validation) --> Booking Service
      |                                                 |
  catalog_db                                        booking_db
```

Booking accepts external showId values without accessing Catalog tables. Its PostgreSQL row locks coordinate competing seat reservations across processes. Five-minute holds, cleanup, pending/confirmed bookings, cancellation, and scoped idempotency are implemented. Payment and messaging remain deferred.

See [Booking Service README](booking-service/README.md) for all APIs and request examples, schema/relationships, transaction and locking explanations, Podman database setup, and development startup commands.

From the repository root:

```powershell
mvn test
mvn -Pintegration verify
mvn -pl catalog-service spring-boot:run "-Dspring-boot.run.profiles=dev"
mvn -pl booking-service spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Run the two application commands in separate terminals, with their respective databases running. Integration tests create independent PostgreSQL containers and require a running Docker-compatible engine. No coverage tools are configured or invoked.

Implementation history is in [EARLIER_ANSWERS.md](EARLIER_ANSWERS.md).
