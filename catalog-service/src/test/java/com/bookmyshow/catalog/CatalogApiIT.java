package com.bookmyshow.catalog;

import com.bookmyshow.catalog.movie.*;
import com.bookmyshow.catalog.theater.*;
import com.bookmyshow.catalog.screen.*;
import com.bookmyshow.catalog.show.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"})
@AutoConfigureMockMvc
@Testcontainers
class CatalogApiIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MovieRepository movies;
    @Autowired TheaterRepository theaters;
    @Autowired ScreenRepository screens;
    @Autowired ShowRepository shows;
    @Autowired ShowService showService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach void clean() {
        shows.deleteAll();
        screens.deleteAll();
        theaters.deleteAll();
        movies.deleteAll();
    }

    private record Fixture(Movie movie, Theater theater, Screen screen) {}
    private Fixture fixture(String city) {
        var movie = movies.save(new Movie("Movie " + city, "Description", "English", "Drama",
                120, LocalDate.of(2026, 1, 1), true));
        var theater = theaters.save(new Theater("Cinema " + city, city, "Main Road", true));
        var screen = screens.save(new Screen("Screen " + city, 100, true, theater));
        return new Fixture(movie, theater, screen);
    }
    private Show show(Fixture fixture, String start) {
        var instant = Instant.parse(start);
        return shows.save(new Show(fixture.movie(), fixture.screen(), instant, instant.plusSeconds(7200), true));
    }
    private String showBody(long movieId, long screenId, long theaterId, String start, String end) throws Exception {
        return mapper.writeValueAsString(Map.of("movieId", movieId, "screenId", screenId,
                "theaterId", theaterId, "startTime", start, "endTime", end, "active", true));
    }
    private long create(String path, Object body) throws Exception {
        var result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty()).andReturn();
        long id = mapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        String resource = path.endsWith("/screens") ? "/api/v1/screens/" : path + "/";
        assertThat(result.getResponse().getHeader("Location")).endsWith(resource + id);
        return id;
    }

    @Test void createsAndReadsRelationshipsThroughHttpWithoutOsiv() throws Exception {
        long theaterId = create("/api/v1/theaters",
                Map.of("name", "Cinema", "city", "Pune", "address", "Road", "active", true));
        long screenId = create("/api/v1/theaters/" + theaterId + "/screens",
                Map.of("name", "Screen 1", "totalSeats", 150, "active", true));
        long secondScreen = create("/api/v1/theaters/" + theaterId + "/screens",
                Map.of("name", "Screen 2", "totalSeats", 90, "active", false));
        var movie = movies.save(new Movie("Arrival", "Description", "English", "Drama",
                116, LocalDate.of(2016, 11, 11), true));
        long showId = create("/api/v1/shows", Map.of("movieId", movie.getId(), "screenId", screenId,
                "theaterId", theaterId, "startTime", "2026-10-01T10:00:00Z",
                "endTime", "2026-10-01T12:00:00Z", "active", true));

        mvc.perform(get("/api/v1/theaters/{id}", theaterId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("Pune"));
        mvc.perform(get("/api/v1/screens/{id}", screenId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.theaterId").value(theaterId))
                .andExpect(jsonPath("$.totalSeats").value(150));
        mvc.perform(get("/api/v1/theaters/{id}/screens?page=1&size=1", theaterId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(secondScreen))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/v1/theaters?page=0&size=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(theaterId));
        mvc.perform(get("/api/v1/shows/{id}", showId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.movieTitle").value("Arrival"))
                .andExpect(jsonPath("$.screenName").value("Screen 1"))
                .andExpect(jsonPath("$.theaterName").value("Cinema"))
                .andExpect(jsonPath("$.startTime").value("2026-10-01T10:00:00Z"));
        assertThat(jdbc.queryForObject("select theater_id from screens where id = ?", Long.class, screenId))
                .isEqualTo(theaterId);
        assertThat(jdbc.queryForObject("select movie_id from shows where id = ?", Long.class, showId))
                .isEqualTo(movie.getId());
        assertThat(jdbc.queryForObject("select screen_id from shows where id = ?", Long.class, showId))
                .isEqualTo(screenId);
    }

    @Test void mappingsAreLazyAndChildrenOwnPersistedForeignKeys() {
        var fixture = fixture("Pune");
        var saved = show(fixture, "2026-10-01T10:00:00Z");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            em.clear();
            // EntityManager.find bypasses the repository's intentional read EntityGraph.
            var loaded = em.find(Show.class, saved.getId());
            assertThat(Hibernate.isInitialized(loaded.getMovie())).isFalse();
            assertThat(Hibernate.isInitialized(loaded.getScreen())).isFalse();
            assertThat(loaded.getMovie().getTitle()).isEqualTo(fixture.movie().getTitle());
            assertThat(loaded.getScreen().getName()).isEqualTo(fixture.screen().getName());
            assertThat(Hibernate.isInitialized(loaded.getScreen().getTheater())).isFalse();
            assertThat(loaded.getScreen().getTheater().getCity()).isEqualTo("Pune");
        });
        // No delete cascade from a Show to its independently managed parents.
        shows.deleteById(saved.getId());
        assertThat(movies.existsById(fixture.movie().getId())).isTrue();
        assertThat(screens.existsById(fixture.screen().getId())).isTrue();
        assertThat(theaters.existsById(fixture.theater().getId())).isTrue();
    }

    @Test void searchesByEachFilterAndCombinedFiltersWithUtcBoundaries() throws Exception {
        var pune = fixture("Pune");
        var mumbai = fixture("Mumbai");
        show(pune, "2026-09-30T23:59:59Z");
        var first = show(pune, "2026-10-01T00:00:00Z");
        var second = show(pune, "2026-10-01T23:59:59Z");
        show(pune, "2026-10-02T00:00:00Z");
        show(mumbai, "2026-10-01T12:00:00Z");
        mvc.perform(get("/api/v1/shows")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5));
        mvc.perform(get("/api/v1/shows").param("movieId", pune.movie().getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4));
        mvc.perform(get("/api/v1/shows").param("theaterId", mumbai.theater().getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/shows?city=pUnE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4));
        mvc.perform(get("/api/v1/shows?date=2026-10-01")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/v1/shows").param("movieId", pune.movie().getId().toString())
                .param("theaterId", pune.theater().getId().toString()).param("city", "pune")
                .param("date", "2026-10-01").param("size", "1").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(second.getId()))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/v1/shows?city=pune&date=2026-10-01&size=1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(first.getId()));
        mvc.perform(get("/api/v1/shows?movieId=999999")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test void graphPreventsNPlusOneForDistinctMoviesScreensAndTheaters() {
        for (String city : new String[]{"Pune", "Mumbai", "Delhi", "Chennai"}) {
            show(fixture(city), "2026-10-01T10:00:00Z");
        }
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        // No test transaction: each service call gets a fresh persistence context.
        var page = showService.search(null, null, null, LocalDate.of(2026, 10, 1),
                PageRequest.of(0, 3, Sort.by("startTime", "id")));
        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).allSatisfy(response -> {
            assertThat(response.movieTitle()).startsWith("Movie ");
            assertThat(response.screenName()).startsWith("Screen ");
            assertThat(response.theaterName()).startsWith("Cinema ");
        });
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2); // content join + count
        statistics.clear();
        assertThat(showService.getById(page.getContent().get(0).id()).city()).isNotBlank();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test void rejectsMissingReferencesWrongTheaterAndInvalidTimesWithoutInserting() throws Exception {
        var fixture = fixture("Pune");
        var other = theaters.save(new Theater("Other", "Mumbai", "Road", true));
        long movieId = fixture.movie().getId(), screenId = fixture.screen().getId(),
                theaterId = fixture.theater().getId();
        for (long[] ids : new long[][]{{999999, screenId, theaterId},
                {movieId, 999999, theaterId}, {movieId, screenId, 999999}}) {
            mvc.perform(post("/api/v1/shows").contentType(MediaType.APPLICATION_JSON)
                    .content(showBody(ids[0], ids[1], ids[2], "2026-10-01T10:00:00Z", "2026-10-01T12:00:00Z")))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/v1/shows").contentType(MediaType.APPLICATION_JSON)
                .content(showBody(movieId, screenId, other.getId(), "2026-10-01T10:00:00Z", "2026-10-01T12:00:00Z")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/shows").contentType(MediaType.APPLICATION_JSON)
                .content(showBody(movieId, screenId, theaterId, "2026-10-01T10:00:00Z", "2026-10-01T10:00:00Z")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/theaters/999999/screens").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Screen\",\"totalSeats\":100,\"active\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/theaters/999999/screens")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/shows/999999")).andExpect(status().isNotFound());
        assertThat(shows.count()).isZero();
    }

    @Test void flywayAndDatabaseConstraintsProtectAllRelationships() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version in ('1', '2') and success",
                Integer.class)).isEqualTo(2);
        var fixture = fixture("Pune");
        var saved = show(fixture, "2026-10-01T10:00:00Z");
        assertThatThrownBy(() -> jdbc.update("update screens set theater_id = 999999 where id = ?", fixture.screen().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update shows set movie_id = 999999 where id = ?", saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update shows set screen_id = 999999 where id = ?", saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update screens set total_seats = 0 where id = ?", fixture.screen().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update shows set end_time = start_time where id = ?", saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update screens set theater_id = null where id = ?", fixture.screen().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update shows set movie_id = null where id = ?", saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update shows set screen_id = null where id = ?", saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from theaters where id = ?", fixture.theater().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from screens where id = ?", fixture.screen().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        mvc.perform(delete("/api/v1/movies/{id}", fixture.movie().getId())).andExpect(status().isConflict());
        assertThat(movies.existsById(fixture.movie().getId())).isTrue();
    }
}
