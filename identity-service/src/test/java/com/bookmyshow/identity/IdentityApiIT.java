package com.bookmyshow.identity;

import com.bookmyshow.identity.user.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IdentityApiIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @BeforeEach void clean() { users.deleteAll(); }

    private String body(String email) throws Exception {
        return mapper.writeValueAsString(Map.of("email", email, "password", "Password@123",
                "firstName", "Mahesh", "lastName", "Kere", "role", "ADMIN"));
    }
    private long register(String email) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body(email)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.createdAt").isNotEmpty()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
    private String login(String email) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email, "password", "Password@123"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900)).andExpect(header().doesNotExist("Set-Cookie")).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test void registrationNormalizesEmailAndPersistsBcryptNotClientRole() throws Exception {
        long id = register("  MAHESH@example.com  ");
        var user = users.findById(id).orElseThrow();
        assertThat(user.getEmail()).isEqualTo("mahesh@example.com");
        assertThat(user.getPasswordHash()).startsWith("$2a$12$");
        assertThat(encoder.matches("Password@123", user.getPasswordHash())).isTrue();
        assertThat(user.getRole()).isEqualTo(Role.USER);
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body("mahesh@example.com")))
                .andExpect(status().isConflict());
        assertThat(users.count()).isEqualTo(1);
    }

    @Test void realLoginJwtMeAndRoleAuthorizationAreStateless() throws Exception {
        long id = register("mahesh@example.com");
        String userToken = login("MAHESH@example.com");
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.email").value("mahesh@example.com")).andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(get("/api/v1/users/admin/status").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        // Privileged test-fixture SQL, not a public promotion API.
        jdbc.update("update users set role='ADMIN' where id=?", id);
        String adminToken = login("mahesh@example.com");
        mvc.perform(get("/api/v1/users/admin/status").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test void wrongUnknownAndInactiveLoginsUseSameGenericFailure() throws Exception {
        long id = register("mahesh@example.com");
        for (String email : List.of("mahesh@example.com", "unknown@example.com")) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("email", email, "password", "wrong"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Invalid credentials or inactive account."));
        }
        String previouslyIssued = login("mahesh@example.com");
        jdbc.update("update users set active=false where id=?", id);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"mahesh@example.com\",\"password\":\"Password@123\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + previouslyIssued))
                .andExpect(status().isUnauthorized());
    }

    @Test void invalidInputIs400AndDoesNotInsert() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", "a@b.com", "password", String.valueOf((char) 0x00E9).repeat(40),
                        "firstName", "A", "lastName", "B")))).andExpect(status().isBadRequest());
        assertThat(users.count()).isZero();
    }

    @Test void flywayAndDatabaseConstraintsProtectEmailAndRole() throws Exception {
        long id = register("mahesh@example.com");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='1' and success", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("insert into users(email,password_hash,first_name,last_name,role,active,created_at,updated_at) select email,password_hash,first_name,last_name,role,active,created_at,updated_at from users where id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update users set email='MAHESH@example.com' where id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update users set role='SUPERUSER' where id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void concurrentDuplicateRegistrationCreatesExactlyOneUser() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<Integer> register = () -> {
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                    .content(body("race@example.com"))).andReturn().getResponse().getStatus();
        };
        try {
            var a = executor.submit(register);
            var b = executor.submit(register);
            start.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
            assertThat(users.count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
