package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

/**
 * Technical failure of the DynamoDB adapter after the bounded SDK retries (ADR-035): throttling,
 * unavailability, unprocessed batch items that persisted or an unexpected cancellation reason. It never
 * represents a failed condition, which is a typed result of the port.
 */
public final class DynamoDbStoreException extends RuntimeException {

    public DynamoDbStoreException(String operation, Throwable cause) {
        super("DynamoDB operation failed: " + operation, cause);
    }

    public DynamoDbStoreException(String message) {
        super(message);
    }
}
