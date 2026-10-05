package com.nequi.ticketing.infrastructure.observability;

import com.nequi.ticketing.application.port.in.MessageDisposition;

/** Metric tags of a {@link MessageDisposition}. */
final class Dispositions {

    private Dispositions() {
    }

    static String action(MessageDisposition disposition) {
        return switch (disposition) {
            case MessageDisposition.Delete delete -> "delete";
            case MessageDisposition.PostponeUntil postpone -> "postpone";
            case MessageDisposition.Retry retry -> "retry";
            case MessageDisposition.Poison poison -> "poison";
        };
    }

    static void count(Telemetry telemetry, String queue, MessageDisposition disposition) {
        telemetry.counter(MetricNames.MESSAGES_PROCESSED, "queue", queue, "action", action(disposition),
                "reason", disposition.reason().name()).increment();
    }
}
