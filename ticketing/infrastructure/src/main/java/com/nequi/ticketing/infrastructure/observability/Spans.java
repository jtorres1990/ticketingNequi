package com.nequi.ticketing.infrastructure.observability;

import com.nequi.ticketing.infrastructure.adapter.sqs.TraceContext;
import com.nequi.ticketing.infrastructure.observability.TraceParents.TraceParent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext.Builder;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import reactor.core.publisher.Mono;

/**
 * Spans of the {@code worker} side of a trace (CMP-018, ADR-037: "trazas propagadas por los atributos de los
 * mensajes, hasta el consumidor, la llamada de pago y el aprovisionamiento"). A span is a child of the W3C
 * {@code traceparent} found in the Reactor context ({@link TraceContext}, filled by the consumers from the
 * message attribute) and, while the body runs, its own {@code traceparent} replaces it, so that the payment
 * call and any publication of the body continue the same trace. With the no-op tracer the body runs unchanged.
 */
public final class Spans {

    private final Tracer tracer;

    public Spans(Tracer tracer) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    /** Runs {@code body} inside a new span named {@code name}, child of the trace context of the subscriber. */
    public <T> Mono<T> child(String name, Map<String, String> tags, Mono<T> body) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(tags, "tags");
        Objects.requireNonNull(body, "body");
        return Mono.deferContextual(context -> {
            Span span = start(name, TraceContext.from(context).flatMap(TraceParents::parse));
            tags.forEach(span::tag);
            Optional<String> own = traceParentOf(span);
            Mono<T> traced = own.isPresent() ? body.contextWrite(inner -> TraceContext.with(inner, own.get())) : body;
            return traced.doOnError(span::error).doFinally(signal -> span.end());
        });
    }

    private Span start(String name, Optional<TraceParent> parent) {
        Span.Builder builder = tracer.spanBuilder().name(name);
        if (parent.isPresent()) {
            Builder context = tracer.traceContextBuilder()
                    .traceId(parent.get().traceId())
                    .spanId(parent.get().spanId())
                    .sampled(parent.get().sampled());
            builder.setParent(context.build());
        }
        return builder.start();
    }

    /** The W3C {@code traceparent} of a span, when its identifiers can be carried in one. */
    public static Optional<String> traceParentOf(Span span) {
        if (span == null) {
            return Optional.empty();
        }
        io.micrometer.tracing.TraceContext context = span.context();
        if (context == null || !TraceParents.valid(context.traceId(), context.spanId())) {
            return Optional.empty();
        }
        return Optional.of(TraceParents.format(context.traceId(), context.spanId(),
                Boolean.TRUE.equals(context.sampled())));
    }
}
