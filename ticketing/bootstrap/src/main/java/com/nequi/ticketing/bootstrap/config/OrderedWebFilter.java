package com.nequi.ticketing.bootstrap.config;

import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Gives a position in the filter chain to an adapter filter (the security chain proxy runs at order -100). */
final class OrderedWebFilter implements WebFilter, Ordered {

    private final int order;
    private final WebFilter delegate;

    OrderedWebFilter(int order, WebFilter delegate) {
        this.order = order;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return delegate.filter(exchange, chain);
    }

    @Override
    public int getOrder() {
        return order;
    }
}
