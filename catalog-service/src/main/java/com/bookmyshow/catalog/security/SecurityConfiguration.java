package com.bookmyshow.catalog.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Import(JwtValidationConfiguration.class)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper,
                                           JwtAuthenticationConverter converter) throws Exception {
        // Bearer credentials are sent explicitly in headers, never in an automatically attached cookie.
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/movies/**", "/api/v1/theaters/**", "/api/v1/screens/**", "/api/v1/shows/**").permitAll()
                        .requestMatchers("/api/v1/movies/**", "/api/v1/theaters/**",
                                "/api/v1/screens/**", "/api/v1/shows/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                SecurityProblemSupport.write(response, mapper, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) ->
                                SecurityProblemSupport.write(response, mapper, HttpStatus.FORBIDDEN)))
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, exception) ->
                                SecurityProblemSupport.write(response, mapper, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) ->
                                SecurityProblemSupport.write(response, mapper, HttpStatus.FORBIDDEN)))
                .build();
    }
}
