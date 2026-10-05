package com.nequi.paymentmock.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Cancellation registered for an attempt: fixed status, number of calls received, first reception and whether it
 * arrived after the authorization result existed ({@code cancelled} of PM-IV-011).
 */
public record Cancellation(CancellationStatus status, long received, Instant firstReceivedAt, boolean afterResult) {

    public Cancellation {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(firstReceivedAt, "firstReceivedAt");
    }

    Cancellation receivedAgain() {
        return new Cancellation(status, received + 1, firstReceivedAt, afterResult);
    }
}
