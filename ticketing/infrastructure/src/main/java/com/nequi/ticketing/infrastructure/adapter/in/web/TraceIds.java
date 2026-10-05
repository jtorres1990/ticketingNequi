package com.nequi.ticketing.infrastructure.adapter.in.web;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.springframework.web.server.ServerWebExchange;

/**
 * Trace identifier of a request: the {@code traceId} member of every Problem Details (ADR-035) and the
 * {@code correlationId} handed to the use cases (audit, MSG-001 / MSG-002). It is the trace-id of a valid W3C
 * {@code traceparent} header when the client sends one, otherwise a random 128-bit identifier; it is computed
 * once per exchange and kept in its attributes. The tracing infrastructure of INC-010 (ADR-037) may supply it.
 */
final class TraceIds {

    static final String ATTRIBUTE = TraceIds.class.getName() + ".traceId";
    static final String TRACE_PARENT = "traceparent";

    private static final Pattern TRACE_PARENT_FORMAT =
            Pattern.compile("[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}");
    private static final String INVALID_TRACE_ID = "0".repeat(32);

    private TraceIds() {
    }

    static String of(ServerWebExchange exchange) {
        Object existing = exchange.getAttributes().get(ATTRIBUTE);
        if (existing instanceof String traceId) {
            return traceId;
        }
        String traceId = fromTraceParent(exchange.getRequest().getHeaders().getFirst(TRACE_PARENT));
        exchange.getAttributes().put(ATTRIBUTE, traceId);
        return traceId;
    }

    static String fromTraceParent(String traceParent) {
        if (traceParent != null) {
            var matcher = TRACE_PARENT_FORMAT.matcher(traceParent.trim());
            if (matcher.matches() && !matcher.group(1).equals(INVALID_TRACE_ID) && !traceParent.trim().startsWith("ff")) {
                return matcher.group(1);
            }
        }
        return random();
    }

    private static String random() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return HexFormat.of().toHexDigits(random.nextLong()) + HexFormat.of().toHexDigits(random.nextLong() | 1L);
    }
}
