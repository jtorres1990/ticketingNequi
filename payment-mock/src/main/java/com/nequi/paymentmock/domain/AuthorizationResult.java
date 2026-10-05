package com.nequi.paymentmock.domain;

import java.util.Objects;

/** Stored authorization result: {@code APPROVED} without reason, or {@code DECLINED} with a reason. */
public record AuthorizationResult(Outcome status, ReasonCode reasonCode) {

    public static final AuthorizationResult APPROVED = new AuthorizationResult(Outcome.APPROVED, null);
    public static final AuthorizationResult ATTEMPT_CANCELLED =
            new AuthorizationResult(Outcome.DECLINED, ReasonCode.ATTEMPT_CANCELLED);

    public AuthorizationResult {
        Objects.requireNonNull(status, "status");
        if ((status == Outcome.APPROVED) != (reasonCode == null)) {
            throw new IllegalArgumentException("reasonCode is present exactly when the status is DECLINED");
        }
    }

    public static AuthorizationResult declined(ReasonCode reasonCode) {
        return new AuthorizationResult(Outcome.DECLINED, Objects.requireNonNull(reasonCode, "reasonCode"));
    }
}
