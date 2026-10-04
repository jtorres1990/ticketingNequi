package com.nequi.ticketing.application.port.out;

import java.util.Objects;

/**
 * Classified cancellation result (ADR-030): a 200 with any {@code cancellationStatus} confirms the
 * reversal; anything else leaves the reversal pending for the next attempt of the reversal process
 * (ADR-025).
 */
public sealed interface CancellationOutcome
        permits CancellationOutcome.Cancelled, CancellationOutcome.ContractError,
        CancellationOutcome.DependencyUnavailable {

    record Cancelled(CancellationStatus status) implements CancellationOutcome {
        public Cancelled {
            Objects.requireNonNull(status, "status");
        }
    }

    record ContractError(int status) implements CancellationOutcome {
    }

    record DependencyUnavailable(String reason) implements CancellationOutcome {
        public DependencyUnavailable {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** {@code cancellationStatus} of API-102. */
    enum CancellationStatus {
        REVERSED,
        VOIDED,
        REGISTERED_BEFORE_CHARGE
    }
}
