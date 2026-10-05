package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import com.nequi.ticketing.application.error.DependencyUnavailableException;

/**
 * Technical failure of the DynamoDB adapter after the bounded SDK retries (ADR-035): throttling,
 * unavailability, unprocessed batch items that persisted or an unexpected cancellation reason. It never
 * represents a failed condition, which is a typed result of the port. The HTTP adapter answers it with
 * {@code SERVICE_UNAVAILABLE} (INC-008).
 */
public final class DynamoDbStoreException extends DependencyUnavailableException {

    public DynamoDbStoreException(String operation, Throwable cause) {
        super("DynamoDB operation failed: " + operation, cause);
    }

    public DynamoDbStoreException(String message) {
        super(message);
    }
}
