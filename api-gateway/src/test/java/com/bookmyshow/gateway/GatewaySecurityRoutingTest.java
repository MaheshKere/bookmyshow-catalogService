package com.bookmyshow.gateway;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import java.time.Instant;
import java.util.List;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySecurityRoutingTest {
    static final DisposableServer identity = backend("identity");
    static final DisposableServer catalog = backend("catalog");
    static final DisposableServer booking = backend("booking");

    static DisposableServer backend(String name) {
        return HttpServer.create().host("127.0.0.1").port(0).handle((request, response) ->
                request.receive().aggregate().asString().defaultIfEmpty("").flatMap(body -> response
                        .header("X-Backend", name)
                        .header("X-Received-Path", request.uri())
                        .header("X-Received-Authorization", request.requestHeaders().get("Authorization", "absent"))
                        .header("X-Received-User", request.requestHeaders().get("X-User-Id", "absent"))
                        .header("X-Received-Role", request.requestHeaders().get("X-Role", "absent"))
                        .sendString(Mono.just(name)).then())).bindNow();
    }

    @DynamicPropertySource static void downstream(DynamicPropertyRegistry registry) {
        registry.add("downstream.identity-url", () -> "http://127.0.0.1:" + identity.port());
        registry.add("downstream.catalog-url", () -> "http://127.0.0.1:" + catalog.port());
        registry.add("downstream.booking-url", () -> "http://127.0.0.1:" + booking.port());
    }
    @Autowired WebTestClient client;
    @AfterAll static void stop() { identity.disposeNow(); catalog.disposeNow(); booking.disposeNow(); }

    @Test void publicAuthRoutesReachIdentityWithoutRewriting() {
        for (String path : List.of("/api/v1/auth/register", "/api/v1/auth/login")) {
            client.post().uri(path).bodyValue("{}").exchange().expectStatus().isOk()
                    .expectHeader().valueEquals("X-Backend", "identity").expectHeader().valueEquals("X-Received-Path", path);
        }
    }
    @Test void publicCatalogRoutesPreservePathAndQuery() {
        for (String path : List.of("/api/v1/movies?page=1", "/api/v1/theaters/1", "/api/v1/screens/1", "/api/v1/shows/100")) {
            client.get().uri(path).exchange().expectStatus().isOk()
                    .expectHeader().valueEquals("X-Backend", "catalog").expectHeader().valueEquals("X-Received-Path", path);
        }
    }
    @Test void specificShowSeatRouteGoesToBookingBeforeCatalogShowRoute() {
        client.get().uri("/api/v1/shows/100/seats?status=AVAILABLE").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Backend", "booking");
        client.post().uri("/api/v1/shows/100/seats").headers(headers -> headers.setBearerAuth(TestTokens.token("ADMIN")))
                .bodyValue("{}").exchange().expectStatus().isOk().expectHeader().valueEquals("X-Backend", "booking");
    }
    @Test void missingInvalidAndExpiredTokensAre401() {
        client.post().uri("/api/v1/reservations").bodyValue("{}").exchange().expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith("application/problem+json");
        client.post().uri("/api/v1/bookings").headers(headers -> headers.setBearerAuth("invalid"))
                .bodyValue("{}").exchange().expectStatus().isUnauthorized();
        var expired = TestTokens.token("1", "USER", "bookmyshow-identity", List.of("bookmyshow-api"), Instant.now().minusSeconds(5));
        client.get().uri("/api/v1/users/me").headers(headers -> headers.setBearerAuth(expired))
                .exchange().expectStatus().isUnauthorized();
    }
    @Test void userCanBookButCannotAdministerCatalogOrInitializeSeats() {
        String token = TestTokens.token("USER");
        for (String path : List.of("/api/v1/reservations", "/api/v1/bookings")) {
            client.post().uri(path).headers(headers -> headers.setBearerAuth(token))
                    .bodyValue("{}").exchange().expectStatus().isOk().expectHeader().valueEquals("X-Backend", "booking");
        }
        for (String path : List.of("/api/v1/movies", "/api/v1/shows/100/seats")) {
            client.post().uri(path).headers(headers -> headers.setBearerAuth(token))
                    .bodyValue("{}").exchange().expectStatus().isForbidden();
        }
    }
    @Test void adminCanWriteCatalogAndAccessAdminEndpoint() {
        String token = TestTokens.token("ADMIN");
        client.post().uri("/api/v1/movies").headers(headers -> headers.setBearerAuth(token)).bodyValue("{}")
                .exchange().expectStatus().isOk().expectHeader().valueEquals("X-Backend", "catalog");
        client.get().uri("/api/v1/users/admin/status").headers(headers -> headers.setBearerAuth(TestTokens.token("USER")))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/api/v1/users/admin/status").headers(headers -> headers.setBearerAuth(token))
                .exchange().expectStatus().isOk().expectHeader().valueEquals("X-Backend", "identity");
    }
    @Test void forwardsOriginalBearerAndStripsSpoofedIdentityHeaders() {
        String token = TestTokens.token("USER");
        client.get().uri("/api/v1/bookings/ref").headers(headers -> {
            headers.setBearerAuth(token);
            headers.set("X-User-Id", "999");
            headers.set("X-Role", "ADMIN");
        }).exchange().expectStatus().isOk().expectHeader().valueEquals("X-Received-Authorization", "Bearer " + token)
                .expectHeader().valueEquals("X-Received-User", "absent").expectHeader().valueEquals("X-Received-Role", "absent");
        client.get().uri("/api/v1/users/me").header("X-User-Id", "1").header("X-Role", "ADMIN")
                .exchange().expectStatus().isUnauthorized();
    }
    @Test void wrongIssuerAndAudienceAreRejectedBeforeRouting() {
        for (String token : List.of(
                TestTokens.token("1", "USER", "other", List.of("bookmyshow-api"), Instant.now().plusSeconds(30)),
                TestTokens.token("1", "USER", "bookmyshow-identity", List.of("other"), Instant.now().plusSeconds(30)))) {
            client.get().uri("/api/v1/bookings/ref").headers(headers -> headers.setBearerAuth(token))
                    .exchange().expectStatus().isUnauthorized();
        }
    }
}
