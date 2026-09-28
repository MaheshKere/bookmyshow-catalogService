package com.bookmyshow.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("gateway.movie-rate-limit")
public record MovieRateLimitProperties(boolean enabled, int limit, Duration window) {
    public MovieRateLimitProperties {
        if (limit < 1 || window == null || window.toMillis() < 1)
            throw new IllegalArgumentException("Rate limit and window must be positive");
    }
}
