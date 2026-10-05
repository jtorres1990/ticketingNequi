package com.nequi.ticketing.infrastructure.observability;

import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

/**
 * W3C {@code traceparent} of the HTTP server span of a request (CMP-018, ADR-037). The server observation of
 * WebFlux keeps its context in the exchange attributes; the tracing handler of Micrometer Tracing attaches the
 * span to it. The {@code api} role writes this value into the Reactor context of the request so that MSG-001 and
 * MSG-002 carry the trace of the request, and uses its trace-id as the {@code traceId} of the Problem Details.
 */
public final class HttpTraceResolver {

    private HttpTraceResolver() {
    }

    /** The {@code traceparent} of the current HTTP server span, if a sampled or unsampled span exists. */
    public static Optional<String> traceParent(Map<String, Object> exchangeAttributes) {
        return ServerRequestObservationContext.findCurrent(exchangeAttributes)
                .<TracingContext>map(context -> context.get(TracingContext.class))
                .flatMap(tracing -> Spans.traceParentOf(tracing.getSpan()));
    }
}
