package com.nequi.ticketing.application.port.in;

import java.time.Instant;
import java.util.Objects;

/**
 * Typed result of processing one queue message (ADR-029, {@code ticketing.messaging.v2.md} §5), which the
 * consumer adapter translates into SQS operations:
 * <ul>
 *   <li>{@link Delete}: stable result, delete the message;</li>
 *   <li>{@link PostponeUntil}: a lease of another consumer is in force, change the visibility until it ends;</li>
 *   <li>{@link Retry}: do not delete, apply the backoff of the queue (on the last reception the message
 *       moves to the DLQ);</li>
 *   <li>{@link Poison}: unreadable message or missing entity, do not delete, short visibility.</li>
 * </ul>
 */
public sealed interface MessageDisposition
        permits MessageDisposition.Delete, MessageDisposition.PostponeUntil, MessageDisposition.Retry,
        MessageDisposition.Poison {

    DispositionReason reason();

    static MessageDisposition delete(DispositionReason reason) {
        return new Delete(reason);
    }

    static MessageDisposition postponeUntil(Instant until, DispositionReason reason) {
        return new PostponeUntil(until, reason);
    }

    static MessageDisposition retry(DispositionReason reason) {
        return new Retry(reason);
    }

    static MessageDisposition poison(DispositionReason reason) {
        return new Poison(reason);
    }

    record Delete(DispositionReason reason) implements MessageDisposition {
        public Delete {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record PostponeUntil(Instant until, DispositionReason reason) implements MessageDisposition {
        public PostponeUntil {
            Objects.requireNonNull(until, "until");
            Objects.requireNonNull(reason, "reason");
        }
    }

    record Retry(DispositionReason reason) implements MessageDisposition {
        public Retry {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record Poison(DispositionReason reason) implements MessageDisposition {
        public Poison {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
