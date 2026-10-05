package com.nequi.paymentmock.web;

import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * {@code X-Api-Key} authentication evaluated before routing on every path except {@code /health}, including
 * paths that do not exist (PM-IV-005). Missing, empty, repeated or wrong keys answer 401 {@code UNAUTHENTICATED}.
 * The presented value is never logged nor echoed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiKeyWebFilter implements WebFilter {

    static final String HEADER = "X-Api-Key";
    static final String HEALTH_PATH = "/health";

    private static final ErrorBody UNAUTHORIZED = new ErrorBody(ErrorBody.UNAUTHENTICATED, "Missing or invalid API key");

    private final ApiKeyVerifier verifier;

    public ApiKeyWebFilter(ApiKeyVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (HEALTH_PATH.equals(path)) {
            return chain.filter(exchange);
        }
        List<String> presented = exchange.getRequest().getHeaders().get(HEADER);
        if (presented != null && presented.size() == 1 && verifier.matches(presented.getFirst())) {
            return chain.filter(exchange);
        }
        return ErrorWriter.write(exchange, HttpStatus.UNAUTHORIZED, UNAUTHORIZED);
    }
}
