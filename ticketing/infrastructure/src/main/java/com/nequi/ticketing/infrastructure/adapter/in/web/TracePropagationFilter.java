package com.nequi.ticketing.infrastructure.adapter.in.web;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Connects the HTTP adapter with the tracing of CMP-018 (ADR-035, ADR-037, IV-007): when the server span of the
 * request exists, its trace-id is the {@code traceId} of the Problem Details and the {@code correlationId} of the
 * commands ({@link TraceIds}), and its W3C {@code traceparent} is written into the Reactor context of the request
 * so that the publications of MSG-001 / MSG-002 carry the trace of the request. Without a span (tracing absent)
 * the request is unchanged. The resolver reads the exchange attributes; the writer is supplied by the
 * composition (the context key of the SQS adapter), so this adapter knows neither the tracer nor the publisher.
 */
public final class TracePropagationFilter implements WebFilter {

    private final Function<Map<String, Object>, Optional<String>> currentTraceParent;
    private final BiFunction<Context, String, Context> propagate;

    public TracePropagationFilter(Function<Map<String, Object>, Optional<String>> currentTraceParent,
            BiFunction<Context, String, Context> propagate) {
        this.currentTraceParent = Objects.requireNonNull(currentTraceParent, "currentTraceParent");
        this.propagate = Objects.requireNonNull(propagate, "propagate");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Optional<String> traceParent = currentTraceParent.apply(exchange.getAttributes());
        if (traceParent.isEmpty()) {
            return chain.filter(exchange);
        }
        String value = traceParent.get();
        String traceId = TraceIds.fromTraceParent(value);
        exchange.getAttributes().put(TraceIds.ATTRIBUTE, traceId);
        return chain.filter(exchange).contextWrite(context -> propagate.apply(context, value));
    }
}
