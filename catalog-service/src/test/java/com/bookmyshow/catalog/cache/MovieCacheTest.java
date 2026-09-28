package com.bookmyshow.catalog.cache;

import com.bookmyshow.catalog.movie.*;
import com.bookmyshow.catalog.movie.dto.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MovieCacheTest {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") final ValueOperations<String, String> values = mock(ValueOperations.class);
    final MovieCache cache = new MovieCache(redis, JsonMapper.builder().findAndAddModules().build(), true, Duration.ofMinutes(5));
    @Test void connectionFailureFallsBackToDatabaseAndDoesNotFailResponse() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("offline"));
        var repository = mock(MovieRepository.class);
        var movie = new Movie("Arrival", "Description", "English", "Drama", 116, LocalDate.now(), true);
        ReflectionTestUtils.setField(movie, "id", 1L);
        when(repository.findById(1L)).thenReturn(Optional.of(movie));
        var service = new MovieService(repository, cache, mock(ApplicationEventPublisher.class));
        assertThat(service.getById(1L).title()).isEqualTo("Arrival");
        verify(repository).findById(1L);
    }
    @Test void malformedCachedJsonIsEvictedAndTreatedAsMiss() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("movie:1")).thenReturn("{bad");
        assertThat(cache.get(1L)).isEmpty();
        verify(redis).delete("movie:1");
    }
    @Test void disabledCacheMakesNoRedisCalls() {
        var disabled = new MovieCache(redis, JsonMapper.builder().build(), false, Duration.ofSeconds(1));
        assertThat(disabled.get(1L)).isEmpty();
        disabled.evict(1L);
        verifyNoInteractions(redis);
    }
    @Test void failedInvalidationDoesNotTurnCommittedWriteIntoError() {
        doThrow(new RedisConnectionFailureException("offline")).when(redis).delete("movie:1");
        assertThatCode(() -> cache.evict(1L)).doesNotThrowAnyException();
    }
    @Test void rejectsNonpositiveTtl() {
        assertThatThrownBy(() -> new MovieCache(redis, JsonMapper.builder().build(), true, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
