package com.bookmyshow.catalog.cache;

import com.bookmyshow.catalog.coordination.RedisLeaseLock;
import com.bookmyshow.catalog.exception.ResourceNotFoundException;
import com.bookmyshow.catalog.movie.*;
import com.bookmyshow.catalog.movie.dto.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"catalog.movie-cache.enabled=true", "catalog.movie-cache.ttl=PT2S"})
@Testcontainers
class CatalogRedisIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container static final GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
    }
    @Autowired MovieService movies;
    @Autowired MovieCache cache;
    @Autowired RedisLeaseLock locks;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean MovieRepository repository;
    @MockitoSpyBean StringRedisTemplate redis;
    @BeforeEach void clean() {
        reset(redis);
        redis.delete(redis.keys("*")); // This disposable container belongs only to this test class.
        repository.deleteAll();
        clearInvocations(repository);
    }
    MovieRequest request(String title) {
        return new MovieRequest(title, "Description", "English", "Drama", 120, LocalDate.of(2026, 1, 1), true);
    }
    MovieResponse create() { return movies.create(request("Original")); }
    @Test void missLoadsDatabasePopulatesCacheAndNextReadIsHit() {
        var movie = create();
        assertThat(redis.hasKey(MovieCache.key(movie.id()))).isFalse();
        clearInvocations(repository);
        assertThat(movies.getById(movie.id()).title()).isEqualTo("Original");
        assertThat(redis.hasKey(MovieCache.key(movie.id()))).isTrue();
        assertThat(movies.getById(movie.id()).title()).isEqualTo("Original");
        verify(repository, times(1)).findById(movie.id());
    }
    @Test void configuredTtlExpiresAndNextReadReloadsDatabase() {
        var movie = create();
        movies.getById(movie.id());
        assertThat(redis.getExpire(MovieCache.key(movie.id()), TimeUnit.MILLISECONDS)).isBetween(1L, 2000L);
        await().atMost(Duration.ofSeconds(5)).until(() -> !Boolean.TRUE.equals(redis.hasKey(MovieCache.key(movie.id()))));
        clearInvocations(repository);
        movies.getById(movie.id());
        verify(repository).findById(movie.id());
        assertThat(redis.hasKey(MovieCache.key(movie.id()))).isTrue();
    }
    @Test void updateInvalidatesOnlyAfterCommitAndReloadsFreshValue() {
        var movie = create();
        movies.getById(movie.id());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            movies.update(movie.id(), request("Updated"));
            assertThat(cache.get(movie.id()).orElseThrow().title()).isEqualTo("Original");
        });
        assertThat(redis.hasKey(MovieCache.key(movie.id()))).isFalse();
        assertThat(movies.getById(movie.id()).title()).isEqualTo("Updated");
    }
    @Test void deleteInvalidatesAndMissingMovieIsNotCached() {
        var movie = create();
        movies.getById(movie.id());
        movies.delete(movie.id());
        assertThat(redis.hasKey(MovieCache.key(movie.id()))).isFalse();
        assertThatThrownBy(() -> movies.getById(movie.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThat(redis.hasKey(MovieCache.key(movie.id()))).isFalse();
    }
    @Test void rollbackDoesNotEvictOrPopulateCacheFromUncommittedState() {
        var movie = create();
        movies.getById(movie.id());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            movies.update(movie.id(), request("Uncommitted"));
            assertThat(movies.getById(movie.id()).title()).isEqualTo("Uncommitted");
            assertThat(cache.get(movie.id()).orElseThrow().title()).isEqualTo("Original");
            status.setRollbackOnly();
        });
        assertThat(cache.get(movie.id()).orElseThrow().title()).isEqualTo("Original");
        assertThat(repository.findById(movie.id()).orElseThrow().getTitle()).isEqualTo("Original");
    }
    @Test void createInvalidationAlsoRunsAfterCommit() {
        var movie = new TransactionTemplate(transactions).execute(status -> {
            var created = create();
            cache.put(created); // Simulate a preexisting key for the newly allocated ID.
            assertThat(redis.hasKey(MovieCache.key(created.id()))).isTrue();
            return created;
        });
        assertThat(redis.hasKey(MovieCache.key(movie.id()))).isFalse();
    }
    @Test void failedRedisReadsAndInvalidationPreserveDatabaseBehavior() {
        var movie = create();
        doThrow(new RedisConnectionFailureException("offline")).when(redis).opsForValue();
        doThrow(new RedisConnectionFailureException("offline")).when(redis).delete(anyString());
        assertThat(movies.getById(movie.id()).title()).isEqualTo("Original");
        assertThat(movies.update(movie.id(), request("Committed despite outage")).title()).isEqualTo("Committed despite outage");
        assertThat(repository.findById(movie.id()).orElseThrow().getTitle()).isEqualTo("Committed despite outage");
        movies.delete(movie.id());
        assertThat(repository.existsById(movie.id())).isFalse();
    }
    @Test void onlyOwnerCanUnlockAndContenderCannotAcquire() {
        var owner = locks.tryAcquire("lock:test", Duration.ofSeconds(30)).orElseThrow();
        assertThat(locks.tryAcquire("lock:test", Duration.ofSeconds(30))).isEmpty();
        assertThat(locks.release(new RedisLeaseLock.Lease("lock:test", "wrong-owner"))).isFalse();
        assertThat(redis.opsForValue().get("lock:test")).isEqualTo(owner.token());
        assertThat(locks.release(owner)).isTrue();
        assertThat(locks.tryAcquire("lock:test", Duration.ofSeconds(30))).isPresent();
    }
    @Test void expiredOwnerCannotUnlockSuccessorAfterCrash() {
        var abandoned = locks.tryAcquire("lock:test", Duration.ofMillis(200)).orElseThrow();
        await().atMost(Duration.ofSeconds(3)).until(() -> !Boolean.TRUE.equals(redis.hasKey("lock:test")));
        var successor = locks.tryAcquire("lock:test", Duration.ofSeconds(30)).orElseThrow();
        assertThat(successor.token()).isNotEqualTo(abandoned.token());
        assertThat(locks.release(abandoned)).isFalse();
        assertThat(redis.opsForValue().get("lock:test")).isEqualTo(successor.token());
        assertThat(locks.release(successor)).isTrue();
    }
    @Test void concurrentInstancesHaveOnlyOneLeaseOwner() throws Exception {
        var other = new RedisLeaseLock(redis);
        var executor = Executors.newFixedThreadPool(8);
        try {
            var tasks = new ArrayList<Future<Optional<RedisLeaseLock.Lease>>>();
            for (int i = 0; i < 8; i++) {
                var instance = i % 2 == 0 ? locks : other;
                tasks.add(executor.submit(() -> instance.tryAcquire("lock:concurrent", Duration.ofSeconds(30))));
            }
            int owners = 0;
            for (var task : tasks) if (task.get(10, TimeUnit.SECONDS).isPresent()) owners++;
            assertThat(owners).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }
}
