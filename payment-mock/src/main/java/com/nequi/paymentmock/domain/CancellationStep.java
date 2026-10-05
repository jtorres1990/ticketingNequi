package com.nequi.paymentmock.domain;

/** Outcome of one cancellation invocation: next state, the (fixed) status and whether it was a repetition. */
public record CancellationStep(Attempt next, CancellationStatus status, boolean replayed) {
}
