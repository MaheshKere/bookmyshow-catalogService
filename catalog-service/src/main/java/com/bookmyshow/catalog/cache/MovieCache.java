package com.bookmyshow.catalog.cache;

import com.bookmyshow.catalog.movie.dto.MovieResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Optional;

@Component
public class MovieCache {
    private static final Logger log = LoggerFactory.getLogger(MovieCache.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final Duration ttl;
    public MovieCache(StringRedisTemplate redis, ObjectMapper mapper,
                      @Value("${catalog.movie-cache.enabled:true}") boolean enabled,
                      @Value("${catalog.movie-cache.ttl:PT5M}") Duration ttl) {
        if (ttl.toMillis() < 1) throw new IllegalArgumentException("Movie cache TTL must be positive");
        this.redis = redis; this.mapper = mapper; this.enabled = enabled; this.ttl = ttl;
    }
    public static String key(Long id) { return "movie:" + id; }
    public Optional<MovieResponse> get(Long id) {
        if (!enabled) return Optional.empty();
        try {
            String json = redis.opsForValue().get(key(id));
            if (json == null) return Optional.empty();
            var movie = mapper.readValue(json, MovieResponse.class);
            if (movie == null || !id.equals(movie.id())) {
                evict(id);
                return Optional.empty();
            }
            return Optional.of(movie);
        } catch (JsonProcessingException exception) {
            log.warn("movieCacheInvalid id={}", id);
            evict(id);
        } catch (DataAccessException exception) {
            log.warn("movieCacheUnavailable operation=get id={}", id);
        }
        return Optional.empty();
    }
    public void put(MovieResponse movie) {
        if (!enabled) return;
        try {
            redis.opsForValue().set(key(movie.id()), mapper.writeValueAsString(movie), ttl);
        } catch (JsonProcessingException | DataAccessException exception) {
            log.warn("movieCacheUnavailable operation=put id={}", movie.id());
        }
    }
    public void evict(Long id) {
        if (!enabled) return;
        try { redis.delete(key(id)); }
        catch (DataAccessException exception) { log.warn("movieCacheUnavailable operation=evict id={}", id); }
    }
}
