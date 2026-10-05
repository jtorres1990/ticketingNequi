package com.nequi.ticketing.infrastructure.adapter.sqs;

import java.net.URI;
import java.util.Objects;

/**
 * Connection of the SQS adapters (ADR-039): optional endpoint override (LocalStack) and region.
 * Credentials are never part of the configuration; they come from the environment or the task role.
 */
public record SqsConnectionSettings(URI endpointOverride, String region) {

    public SqsConnectionSettings {
        Objects.requireNonNull(region, "region");
        if (region.isBlank()) {
            throw new IllegalArgumentException("region must not be blank");
        }
    }
}
