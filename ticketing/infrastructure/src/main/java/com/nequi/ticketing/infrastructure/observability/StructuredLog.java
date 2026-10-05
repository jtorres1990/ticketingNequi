package com.nequi.ticketing.infrastructure.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Structured log of the observability hooks (CMP-018, ADR-037): every record carries a stable {@code event}
 * name and key-value fields (trace, {@code orderId}, {@code eventId}, queue, circuit, ...) that the JSON console
 * format of Spring Boot renders as members (IV-007). Records are written by the {@link LogDispatcher} thread,
 * never on the reactive thread that reported them. No token, authorization header nor request body is ever
 * passed here (ADR-032).
 */
public final class StructuredLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("com.nequi.ticketing.observability");

    private final LogDispatcher dispatcher;
    private final Logger logger;

    public StructuredLog(LogDispatcher dispatcher) {
        this(dispatcher, LOGGER);
    }

    StructuredLog(LogDispatcher dispatcher, Logger logger) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Fluent record: {@code log.warn("sqs.publication.failed", "...").with("queue", q).cause(e).write()}. */
    public Entry info(String event, String message) {
        return new Entry(Level.INFO, event, message);
    }

    public Entry warn(String event, String message) {
        return new Entry(Level.WARN, event, message);
    }

    public Entry error(String event, String message) {
        return new Entry(Level.ERROR, event, message);
    }

    public Entry debug(String event, String message) {
        return new Entry(Level.DEBUG, event, message);
    }

    /** One record under construction; {@link #write()} hands it to the dispatcher. */
    public final class Entry {

        private final Level level;
        private final String message;
        private final Map<String, Object> fields = new LinkedHashMap<>();
        private Throwable cause;

        private Entry(Level level, String event, String message) {
            this.level = level;
            this.message = Objects.requireNonNull(message, "message");
            fields.put("event", Objects.requireNonNull(event, "event"));
        }

        /** Adds a field; {@code null} values are omitted. */
        public Entry with(String key, Object value) {
            if (value != null) {
                fields.put(Objects.requireNonNull(key, "key"), value instanceof Enum<?> constant
                        ? constant.name() : value);
            }
            return this;
        }

        public Entry cause(Throwable error) {
            this.cause = error;
            return this;
        }

        /** Enqueues the record; returns {@code false} when the dispatcher dropped it. */
        public boolean write() {
            if (!logger.isEnabledForLevel(level)) {
                return false;
            }
            Map<String, Object> snapshot = Map.copyOf(fields);
            Throwable error = cause;
            return dispatcher.submit(() -> {
                LoggingEventBuilder builder = logger.atLevel(level).setMessage(message);
                snapshot.forEach(builder::addKeyValue);
                if (error != null) {
                    builder.setCause(error);
                }
                builder.log();
            });
        }
    }
}
