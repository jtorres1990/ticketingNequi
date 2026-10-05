package com.nequi.paymentmock.application;

import com.nequi.paymentmock.domain.CancellationStatus;
import java.time.Instant;

/** API-109 record: cancellations received, fixed status and first reception. */
public record CancellationView(String paymentAttemptId, long received, CancellationStatus status,
        Instant firstReceivedAt) {
}
