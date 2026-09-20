package com.bookmyshow.catalog.movie;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MovieApiIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired org.springframework.web.context.WebApplicationContext webContext;

    @BeforeEach void authenticateRequests() {
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .defaultRequest(get("/").header("Authorization", "Bearer " + com.bookmyshow.catalog.TestTokens.token("ADMIN")))
                .build();
    }
    @Autowired ObjectMapper mapper;
    @Autowired MovieRepository repository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() { repository.deleteAll(); }

    @Test void crudPersistsAllFieldsAndAuditTimestamps() throws Exception {
        var result = mvc.perform(post("/api/v1/movies").contentType(MediaType.APPLICATION_JSON)
                .content(body("Arrival", true))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty()).andReturn();
        var created = mapper.readTree(result.getResponse().getContentAsString());
        long id = created.get("id").asLong();
        assertThat(result.getResponse().getHeader("Location")).endsWith("/api/v1/movies/" + id);
        var stored = repository.findById(id).orElseThrow();
        assertThat(stored.getTitle()).isEqualTo("Arrival");
        assertThat(stored.getDescription()).isEqualTo("A movie description");
        assertThat(stored.getLanguage()).isEqualTo("English");
        assertThat(stored.getGenre()).isEqualTo("Science Fiction");
        assertThat(stored.getDurationMinutes()).isEqualTo(116);
        assertThat(stored.getReleaseDate()).hasToString("2016-11-11");
        assertThat(stored.isActive()).isTrue();
        var createdAt = stored.getCreatedAt();
        mvc.perform(get("/api/v1/movies/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Arrival"));
        mvc.perform(put("/api/v1/movies/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(body("Updated", false))).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated"))
                .andExpect(jsonPath("$.active").value(false));
        var updated = repository.findById(id).orElseThrow();
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(stored.getUpdatedAt());
        mvc.perform(delete("/api/v1/movies/{id}", id)).andExpect(status().isNoContent());
        assertThat(repository.existsById(id)).isFalse();
        mvc.perform(get("/api/v1/movies/{id}", id)).andExpect(status().isNotFound());
    }

    @Test void paginationUsesStableOrderAndCorrectTotals() throws Exception {
        mvc.perform(post("/api/v1/movies").contentType(MediaType.APPLICATION_JSON).content(body("First", true)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/movies").contentType(MediaType.APPLICATION_JSON).content(body("Second", true)))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/movies?page=1&size=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Second"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test void invalidRequestDoesNotInsertAndMissingUpdatesReturn404() throws Exception {
        mvc.perform(post("/api/v1/movies").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
        mvc.perform(put("/api/v1/movies/999999").contentType(MediaType.APPLICATION_JSON).content(body("Missing", true)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/movies/999999")).andExpect(status().isNotFound());
    }

    @Test void flywayRanAndDatabaseEnforcesPositiveDuration() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO movies (title, description, language, genre, duration_minutes, release_date, active, created_at, updated_at) VALUES ('Invalid', 'Description', 'English', 'Drama', 0, CURRENT_DATE, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private String body(String title, boolean active) throws Exception {
        return mapper.writeValueAsString(Map.of("title", title, "description", "A movie description",
                "language", "English", "genre", "Science Fiction", "durationMinutes", 116,
                "releaseDate", "2016-11-11", "active", active));
    }
}
