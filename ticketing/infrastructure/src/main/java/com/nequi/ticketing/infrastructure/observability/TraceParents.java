package com.nequi.ticketing.infrastructure.observability;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * W3C {@code traceparent} values (version {@code 00}), the trace context carried by the HTTP requests and by the
 * {@code traceparent} attribute of MSG-001 / MSG-002 (messaging v2 §2, ADR-037).
 */
public final class TraceParents {

    private static final Pattern FORMAT =
            Pattern.compile("([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");
    private static final String INVALID_TRACE_ID = "0".repeat(32);
    private static final String INVALID_SPAN_ID = "0".repeat(16);

    private TraceParents() {
    }

    /** Parsed parts of a valid value: trace-id, parent span-id and the sampled flag. */
    public record TraceParent(String traceId, String spanId, boolean sampled) {

        public String format() {
            return TraceParents.format(traceId, spanId, sampled);
        }
    }

    public static Optional<TraceParent> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher matcher = FORMAT.matcher(value.trim());
        if (!matcher.matches() || "ff".equals(matcher.group(1)) || INVALID_TRACE_ID.equals(matcher.group(2))
                || INVALID_SPAN_ID.equals(matcher.group(3))) {
            return Optional.empty();
        }
        int flags = Integer.parseInt(matcher.group(4), 16);
        return Optional.of(new TraceParent(matcher.group(2), matcher.group(3), (flags & 1) == 1));
    }

    /** Formats a version {@code 00} value; the identifiers must satisfy {@link #valid(String, String)}. */
    public static String format(String traceId, String spanId, boolean sampled) {
        return "00-" + traceId + "-" + spanId + (sampled ? "-01" : "-00");
    }

    /** Whether the identifiers can be carried in a {@code traceparent}. */
    public static boolean valid(String traceId, String spanId) {
        return traceId != null && spanId != null && traceId.matches("[0-9a-f]{32}") && spanId.matches("[0-9a-f]{16}")
                && !INVALID_TRACE_ID.equals(traceId) && !INVALID_SPAN_ID.equals(spanId);
    }
}
