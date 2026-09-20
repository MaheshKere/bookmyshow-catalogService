package com.bookmyshow.identity.security;

import com.bookmyshow.identity.auth.dto.TokenResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.List;

@Service
public class JwtTokenService {
    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;
    private final String audience;
    private final Duration ttl;

    public JwtTokenService(JwtEncoder encoder, Clock clock,
                           @Value("${security.jwt.issuer:bookmyshow-identity}") String issuer,
                           @Value("${security.jwt.audience:bookmyshow-api}") String audience,
                           @Value("${security.jwt.access-token-ttl:PT15M}") Duration ttl) {
        if (ttl.getSeconds() < 1 || ttl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("JWT access-token-ttl must be between 1 second and 1 hour");
        }
        this.encoder = encoder;
        this.clock = clock;
        this.issuer = issuer;
        this.audience = audience;
        this.ttl = ttl;
    }

    public TokenResponse generate(UserPrincipal principal) {
        var now = clock.instant();
        var role = principal.getAuthorities().iterator().next().getAuthority().substring("ROLE_".length());
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
                .subject(principal.getUserId().toString()).issuedAt(now).expiresAt(now.plus(ttl))
                .claim("role", role).build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build();
        return new TokenResponse(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(),
                "Bearer", ttl.getSeconds());
    }
}
