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

Default port: 8081. No credentials are committed. Flyway creates the schema before Hibernate validates it. PostgreSQL must already be running; application startup does not create the database itself.

## Tests

```powershell
mvn test
mvn -Pintegration verify
mvn package
```

`mvn test` runs service unit tests and MVC slice tests. The integration profile additionally runs `MovieApiIT` against `postgres:17-alpine` using Testcontainers. A running Docker-compatible engine is required. On Windows, Podman requires a configured/running machine and a Testcontainers-compatible API connection. Integration tests do not silently skip when an engine is unavailable. They supply isolated database credentials dynamically, so DB_PASSWORD is not needed for tests.

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

No city, theatre, screen, seat, security, messaging, caching, gateway, booking, payment or Kubernetes functionality is included.

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
