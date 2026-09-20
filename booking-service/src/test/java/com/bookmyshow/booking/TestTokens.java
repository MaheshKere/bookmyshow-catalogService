package com.bookmyshow.booking;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import java.time.Instant;
import java.util.List;

/** Test-only signing keys; never selected by a runtime profile. */
public final class TestTokens {
    private TestTokens() {}
    public static JwtEncoder encoder() {
        try (var pub = new ClassPathResource("keys/test-public.pem").getInputStream();
             var key = new ClassPathResource("keys/test-private.pem").getInputStream()) {
            var rsa = new RSAKey.Builder(RsaKeyConverters.x509().convert(pub))
                    .privateKey(RsaKeyConverters.pkcs8().convert(key)).build();
            return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsa)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    public static String token(String role) {
        return token("1", role, "bookmyshow-identity", List.of("bookmyshow-api"), Instant.now().plusSeconds(300));
    }
    public static String token(String subject, String role, String issuer, List<String> audience, Instant expiresAt) {
        var claims = JwtClaimsSet.builder().subject(subject).issuer(issuer).audience(audience)
                .issuedAt(Instant.now().minusSeconds(600)).expiresAt(expiresAt).claim("role", role).build();
        return encoder().encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }
}
