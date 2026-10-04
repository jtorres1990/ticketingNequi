package com.nequi.ticketing.application.port.out;

import java.util.Objects;

/**
 * Classified authorization result (ADR-030 "Classification in ticketing", ADR-029):
 * {@code APPROVED} confirms, {@code DECLINED} (including {@code ATTEMPT_CANCELLED}) rejects, a contract
 * or authentication error fails without reversal, and timeout, connection, 5xx or an open circuit are
 * "dependency unavailable" (transient).
 */
public sealed interface AuthorizationOutcome
        permits AuthorizationOutcome.Approved, AuthorizationOutcome.Declined,
        AuthorizationOutcome.ContractError, AuthorizationOutcome.DependencyUnavailable {

    record Approved(String providerReference) implements AuthorizationOutcome {
    }

    record Declined(String providerReference, String reasonCode) implements AuthorizationOutcome {
    }

    /** 4xx of contract or authentication: definitive technical error (ERR-008, AC-021). */
    record ContractError(int status) implements AuthorizationOutcome {
    }

    /** Timeout, connection error, 5xx or open circuit: the result is unknown (ALT-004). */
    record DependencyUnavailable(String reason) implements AuthorizationOutcome {
        public DependencyUnavailable {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
