package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import java.net.URI;
import java.util.Objects;

/**
 * Connection of the DynamoDB adapter (ADR-039): optional endpoint override (DynamoDB Local) and region.
 * Credentials are never part of the configuration; they come from the environment or the task role.
 */
public record DynamoDbConnectionSettings(URI endpointOverride, String region) {

    public DynamoDbConnectionSettings {
        Objects.requireNonNull(region, "region");
        if (region.isBlank()) {
            throw new IllegalArgumentException("region must not be blank");
        }
    }
}
