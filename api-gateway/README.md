# API Gateway

Reactive Spring Cloud Gateway Server WebFlux, Spring Boot 3.5.16, Spring Cloud BOM 2025.0.3. HTTP port 8080; no database.

See the [root README](../README.md) for the architecture, JWT trust model, complete access policy, environment variables, startup and request examples.

GatewayRoutes defines four routes: Identity auth/users; Booking Show-seat inventory with priority -10; Booking reservations/bookings; Catalog movies/theaters/screens/shows. The specific seat route takes precedence over Catalog's broader Show path. Paths and query strings are forwarded unchanged. Downstream URIs are configurable and default to local ports 8083, 8081, and 8082.

SecurityConfiguration uses SecurityWebFilterChain, reactive JWT validation, and no session/security-context persistence. Only the public RSA key is needed. IdentityHeaderFilter removes spoofable identity/role headers; downstream services validate the original bearer token independently. The Gateway never synthesizes trusted identity headers and requires no TokenRelay/OAuth2 client flow.

GatewaySecurityRoutingTest runs an actual HTTP Gateway with three ephemeral local HTTP backends. It verifies public/protected routes, 401/403, ADMIN/USER policy, expired/wrong-issuer/wrong-audience tokens, specific Show-seat routing, query preservation, bearer forwarding, and header removal. Run mvn -pl api-gateway test from the repository root.
