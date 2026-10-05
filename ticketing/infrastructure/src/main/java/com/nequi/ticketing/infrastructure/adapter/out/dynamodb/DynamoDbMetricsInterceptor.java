package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * SDK interceptor that reports every DynamoDB execution to {@link DynamoDbEvents} with its operation, outcome and
 * duration (from the start of the execution to its completion, retries of the SDK included). It only reads the
 * response or the exception: no item, key or expression leaves the adapter.
 */
final class DynamoDbMetricsInterceptor implements ExecutionInterceptor {

    private static final ExecutionAttribute<Long> STARTED_AT = new ExecutionAttribute<>("ticketing.metrics.startedAt");

    private final DynamoDbEvents events;
    private final LongSupplier nanoTime;

    DynamoDbMetricsInterceptor(DynamoDbEvents events, LongSupplier nanoTime) {
        this.events = Objects.requireNonNull(events, "events");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
        attributes.putAttribute(STARTED_AT, nanoTime.getAsLong());
    }

    @Override
    public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
        report(attributes, DynamoDbEvents.SUCCESS);
    }

    @Override
    public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes attributes) {
        report(attributes, outcome(context.exception()));
    }

    static String outcome(Throwable error) {
        if (error instanceof TransactionCanceledException cancelled) {
            boolean conflict = cancelled.hasCancellationReasons() && cancelled.cancellationReasons().stream()
                    .map(CancellationReason::code).anyMatch("TransactionConflict"::equals);
            return conflict ? DynamoDbEvents.TRANSACTION_CONFLICT : DynamoDbEvents.CONDITION_FAILED;
        }
        if (error instanceof ConditionalCheckFailedException) {
            return DynamoDbEvents.CONDITION_FAILED;
        }
        if (error instanceof AwsServiceException service && service.isThrottlingException()) {
            return DynamoDbEvents.THROTTLED;
        }
        return DynamoDbEvents.ERROR;
    }

    private void report(ExecutionAttributes attributes, String outcome) {
        Long startedAt = attributes.getAttribute(STARTED_AT);
        String operation = attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME);
        Duration duration = startedAt == null ? Duration.ZERO : Duration.ofNanos(nanoTime.getAsLong() - startedAt);
        try {
            events.executed(operation == null ? "unknown" : operation, outcome, duration);
        } catch (RuntimeException ignored) {
            // Observation never changes the result of a request.
        }
    }
}
