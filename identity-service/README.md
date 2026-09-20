# Identity Service

Identity owns identity_db and runs on port 8083. It provides public POST /api/v1/auth/register and /api/v1/auth/login, authenticated GET /api/v1/users/me, and ADMIN-only GET /api/v1/users/admin/status.

See the [root README](../README.md) for key generation, Podman database setup, startup commands, register/login examples, the Spring Security flow, token lifetime/trust model, and test commands.

V1__create_users.sql adds users with an identity primary key, normalized unique email, BCrypt password_hash, first/last names, USER/ADMIN role, active flag, and audit timestamps. Required fields and role/hash/email checks are enforced by PostgreSQL. The unique email index supports login and duplicate detection; the PK supports JWT-subject lookups. There are no relationships to other databases.

Registration uses a service transaction and saveAndFlush for a new User. The unique constraint handles concurrent duplicates. UserDetails and /me reads use read-only transactions. Login delegates to AuthenticationManager and does not hold a database transaction while signing a JWT. Responses are record DTOs with no password hash. Password hashing uses BCrypt cost 12 and a 72-byte input bound.

JWT generation is in security/JwtTokenService and JwtSigningConfiguration. JWT validation is in JwtValidationConfiguration. SecurityConfiguration defines a stateless filter chain using Spring's built-in bearer-token mechanism; no custom JWT filter duplicates framework functionality. Missing key settings fail startup. No bind-parameter logging is enabled for Identity.

The dev profile selects [::1]:5434/identity_db with identity_user / identity_password. JWT_PUBLIC_KEY_LOCATION and JWT_PRIVATE_KEY_LOCATION remain required even in dev. Test-only PEM fixtures must never be used for a running environment.
