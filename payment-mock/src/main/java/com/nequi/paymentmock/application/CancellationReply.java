package com.nequi.paymentmock.application;

import com.nequi.paymentmock.domain.CancellationStatus;

/** Observable answer of API-102; {@code replayed} is false only for the cancellation that fixed the status. */
public record CancellationReply(String paymentAttemptId, CancellationStatus status, boolean replayed) {
}
