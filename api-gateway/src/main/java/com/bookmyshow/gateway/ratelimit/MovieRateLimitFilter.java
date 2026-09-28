package com.bookmyshow.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.*;
import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

@Component
public class MovieRateLimitFilter implements GlobalFilter, Ordered {
    private final RedisMovieRateLimiter limiter;
    private final MovieRateLimitProperties properties;
    public MovieRateLimitFilter(RedisMovieRateLimiter limiter, MovieRateLimitProperties properties) {
        this.limiter = limiter; this.properties = properties;
    }
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var path = exchange.getRequest().getPath().value();
        if (!properties.enabled() || exchange.getRequest().getMethod() != HttpMethod.GET
                || !(path.equals("/api/v1/movies") || path.startsWith("/api/v1/movies/")))
            return chain.filter(exchange);
        return exchange.getPrincipal().filter(principal -> principal instanceof JwtAuthenticationToken)
                .map(principal -> "user:" + principal.getName())
                .defaultIfEmpty(remoteIdentity(exchange))
                .flatMap(identity -> limiter.acquire(key(identity)))
                .flatMap(decision -> {
                    if (decision.remaining() >= 0) {
                        exchange.getResponse().getHeaders().set("X-RateLimit-Limit", Integer.toString(properties.limit()));
                        exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", Long.toString(decision.remaining()));
                    }
                    if (decision.allowed()) return chain.filter(exchange);
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER,
                            Long.toString(Math.max(1, (decision.retryAfterMillis() + 999) / 1000)));
                    return exchange.getResponse().setComplete();
                });
    }
    private String remoteIdentity(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        // Do not trust arbitrary X-Forwarded-For or identity headers as rate-limit keys.
        return "ip:" + (remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress());
    }
    public static String key(String identity) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return "rate:gateway:movies:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    @Override public int getOrder() { return -2; }
}
