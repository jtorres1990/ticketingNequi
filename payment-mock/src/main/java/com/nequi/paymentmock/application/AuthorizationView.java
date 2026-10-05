package com.nequi.paymentmock.application;

import com.nequi.paymentmock.domain.AuthorizationResult;

/**
 * API-108 record: invocations received and stored result (null when none); {@code cancelled} reflects the current
 * state (PM-IV-011).
 */
public record AuthorizationView(String paymentAttemptId, long invocations, AuthorizationResult result,
        boolean cancelled) {
}
