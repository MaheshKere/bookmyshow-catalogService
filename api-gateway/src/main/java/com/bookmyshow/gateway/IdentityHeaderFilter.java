package com.bookmyshow.gateway;

import org.springframework.cloud.gateway.filter.*;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class IdentityHeaderFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest().mutate().headers(headers -> {
            headers.remove("X-User-Id");
            headers.remove("X-Role");
            headers.remove("X-Roles");
            headers.remove("X-Authorities");
        }).build();
        // Keep Authorization: downstream services independently validate the original signed token.
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override public int getOrder() { return -1; }
}
