package com.bookmyshow.gateway;

import com.bookmyshow.gateway.ratelimit.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.*;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.movie-rate-limit.enabled=true", "gateway.movie-rate-limit.limit=3", "gateway.movie-rate-limit.window=PT10S"})
@Testcontainers
class GatewayRedisIT {
    @Container static final GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    static final AtomicInteger backendRequests = new AtomicInteger();
    static final DisposableServer backend = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
        backendRequests.incrementAndGet();
        return response.sendString(Mono.just("movie"));
    }).bindNow();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
        registry.add("downstream.catalog-url", () -> "http://127.0.0.1:" + backend.port());
    }
    @Autowired WebTestClient client;
    @Autowired RedisMovieRateLimiter limiter;
    @Autowired MovieRateLimitProperties properties;
    @MockitoSpyBean ReactiveStringRedisTemplate redis;
    @BeforeEach void clean() {
        reset(redis);
        redis.delete(redis.keys("*")).block(Duration.ofSeconds(5));
        backendRequests.set(0);
    }
    @AfterAll static void close() { backend.disposeNow(); }
    @Test void allowsLimitThenReturns429WithoutCallingBackend() {
        for (int i = 0; i < 3; i++) client.get().uri("/api/v1/movies/1").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Remaining", Integer.toString(2 - i));
        client.get().uri("/api/v1/movies/2").exchange().expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After").expectHeader().valueEquals("X-RateLimit-Remaining", "0");
        assertThat(backendRequests.get()).isEqualTo(3);
    }
    @Test void spoofedForwardingAndIdentityHeadersDoNotCreateNewBuckets() {
        for (int i = 0; i < 3; i++) client.get().uri("/api/v1/movies/1")
                .header("X-Forwarded-For", "192.0.2." + i).header("X-User-Id", Integer.toString(i))
                .exchange().expectStatus().isOk();
        client.get().uri("/api/v1/movies/1").header("X-Forwarded-For", "192.0.2.99")
                .header("X-User-Id", "999").exchange().expectStatus().isEqualTo(429);
    }
    @Test void verifiedJwtSubjectsHaveIndependentBucketsAndInvalidJwtStays401() {
        for (int i = 0; i < 3; i++) client.get().uri("/api/v1/movies/1")
                .headers(h -> h.setBearerAuth(TestTokens.token("USER"))).exchange().expectStatus().isOk();
        client.get().uri("/api/v1/movies/1").headers(h -> h.setBearerAuth(TestTokens.token("USER")))
                .exchange().expectStatus().isEqualTo(429);
        var second = TestTokens.token("2", "USER", "bookmyshow-identity", List.of("bookmyshow-api"), Instant.now().plusSeconds(60));
        client.get().uri("/api/v1/movies/1").headers(h -> h.setBearerAuth(second)).exchange().expectStatus().isOk();
        client.get().uri("/api/v1/movies/1").headers(h -> h.setBearerAuth("invalid")).exchange().expectStatus().isUnauthorized();
    }
    @Test void multipleInstancesShareAtomicCounterUnderConcurrentRequests() {
        var otherInstance = new RedisMovieRateLimiter(redis, properties);
        String key = "rate:test:" + UUID.randomUUID();
        var results = Flux.range(0, 20).flatMap(i -> (i % 2 == 0 ? limiter : otherInstance).acquire(key), 20)
                .collectList().block(Duration.ofSeconds(10));
        assertThat(results).filteredOn(RedisMovieRateLimiter.Decision::allowed).hasSize(3);
        assertThat(redis.opsForValue().get(key).block()).isEqualTo("3");
        assertThat(redis.getExpire(key).block()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(10));
    }
    @Test void expiredWindowStartsNewAllowanceWithoutExtendingOnRejection() {
        var shortWindow = new RedisMovieRateLimiter(redis, new MovieRateLimitProperties(true, 1, Duration.ofMillis(300)));
        String key = "rate:test:" + UUID.randomUUID();
        assertThat(shortWindow.acquire(key).block().allowed()).isTrue();
        assertThat(shortWindow.acquire(key).block().allowed()).isFalse();
        await().atMost(Duration.ofSeconds(3)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key).block()));
        assertThat(shortWindow.acquire(key).block().allowed()).isTrue();
    }
    @Test @SuppressWarnings("unchecked") void redisOutageFailsOpenForMovieReads() {
        doReturn(Flux.error(new RedisConnectionFailureException("offline"))).when(redis)
                .execute(any(RedisScript.class), anyList(), anyList());
        client.get().uri("/api/v1/movies/1").exchange().expectStatus().isOk()
                .expectHeader().doesNotExist("X-RateLimit-Remaining");
        assertThat(backendRequests.get()).isEqualTo(1);
    }
}
