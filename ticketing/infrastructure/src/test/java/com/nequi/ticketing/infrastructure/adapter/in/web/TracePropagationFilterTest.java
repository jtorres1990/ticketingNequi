package com.nequi.ticketing.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** CMP-018 ADR-035: the trace of the HTTP server span becomes the traceId of the request and reaches MSG-001/MSG-002. */
class TracePropagationFilterTest {

    private static final String TRACE_PARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    @DisplayName("ADR-037 ADR-035 with a server span the traceId and the Reactor trace context follow the span")
    void propagatesTheServerSpan() {
        AtomicReference<String> seen = new AtomicReference<>();
        TracePropagationFilter filter = new TracePropagationFilter(attributes -> Optional.of(TRACE_PARENT),
                (context, value) -> context.put("traceparent", value));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/events"));
        WebFilterChain chain = ignored -> Mono.deferContextual(context -> {
            seen.set(context.getOrDefault("traceparent", "none"));
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(seen.get()).isEqualTo(TRACE_PARENT);
        assertThat(TraceIds.of(exchange)).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    @DisplayName("ADR-035 without a server span the request is unchanged")
    void withoutSpan() {
        AtomicReference<String> seen = new AtomicReference<>();
        TracePropagationFilter filter = new TracePropagationFilter(attributes -> Optional.empty(),
                (context, value) -> context.put("traceparent", value));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/events"));

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.deferContextual(context -> {
            seen.set(context.getOrDefault("traceparent", "none"));
            return Mono.empty();
        }))).verifyComplete();

        assertThat(seen.get()).isEqualTo("none");
        assertThat(exchange.getAttributes()).doesNotContainKey(TraceIds.ATTRIBUTE);
    }
}
