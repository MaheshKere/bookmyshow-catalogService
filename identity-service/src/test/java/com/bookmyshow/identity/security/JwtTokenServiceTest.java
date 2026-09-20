package com.bookmyshow.identity.security;

import com.bookmyshow.identity.TestTokens;
import com.bookmyshow.identity.user.User;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class JwtTokenServiceTest {
    JwtDecoder decoder;
    @BeforeEach void setUp() throws Exception {
        decoder = new JwtValidationConfiguration().jwtDecoder(new ClassPathResource("keys/test-public.pem"),
                "bookmyshow-identity", "bookmyshow-api");
    }
    @Test void generatesSignedMinimalClaimsWithConfiguredTtl() {
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var user = new User("a@b.com", "never-in-token", "A", "B");
        ReflectionTestUtils.setField(user, "id", 7L);
        var service = new JwtTokenService(TestTokens.encoder(), Clock.fixed(now, ZoneOffset.UTC),
                "bookmyshow-identity", "bookmyshow-api", Duration.ofMinutes(10));
        var response = service.generate(new UserPrincipal(user));
        var jwt = decoder.decode(response.accessToken());
        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plusSeconds(600));
        assertThat(jwt.getClaims()).doesNotContainKeys("password", "passwordHash", "email");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(600);
    }
    @Test void rejectsExpiredAndMalformedTokens() {
        assertThatThrownBy(() -> decoder.decode(TestTokens.token("1", "USER", "bookmyshow-identity",
                List.of("bookmyshow-api"), Instant.now().minusSeconds(1)))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode("not-a-token")).isInstanceOf(JwtException.class);
    }
    @Test void rejectsChangedSignature() {
        var parts = TestTokens.token("USER").split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        assertThatThrownBy(() -> decoder.decode(String.join(".", parts))).isInstanceOf(JwtException.class);
    }
    @Test void rejectsWrongIssuerAudienceRoleAndSubject() {
        for (String token : List.of(
                TestTokens.token("1", "USER", "other", List.of("bookmyshow-api"), Instant.now().plusSeconds(30)),
                TestTokens.token("1", "USER", "bookmyshow-identity", List.of("other"), Instant.now().plusSeconds(30)),
                TestTokens.token("1", "SUPERUSER", "bookmyshow-identity", List.of("bookmyshow-api"), Instant.now().plusSeconds(30)),
                TestTokens.token("invalid-id", "USER", "bookmyshow-identity", List.of("bookmyshow-api"), Instant.now().plusSeconds(30)))) {
            assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
        }
    }
    @Test void rejectsMissingExpirationAndUnsafeTtl() {
        var claims = JwtClaimsSet.builder().issuer("bookmyshow-identity").audience(List.of("bookmyshow-api"))
                .subject("1").claim("role", "USER").build();
        var token = TestTokens.encoder().encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> new JwtTokenService(TestTokens.encoder(), Clock.systemUTC(),
                "issuer", "audience", Duration.ofDays(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
