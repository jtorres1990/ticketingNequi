package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import java.time.Duration;

/**
 * Observation hook of the DynamoDB client (CMP-010, CMP-018; {@code ticketing.aws-target.v2.md} §7: latency,
 * throttling and conflicts of DynamoDB). It is called once per SDK execution (after the retries of the SDK) on
 * the thread that completes it and must not block (NFR-003). It never receives items, keys or SDK types.
 */
public interface DynamoDbEvents {

    /** Outcome of a successful execution. */
    String SUCCESS = "success";
    /** The service throttled the request (after the retries of the SDK). */
    String THROTTLED = "throttled";
    /** A condition of the write or of the transaction was not met (expected business outcome). */
    String CONDITION_FAILED = "condition_failed";
    /** The transaction was cancelled by a conflicting transaction (ADR-023: retried by the adapter). */
    String TRANSACTION_CONFLICT = "transaction_conflict";
    /** Any other failure (technical unavailability, service or client error). */
    String ERROR = "error";

    DynamoDbEvents NONE = (operation, outcome, duration) -> {
    };

    /** One SDK execution of {@code operation} ({@code PutItem}, {@code TransactWriteItems}, ...) ended. */
    void executed(String operation, String outcome, Duration duration);
}
