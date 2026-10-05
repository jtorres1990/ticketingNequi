package com.nequi.ticketing.infrastructure.adapter.in.web;

import java.util.Objects;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * CMP-017 request guard, body size (NFR-010, ADR-032): a request whose declared {@code Content-Length} exceeds
 * the limit (256 KB) is rejected with {@code 413 PAYLOAD_TOO_LARGE} before anything reads it. Bodies without a
 * declared length (chunked) are bounded while they are read: the adapter aggregates at most the limit and
 * answers 413 beyond it ({@code DataBufferLimitException}). It must run before any other filter, including the
 * security chain, so that an oversized body is never processed.
 */
public final class RequestBodyLimitFilter implements WebFilter {

    private final WebApiSettings settings;
    private final ApiProblems problems;

    public RequestBodyLimitFilter(WebApiSettings settings, ApiProblems problems) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long declared = exchange.getRequest().getHeaders().getContentLength();
        if (declared > settings.maximumBodyBytes()) {
            return problems.write(exchange, ApiRejection.payloadTooLarge());
        }
        return chain.filter(exchange);
    }
}
