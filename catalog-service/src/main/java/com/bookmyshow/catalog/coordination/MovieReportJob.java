package com.bookmyshow.catalog.coordination;

import com.bookmyshow.catalog.movie.MovieRepository;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "learning.movie-report.enabled", havingValue = "true")
public class MovieReportJob {
    public static final String LOCK_KEY = "lock:catalog:movie-count-report";
    private static final Logger log = LoggerFactory.getLogger(MovieReportJob.class);
    private final RedisLeaseLock locks;
    private final MovieRepository movies;
    private final Duration lease;
    public MovieReportJob(RedisLeaseLock locks, MovieRepository movies,
                          @Value("${learning.movie-report.lease:PT30S}") Duration lease) {
        if (lease.toMillis() < 1) throw new IllegalArgumentException("Report lease must be positive");
        this.locks = locks; this.movies = movies; this.lease = lease;
    }
    @Scheduled(fixedDelayString = "${learning.movie-report.interval-ms:60000}")
    public void report() {
        try {
            var acquired = locks.tryAcquire(LOCK_KEY, lease);
            if (acquired.isEmpty()) return;
            try {
                // Learning-only best-effort coordination. Duplicate reports after lease expiry are harmless.
                log.info("movieCountReport count={}", movies.count());
            } finally { locks.release(acquired.get()); }
        } catch (RuntimeException exception) {
            // In particular, never pretend to own the lock when Redis is unavailable.
            log.warn("movieCountReportSkipped failureType={}", exception.getClass().getSimpleName());
        }
    }
}
