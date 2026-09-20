package com.bookmyshow.catalog.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import java.io.IOException;
import java.time.Duration;

@Configuration
public class JwtValidationConfiguration {
    @Bean
    JwtDecoder jwtDecoder(@Value("${security.jwt.public-key-location}") Resource publicKey,
                          @Value("${security.jwt.issuer:bookmyshow-identity}") String issuer,
                          @Value("${security.jwt.audience:bookmyshow-api}") String audience) throws IOException {
        NimbusJwtDecoder decoder;
        try (var input = publicKey.getInputStream()) {
            decoder = NimbusJwtDecoder.withPublicKey(RsaKeyConverters.x509().convert(input))
                    .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO), new JwtIssuerValidator(issuer),
                new JwtClaimValidator<java.util.List<String>>("aud", value -> value != null && value.contains(audience)),
                new JwtClaimValidator<String>("sub", value -> value != null && value.matches("[1-9][0-9]*")),
                new JwtClaimValidator<Object>("role", value -> "USER".equals(value) || "ADMIN".equals(value)),
                new JwtClaimValidator<java.time.Instant>("exp", java.util.Objects::nonNull)));
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
