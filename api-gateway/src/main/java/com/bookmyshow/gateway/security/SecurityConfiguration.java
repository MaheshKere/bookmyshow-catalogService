package com.bookmyshow.gateway.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Configuration
@Import(JwtValidationConfiguration.class)
public class SecurityConfiguration {
    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ObjectMapper mapper,
                                                  JwtAuthenticationConverter converter) {
        return http.csrf(csrf -> csrf.disable()).formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .authorizeExchange(auth -> auth
                        .pathMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .pathMatchers("/api/v1/users/admin/**").hasRole("ADMIN")
                        .pathMatchers("/api/v1/users/me").hasAnyRole("USER", "ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/shows/*/seats").permitAll()
                        .pathMatchers("/api/v1/shows/*/seats").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/movies/**", "/api/v1/theaters/**",
                                "/api/v1/screens/**", "/api/v1/shows/**").permitAll()
                        .pathMatchers("/api/v1/movies/**", "/api/v1/theaters/**",
                                "/api/v1/screens/**", "/api/v1/shows/**").hasRole("ADMIN")
                        .pathMatchers("/api/v1/reservations/**", "/api/v1/bookings/**").hasAnyRole("USER", "ADMIN")
                        .anyExchange().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((exchange, exception) -> problem(exchange, mapper, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((exchange, exception) -> problem(exchange, mapper, HttpStatus.FORBIDDEN)))
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(converter)))
                        .authenticationEntryPoint((exchange, exception) -> problem(exchange, mapper, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((exchange, exception) -> problem(exchange, mapper, HttpStatus.FORBIDDEN)))
                .build();
    }

    private Mono<Void> problem(ServerWebExchange exchange, ObjectMapper mapper, HttpStatus status) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status == HttpStatus.UNAUTHORIZED) response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        var detail = ProblemDetail.forStatusAndDetail(status, status == HttpStatus.UNAUTHORIZED
                ? "Authentication is required or the bearer token is invalid."
                : "You do not have permission to perform this operation.");
        try {
            return response.writeWith(Mono.just(response.bufferFactory().wrap(mapper.writeValueAsBytes(detail))));
        } catch (JsonProcessingException exception) {
            return Mono.error(exception);
        }
    }
}
