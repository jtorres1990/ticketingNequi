package com.nequi.ticketing.infrastructure.adapter.in.web;

import java.util.Objects;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * CMP-016 last-resort handler: a failure raised outside the router (for example by a web filter) is still
 * answered by {@link ApiProblems}, so that no framework error page or technical detail reaches the client
 * (ADR-035). The bootstrap module registers it ahead of the framework handlers (INC-010).
 */
public final class ApiExceptionHandler implements WebExceptionHandler {

    private final ApiProblems problems;

    public ApiExceptionHandler(ApiProblems problems) {
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        return problems.write(exchange, error);
    }
}
