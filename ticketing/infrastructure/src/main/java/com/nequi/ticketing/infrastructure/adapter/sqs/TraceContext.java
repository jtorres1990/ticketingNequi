package com.nequi.ticketing.infrastructure.adapter.sqs;

import java.util.Optional;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Propagation of the W3C trace context between the Reactor context and the {@code traceparent} message
 * attribute (messaging v2 §2: "contexto de traza"). The publisher copies the value found in the Reactor
 * context into the message; the consumers put the received value into the context of the use case. The
 * tracing infrastructure that fills and reads the context is wired in INC-010 (ADR-037).
 */
public final class TraceContext {

    /** Reactor context key holding the W3C {@code traceparent} value. */
    public static final String KEY = MessageCodec.ATTRIBUTE_TRACE_PARENT;

    private TraceContext() {
    }

    public static Optional<String> from(ContextView context) {
        return context.getOrEmpty(KEY).map(Object::toString);
    }

    public static Context with(Context context, String traceParent) {
        return traceParent == null || traceParent.isBlank() ? context : context.put(KEY, traceParent);
    }
}
