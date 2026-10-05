package com.nequi.paymentmock.application;

import com.nequi.paymentmock.domain.AuthorizationResult;

/** Observable answer of API-101. */
public sealed interface AuthorizationReply {

    /** 200 with the result; {@code replayed} and {@code cancelled} as defined by PM-IV-011. */
    record Authorized(String paymentAttemptId, AuthorizationResult result, boolean replayed, boolean cancelled)
            implements AuthorizationReply {
    }

    /** 422 {@code IDEMPOTENCY_KEY_REUSED} (PM-IV-010). */
    record PayloadConflict() implements AuthorizationReply {
    }

    /** 422 {@code SIMULATED_DEFINITIVE_ERROR} (PM-IV-009). */
    record DefinitiveError() implements AuthorizationReply {
    }

    /** 503 {@code SIMULATED_TRANSIENT_FAILURE} (PM-IV-009). */
    record TransientFailure() implements AuthorizationReply {
    }
}
