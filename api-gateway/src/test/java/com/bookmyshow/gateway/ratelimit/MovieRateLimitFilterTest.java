package com.bookmyshow.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MovieRateLimitFilterTest {
    final RedisMovieRateLimiter limiter = mock(RedisMovieRateLimiter.class);
    final GatewayFilterChain chain = mock(GatewayFilterChain.class);
    final MovieRateLimitFilter filter = new MovieRateLimitFilter(limiter, new MovieRateLimitProperties(true, 3, Duration.ofSeconds(10)));
    @Test void bookingTrafficBypassesMovieLimiter() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/bookings/ref"));
        when(chain.filter(exchange)).thenReturn(Mono.empty());
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verifyNoInteractions(limiter);
        verify(chain).filter(exchange);
    }
    @Test void downstreamFailureIsNotSwallowedOrRetriedByRateLimiter() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/movies/1"));
        when(limiter.acquire(anyString())).thenReturn(Mono.just(new RedisMovieRateLimiter.Decision(true, 2, 1000)));
        when(chain.filter(exchange)).thenReturn(Mono.error(new IllegalStateException("backend failed")));
        StepVerifier.create(filter.filter(exchange, chain)).expectErrorMessage("backend failed").verify();
        verify(chain, times(1)).filter(exchange);
    }
    @Test void keysAreStableBoundedAndSeparateUserFromIp() {
        assertThat(MovieRateLimitFilter.key("user:1")).isEqualTo(MovieRateLimitFilter.key("user:1"));
        assertThat(MovieRateLimitFilter.key("user:1")).isNotEqualTo(MovieRateLimitFilter.key("ip:1"));
        assertThat(MovieRateLimitFilter.key("user:1")).matches("rate:gateway:movies:[0-9a-f]{64}");
    }
    @Test void rejectsInvalidLimitsAndWindows() {
        assertThatThrownBy(() -> new MovieRateLimitProperties(true, 0, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MovieRateLimitProperties(true, 1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
