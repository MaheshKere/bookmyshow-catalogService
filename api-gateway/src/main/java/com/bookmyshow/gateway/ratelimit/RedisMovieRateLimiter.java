package com.bookmyshow.gateway.ratelimit;

import org.slf4j.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.List;

@Component
@EnableConfigurationProperties(MovieRateLimitProperties.class)
public class RedisMovieRateLimiter {
    // Fixed window anchored at the first accepted request, measured by Redis TTL, not JVM clocks.
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> ACQUIRE = new DefaultRedisScript<>("""
            local limit = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            if count == 0 then
                redis.call('SET', KEYS[1], '1', 'PX', window)
                return {1, limit - 1, window}
            end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then
                redis.call('PEXPIRE', KEYS[1], window)
                ttl = window
            end
            if count >= limit then return {0, 0, ttl} end
            redis.call('INCR', KEYS[1])
            return {1, limit - count - 1, ttl}
            """, List.class);
    private static final Logger log = LoggerFactory.getLogger(RedisMovieRateLimiter.class);
    private final ReactiveStringRedisTemplate redis;
    private final MovieRateLimitProperties properties;
    public record Decision(boolean allowed, long remaining, long retryAfterMillis) { }
    public RedisMovieRateLimiter(ReactiveStringRedisTemplate redis, MovieRateLimitProperties properties) {
        this.redis = redis; this.properties = properties;
    }
    public Mono<Decision> acquire(String key) {
        return redis.execute(ACQUIRE, List.of(key), List.of(Integer.toString(properties.limit()),
                        Long.toString(properties.window().toMillis())))
                .single().map(result -> new Decision(((Number) result.get(0)).longValue() == 1,
                        ((Number) result.get(1)).longValue(), ((Number) result.get(2)).longValue()))
                .onErrorResume(exception -> {
                    // Fail open only for this read-only learning boundary; no booking state depends on Redis.
                    log.warn("movieRateLimitUnavailable failureType={}", exception.getClass().getSimpleName());
                    return Mono.just(new Decision(true, -1, 0));
                });
    }
}
