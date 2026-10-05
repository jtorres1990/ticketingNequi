package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** CMP-018: DynamoDB executions reported with operation, outcome and duration; the resource closes its client. */
class DynamoDbObservationTest {

    @Test
    @DisplayName("CMP-018 aws-target §7 DynamoDB outcomes: success, throttled, condition failed, transaction conflict, error")
    void outcomes() {
        assertThat(DynamoDbMetricsInterceptor.outcome(TransactionCanceledException.builder()
                .cancellationReasons(CancellationReason.builder().code("None").build(),
                        CancellationReason.builder().code("TransactionConflict").build()).build()))
                .isEqualTo(DynamoDbEvents.TRANSACTION_CONFLICT);
        assertThat(DynamoDbMetricsInterceptor.outcome(TransactionCanceledException.builder()
                .cancellationReasons(CancellationReason.builder().code("ConditionalCheckFailed").build()).build()))
                .isEqualTo(DynamoDbEvents.CONDITION_FAILED);
        assertThat(DynamoDbMetricsInterceptor.outcome(TransactionCanceledException.builder().build()))
                .isEqualTo(DynamoDbEvents.CONDITION_FAILED);
        assertThat(DynamoDbMetricsInterceptor.outcome(ConditionalCheckFailedException.builder().build()))
                .isEqualTo(DynamoDbEvents.CONDITION_FAILED);
        assertThat(DynamoDbMetricsInterceptor.outcome(AwsServiceException.builder().statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("ThrottlingException").build()).build()))
                .isEqualTo(DynamoDbEvents.THROTTLED);
        assertThat(DynamoDbMetricsInterceptor.outcome(AwsServiceException.builder().statusCode(500)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("InternalServerError").build()).build()))
                .isEqualTo(DynamoDbEvents.ERROR);
        assertThat(DynamoDbMetricsInterceptor.outcome(new IllegalStateException("x"))).isEqualTo(DynamoDbEvents.ERROR);
    }

    @Test
    @DisplayName("CMP-018 the interceptor measures each execution and never fails a request when the hook fails")
    void interceptorReports() {
        List<String> reported = new CopyOnWriteArrayList<>();
        AtomicLong clock = new AtomicLong(1_000_000L);
        DynamoDbMetricsInterceptor interceptor = new DynamoDbMetricsInterceptor(
                (operation, outcome, duration) -> reported.add(operation + "/" + outcome + "/" + duration.toMillis()),
                clock::get);
        ExecutionAttributes attributes = new ExecutionAttributes();
        attributes.putAttribute(SdkExecutionAttribute.OPERATION_NAME, "PutItem");
        interceptor.beforeExecution(mock(Context.BeforeExecution.class), attributes);
        clock.addAndGet(5_000_000L);
        interceptor.afterExecution(mock(Context.AfterExecution.class), attributes);

        Context.FailedExecution failed = mock(Context.FailedExecution.class);
        when(failed.exception()).thenReturn(ConditionalCheckFailedException.builder().build());
        interceptor.onExecutionFailure(failed, new ExecutionAttributes());

        assertThat(reported).containsExactly("PutItem/success/5", "unknown/condition_failed/0");

        DynamoDbMetricsInterceptor throwing = new DynamoDbMetricsInterceptor((operation, outcome, duration) -> {
            throw new IllegalStateException("hook failure");
        }, System::nanoTime);
        throwing.afterExecution(mock(Context.AfterExecution.class), attributes);
        DynamoDbEvents.NONE.executed("GetItem", DynamoDbEvents.SUCCESS, Duration.ZERO);
    }

    @Test
    @DisplayName("CMP-021 CMP-010 the DynamoDB resource composes the persistence ports and closes its client")
    void resources() {
        List<String> reported = new CopyOnWriteArrayList<>();
        DynamoDbResources resources = DynamoDbResources.open(
                new DynamoDbConnectionSettings(URI.create("http://127.0.0.1:1"), "us-east-1"),
                DynamoDbAdapterSettings.deployed("ticketing"), Instant::now,
                (operation, outcome, duration) -> reported.add(outcome));
        assertThat(resources.persistence().eventCatalog()).isNotNull();
        assertThat(resources.persistence().orderLifecycleStore()).isNotNull();
        resources.close();
        DynamoDbResources.open(new DynamoDbConnectionSettings(null, "us-east-1"),
                DynamoDbAdapterSettings.deployed("ticketing"), Instant::now, DynamoDbEvents.NONE).close();
        assertThat(reported).isEmpty();
    }
}
