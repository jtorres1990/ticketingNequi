package com.nequi.paymentmock.domain;

import java.util.Objects;

/**
 * Default outcome and deterministic decline percentage (PM-IV-008). Initial state and state after reset:
 * {@code APPROVED} and 0.
 */
public record Defaults(Outcome defaultOutcome, int declinePercentage) {

    public static final Defaults INITIAL = new Defaults(Outcome.APPROVED, 0);

    public Defaults {
        Objects.requireNonNull(defaultOutcome, "defaultOutcome");
        if (declinePercentage < 0 || declinePercentage > 100) {
            throw new InvalidConfigurationException("declinePercentage must be between 0 and 100");
        }
    }
}
