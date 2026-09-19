# Catalog Service — Movie iteration

Java 17, Spring Boot 3.5.16, Maven, PostgreSQL, JPA/Hibernate, Flyway.

## Run

Create a local PostgreSQL role and database (execute as a database administrator):

```sql
CREATE ROLE catalog_user LOGIN PASSWORD 'choose-a-local-password';
CREATE DATABASE catalog_db OWNER catalog_user;
```

PowerShell, from this directory:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/catalog_db'
$env:DB_USERNAME = 'catalog_user'
$env:DB_PASSWORD = 'choose-a-local-password'
mvn spring-boot:run
```

Default port: 8081. Default-profile credentials come from environment variables. The explicit dev profile contains the requested local-only credentials. Flyway creates the schema before Hibernate validates it. PostgreSQL must already be running; application startup does not create the database itself.

## Tests

```powershell
mvn test
mvn -Pintegration verify
mvn package
```

`mvn test` runs service unit tests and MVC slice tests. The integration profile additionally runs `MovieApiIT` and `CatalogApiIT` against `postgres:17-alpine` using Testcontainers. A running Docker-compatible engine is required. On Windows, Podman requires a configured/running machine and a Testcontainers-compatible API connection. Integration tests do not silently skip when an engine is unavailable. They supply isolated database credentials dynamically, so DB_PASSWORD is not needed for tests.

## API

| Method | Path | Result |
|---|---|---|
| POST | /api/v1/movies | 201, response and Location header |
| GET | /api/v1/movies/{id} | 200 or 404 |
| GET | /api/v1/movies?page=0&size=20 | 200, paginated response |
| PUT | /api/v1/movies/{id} | 200 or 404; full replacement of editable fields |
| DELETE | /api/v1/movies/{id} | 204 or 404; physical deletion |

Request example (POST and PUT):

```json
{
  "title": "Arrival",
  "description": "A science fiction movie",
  "language": "English",
  "genre": "Science Fiction",
  "durationMinutes": 116,
  "releaseDate": "2016-11-11",
  "active": true
}
```

Pagination is zero-based; size must be 1–100. Sorting is ID ascending. Response contains content, page, size, totalElements, totalPages. Both active and inactive movies are returned. Title is not unique. Release dates may be in the future. IDs and audit timestamps are server-controlled. Validation errors return 400 Problem Details with an errors map; missing resources return 404; integrity conflicts return 409.

## Design and request flow

Feature packages keep movie code together. Java records define DTOs; Lombok supplies entity getters and its JPA-required protected no-argument constructor. There is no Lombok @Data on the entity: generated setters/equality/toString would unnecessarily expose persistence state and become troublesome with relationships later.

POST flow:

1. Embedded Tomcat accepts the request; Spring MVC DispatcherServlet resolves MovieController.create.
2. Jackson converts JSON into MovieRequest. Bean Validation checks required fields, lengths and positive duration. Invalid requests stop here.
3. Controller invokes the Spring-managed MovieService proxy.
4. The transaction interceptor opens a transaction and binds an EntityManager to the request thread.
5. Service constructs a new Movie and calls the repository proxy's save method.
6. Spring Data's SimpleJpaRepository detects its null ID and calls EntityManager.persist (existing entities can use merge instead).
7. Hibernate invokes @PrePersist, manages the entity, translates mappings into an INSERT and binds JDBC parameters. IDENTITY generation normally requires the insert during persist to obtain the PostgreSQL-generated ID.
8. PostgreSQL checks constraints and inserts the row. Hibernate assigns the generated ID to the managed entity.
9. Service maps the entity to MovieResponse. The transaction proxy commits before control returns to the controller. Commit failure prevents a successful response.
10. Controller creates a 201 response with Location; Jackson serializes the response DTO.

Flush sends changes to the database; it does not commit the transaction. For PUT, a loaded Movie is managed, so Hibernate compares its state and emits UPDATE during flush. Explicit flush triggers @PreUpdate before we map updatedAt. An unchanged PUT can leave updatedAt unchanged. createdAt is not updated. Callback-based audit fields apply to JPA writes; direct SQL writers would need to supply them themselves.

## Dependency injection and repository implementation

@SpringBootApplication enables configuration, auto-configuration and component scanning beneath com.bookmyshow.catalog. @RestController and @Service register application beans. Spring resolves constructor arguments by type: the repository bean is supplied to MovieService, and the transaction-proxied service bean is supplied to MovieController. These beans are singleton by default; request data stays in local variables.

JPA repository infrastructure discovers MovieRepository and registers a factory-produced proxy implementing that interface. CRUD calls delegate to SimpleJpaRepository; EntityManager is the persistence abstraction and Hibernate is its provider. Spring Data also adds transaction and persistence-exception translation behavior. No handwritten implementation or @Repository annotation is needed on this interface.

Service-level transactions define the whole business operation. Class-level readOnly applies to reads; method annotations override it for writes. The repository joins the existing transaction. Runtime exceptions normally trigger rollback. Calling an annotated method through self-invocation bypasses the proxy, so annotations are not universal method interception. Controllers should not hold database transactions across HTTP responsibilities.

## Tests and their boundaries

MovieServiceTest uses MockitoExtension and a mocked MovieRepository. The service, DTOs, mapping and Movie objects are real. Stubs supply Optional/Page results and echo save's argument; verifications check persistence calls. No SQL, generated ID, lifecycle callbacks, dirty checking or transaction interception is exercised there.

MovieControllerTest starts an MVC slice. MockMvc performs HTTP dispatch without opening a network port. MovieService is replaced with @MockitoBean; Jackson, validation and exception advice are real. It checks field errors, missing resources, invalid pagination/IDs and malformed JSON.

MovieApiIT loads the whole application with a real PostgreSQL container and no mocked service/repository. It verifies CRUD, persisted fields, audit timestamps, stable pagination, invalid input, missing updates/deletes, Flyway history and the database duration constraint. Every test cleans movie rows and runs without a surrounding test transaction, so application commits are exercised.

## Schema management

V1__create_movies.sql defines the table, identity primary key, bounded columns, NOT NULL constraints, positive-duration check and TIMESTAMPTZ audit fields. Flyway tracks applied versions/checksums in flyway_schema_history. Never edit an applied migration; add V2, V3, etc. Hibernate ddl-auto=validate catches mapping/schema mismatch. The primary-key index supports this iteration's ID lookups/sort; add additional indexes only when query patterns justify them. Open Session in View is disabled; DTO mapping takes place within service transactions.

## Alternatives and current scope

A sequence generator can support better insert batching than IDENTITY; simplicity wins for this iteration. An interface plus service implementation or MapStruct could become useful with greater complexity, but is unnecessary here. Database-generated timestamps or Spring Data auditing are alternatives to entity callbacks. Soft deletion and @Version-based lost-update protection are deliberate future design choices, not implemented implicitly. Current PUT is last-writer-wins.

The Theater, Screen, and Show extension is documented below. Seat inventory, security, messaging, caching, gateway, booking, payment, and Kubernetes remain outside this iteration.

## Interview questions

1. How do component scanning and Boot auto-configuration differ?
2. How does constructor injection differ from field injection, particularly for testing?
3. How does Spring Data create a repository bean from an interface?
4. When does save use persist versus merge, and why does merge return an entity?
5. What is Hibernate's persistence context, and how does dirty checking work?
6. How do flush and commit differ, and how does IDENTITY affect insert timing/batching?
7. Why does @Transactional fail on self-invocation, and what are its default rollback rules?
8. What does readOnly=true guarantee, and why disable Open Session in View?
9. Why are DTO validation and database constraints both necessary?
10. What can Mockito tests prove that PostgreSQL integration tests cannot, and vice versa?

## Theater, Screen, and Show iteration

All three features live inside catalog-service in feature packages beside movie. Movie sources and V1 remain unchanged. New endpoints return record DTOs; no new endpoint returns an entity.

| Method | Path | Result |
|---|---|---|
| POST | /api/v1/theaters | Create Theater; 201 and Location |
| GET | /api/v1/theaters/{id} | Theater DTO or 404 |
| GET | /api/v1/theaters | Paginated Theaters |
| POST | /api/v1/theaters/{theaterId}/screens | Create Screen under existing Theater |
| GET | /api/v1/screens/{id} | Screen DTO or 404 |
| GET | /api/v1/theaters/{theaterId}/screens | Paginated Screens; missing Theater is 404 |
| POST | /api/v1/shows | Create Show with existing Movie, Screen, expected Theater |
| GET | /api/v1/shows/{id} | Show DTO including Movie/Screen/Theater display fields |
| GET | /api/v1/shows?movieId=1&theaterId=1&city=Pune&date=2026-10-01 | Optional filters combined with AND |

Every list supports page=0&size=20; size is limited to 1?100. Theater/Screen order is ID ascending; Show order is startTime then ID ascending. Missing search matches return an empty page. City is a case-insensitive exact match. Both active and inactive records are returned.

Create examples, in dependency order (substitute generated IDs):

```json
{"name":"Central Cinema","city":"Pune","address":"Main Road","active":true}
```

```json
{"name":"Screen 1","totalSeats":150,"active":true}
```

```json
{
  "movieId":1,
  "screenId":1,
  "theaterId":1,
  "startTime":"2026-10-01T18:00:00+05:30",
  "endTime":"2026-10-01T20:00:00+05:30",
  "active":true
}
```

Times use Instant and PostgreSQL TIMESTAMPTZ. Supply an offset or Z; responses normalize to UTC. date filters the Show start within [00:00 UTC, next day 00:00 UTC), not the cinema's local day or every Show overlapping the day. The API accepts years 0001?9999; search uses finite bounds for an omitted date. A future local-day search should explicitly model Theater time zones.

theaterId in ShowRequest is an expected-parent check, not a duplicated Show database column. The service verifies the Theater exists and matches Screen.theater before saving. It also verifies Movie/Screen existence and strict startTime < endTime. Screen capacity is checked at the DTO, service, and database levels. Missing references return 404, business validation returns 400, and database integrity conflicts return 409. The existing advice now handles method validation as well as request-body validation with field errors.

### Relationships, fetching, and transactions

| Relationship | Owning side / foreign key | Fetch / lifecycle |
|---|---|---|
| Screen -> Theater | Screen / screens.theater_id | LAZY, required, no cascade |
| Show -> Movie | Show / shows.movie_id | LAZY, required, no cascade |
| Show -> Screen | Show / shows.screen_id | LAZY, required, no cascade |

These unidirectional ManyToOne relationships already allow many Screens per Theater and many Shows per Movie/Screen. Parent-side collections are not needed for these use cases: repositories page children by parent ID. There is no OneToMany, inverse side, mappedBy, or orphanRemoval. If a future use case warrants a parent collection, its mappedBy must name the corresponding child property; the child remains the owner because it writes the foreign key.

Cascade is omitted because creating or deleting a Show must not create/delete its Movie, Screen, or Theater. These parents have independent lifecycles. Database foreign keys prevent parent deletion while referenced, including existing Movie DELETE, which now returns 409 for a scheduled Movie.

Show DTO mapping reads Movie title, Screen name, and Theater name/city. Without a fetch plan, a Show page could trigger additional SELECTs for each distinct Movie, Screen, and Theater. ShowRepository uses an EntityGraph on search and findById to fetch movie, screen, and screen.theater together. Only to-one joins are fetched, so SQL pagination is safe; the count query remains separate. The nullable city parameter is explicitly cast to string to avoid PostgreSQL lower(bytea) errors.

Read methods inherit service-level @Transactional(readOnly = true); create methods override with @Transactional. Parent validation, insertion, and DTO mapping occur inside that boundary. save is used only for new entities. No controller transaction or Open Session in View is needed; open-in-view=false and ddl-auto=validate remain unchanged.

### Migration and indexes

V2__create_theaters_screens_shows.sql adds identity primary keys, required columns, three foreign keys, positive totalSeats and strict time-order checks. No existing migration is edited. Audit timestamps retain Movie's JPA callback style.

- screens(theater_id): Theater's Screen listing and Theater-to-Show join path.
- shows(movie_id, start_time): Movie/date searches and Movie foreign-key checks.
- shows(screen_id, start_time): Screen scheduling lookups, Theater-to-Show joins, and Screen foreign-key checks.
- shows(start_time, id): date-only searches and chronological pagination.

Primary keys already have indexes. City/name indexes are deferred until data size and measured query plans justify them. Optional-filter queries deliberately favor readability here; benchmark with representative data before adding search abstractions or more indexes.

### Tests for this iteration

TheaterServiceTest, ScreenServiceTest, and ShowServiceTest use Mockito to verify DTO mapping, reference checks, Theater mismatch, capacity/time validation, date bounds, and pagination. CatalogControllerTest checks HTTP binding/validation, pagination/filter errors, and Problem Details. Existing Movie tests remain in place.

CatalogApiIT uses a real postgres:17-alpine container with the full application and no enclosing test transaction. It verifies committed HTTP creates/reads, multiple Screens per Theater, persisted foreign keys, LAZY mappings, non-cascading deletion, Flyway history, database FK/NOT NULL/CHECK constraints, search combinations and UTC boundaries, pagination, and Movie deletion conflicts. Its Hibernate statistics test uses distinct Movie/Screen/Theater rows and a fresh service transaction: a full Show page needs exactly two SQL statements (content plus count); a single Show needs one.

Run from the repository root:

```powershell
mvn -pl catalog-service test
mvn -pl catalog-service -Pintegration verify
$env:DB_URL = 'jdbc:postgresql://localhost:5432/catalog_db'
$env:DB_USERNAME = 'catalog_user'
$env:DB_PASSWORD = 'choose-a-local-password'
mvn -pl catalog-service spring-boot:run
```

A running PostgreSQL database is needed for application startup. Tests create their own isolated PostgreSQL containers and need access to a Docker-compatible API. In a restricted agent sandbox, the integration command may require approval for named-pipe access to the running Podman machine. No runtime or credentials are hard-coded into the project.

### Decisions before the next phase

No seat records, ShowSeat, booking, concurrency controls, overlap prevention, capacity allocation, pricing, or new microservice have been added. totalSeats is descriptive capacity only. active is descriptive; it does not prohibit creating a Show for an inactive parent. End time is supplied explicitly and is not derived from Movie duration. Duplicate Theater/Screen names and overlapping Shows are currently allowed. Decide those scheduling policies explicitly in a later iteration.

There are no new update/delete endpoints. Future changes to parent lifecycles must respect the foreign keys. The pre-existing Movie language endpoint returns Movie entities; it was left unchanged to preserve the requested Movie scope. All added endpoints use DTOs.

Verification on 2026-09-19: `mvn -pl catalog-service -Pintegration verify` passed all 37 unit/MVC tests and 10 PostgreSQL integration tests (zero failures, errors, or skips), including executable-JAR packaging.

## Local development profile

From catalog-service in PowerShell, with the existing local PostgreSQL database running:

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

The dev profile connects to jdbc:postgresql://[::1]:5432/catalog_db as catalog_user with password catalog_password. It is never activated globally. Common application.yml still controls Flyway, Hibernate schema validation, UTC JDBC handling, disabled Open Session in View, and the server settings. Default-profile datasource environment-variable behavior is unchanged.

Development logging enables DEBUG for com.bookmyshow.catalog and org.hibernate.SQL, TRACE for org.hibernate.orm.jdbc.bind (SQL parameter values), and formatted SQL. These logging settings apply only to dev; broad Spring DEBUG logging is not enabled.

Spring Boot DevTools is an optional runtime dependency. During spring-boot:run it restarts the application when compiled classpath files change. Saving Java source alone is insufficient: use your IDE's build action or run mvn compile in another terminal. Spring Boot's repackage goal excludes DevTools from the executable production JAR by default; optional also prevents propagation to downstream consumers. Do not force-enable DevTools in production.
