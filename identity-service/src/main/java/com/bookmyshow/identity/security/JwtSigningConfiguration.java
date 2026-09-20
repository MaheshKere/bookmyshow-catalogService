package com.bookmyshow.identity.security;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.*;
import java.io.IOException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

@Configuration
public class JwtSigningConfiguration {
    @Bean
    JwtEncoder jwtEncoder(@Value("${security.jwt.public-key-location}") Resource publicKeyResource,
                          @Value("${security.jwt.private-key-location}") Resource privateKeyResource) throws IOException {
        RSAPublicKey publicKey;
        RSAPrivateKey privateKey;
        try (var input = publicKeyResource.getInputStream()) { publicKey = RsaKeyConverters.x509().convert(input); }
        try (var input = privateKeyResource.getInputStream()) { privateKey = RsaKeyConverters.pkcs8().convert(input); }
        if (!publicKey.getModulus().equals(privateKey.getModulus())) {
            throw new IllegalStateException("JWT signing and verification keys do not match");
        }
        var key = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }
}
