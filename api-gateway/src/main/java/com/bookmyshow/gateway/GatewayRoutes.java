package com.bookmyshow.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.*;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.*;

@Configuration
public class GatewayRoutes {
    @Bean
    RouteLocator routes(RouteLocatorBuilder builder,
                        @Value("${downstream.identity-url}") String identity,
                        @Value("${downstream.catalog-url}") String catalog,
                        @Value("${downstream.booking-url}") String booking) {
        return builder.routes()
                .route("identity", route -> route.path("/api/v1/auth/**", "/api/v1/users/**").uri(identity))
                // More specific than Catalog's /shows/**: preserve existing seat endpoints.
                .route("booking-seats", route -> route.order(-10).path("/api/v1/shows/{showId}/seats").uri(booking))
                .route("booking", route -> route.path("/api/v1/reservations/**", "/api/v1/bookings/**").uri(booking))
                .route("catalog", route -> route.path("/api/v1/movies/**", "/api/v1/theaters/**",
                        "/api/v1/screens/**", "/api/v1/shows/**").uri(catalog))
                .build();
    }
}
