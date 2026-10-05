package com.nequi.ticketing.infrastructure.observability;

import com.nequi.ticketing.infrastructure.adapter.out.dynamodb.DynamoDbEvents;
import java.time.Duration;

/**
 * DynamoDB executions as the {@code ticketing.dynamodb.requests} timer by operation and outcome: latency,
 * throttling ({@code throttled}) and transaction conflicts ({@code transaction_conflict}) of
 * {@code ticketing.aws-target.v2.md} §7. Metric only: a throttling storm must not become a log storm.
 */
final class ObservedDynamoDbEvents implements DynamoDbEvents {

    private final Telemetry telemetry;

    ObservedDynamoDbEvents(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    public void executed(String operation, String outcome, Duration duration) {
        telemetry.timer(MetricNames.DYNAMODB_REQUESTS, "operation", operation, "outcome", outcome).record(duration);
    }
}
