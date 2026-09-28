package com.bookmyshow.catalog.coordination;

import com.bookmyshow.catalog.movie.MovieRepository;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MovieReportJobTest {
    final RedisLeaseLock locks = mock(RedisLeaseLock.class);
    final MovieRepository movies = mock(MovieRepository.class);
    final Duration ttl = Duration.ofSeconds(30);
    final MovieReportJob job = new MovieReportJob(locks, movies, ttl);
    @Test void unavailableRedisSkipsWork() {
        when(locks.tryAcquire(MovieReportJob.LOCK_KEY, ttl)).thenThrow(new IllegalStateException("offline"));
        assertThatCode(job::report).doesNotThrowAnyException();
        verifyNoInteractions(movies);
    }
    @Test void heldLockSkipsWork() {
        when(locks.tryAcquire(MovieReportJob.LOCK_KEY, ttl)).thenReturn(Optional.empty());
        job.report();
        verifyNoInteractions(movies);
    }
    @Test void reportFailureStillReleasesOwnedLease() {
        var lease = new RedisLeaseLock.Lease(MovieReportJob.LOCK_KEY, "owner");
        when(locks.tryAcquire(MovieReportJob.LOCK_KEY, ttl)).thenReturn(Optional.of(lease));
        when(movies.count()).thenThrow(new IllegalStateException("database unavailable"));
        job.report();
        verify(locks).release(lease);
    }
}
