package com.nequi.ticketing.infrastructure.adapter.out.sqs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.model.InvalidMessageContentsException;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.RequestThrottledException;
import software.amazon.awssdk.services.sqs.model.SqsException;

/** Classification table of SPK-018 (ADR-026, ADR-035). */
class SqsErrorClassificationTest {

    @Test
    @DisplayName("SPK-018 transient: attempt timeout, client/connection failures, 5xx and throttling (400, 403 RequestThrottled, 429)")
    void transientErrors() {
        assertThat(SqsErrorClassification.isTransient(new TimeoutException())).isTrue();
        assertThat(SqsErrorClassification.isTransient(SdkClientException.create("connection refused"))).isTrue();
        assertThat(SqsErrorClassification.isTransient(ApiCallAttemptTimeoutException.create(500))).isTrue();
        assertThat(SqsErrorClassification.isTransient(service(500, "InternalError"))).isTrue();
        assertThat(SqsErrorClassification.isTransient(service(503, "ServiceUnavailable"))).isTrue();
        assertThat(SqsErrorClassification.isTransient(service(400, "ThrottlingException"))).isTrue();
        assertThat(SqsErrorClassification.isTransient(service(429, "TooManyRequestsException"))).isTrue();
        assertThat(SqsErrorClassification.isTransient(RequestThrottledException.builder().statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("RequestThrottled").build()).build())).isTrue();
        assertThat(SqsErrorClassification.isTransient(new CompletionException(new TimeoutException()))).isTrue();
    }

    @Test
    @DisplayName("SPK-018 non-retryable: missing queue, access denied, invalid contents, invalid parameter and unknown errors")
    void nonRetryableErrors() {
        assertThat(SqsErrorClassification.isTransient(QueueDoesNotExistException.builder().statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AWS.SimpleQueueService.NonExistentQueue").build())
                .build())).isFalse();
        assertThat(SqsErrorClassification.isTransient(service(403, "AccessDenied"))).isFalse();
        assertThat(SqsErrorClassification.isTransient(InvalidMessageContentsException.builder().statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("InvalidMessageContents").build()).build())).isFalse();
        assertThat(SqsErrorClassification.isTransient(service(400, "InvalidParameterValue"))).isFalse();
        assertThat(SqsErrorClassification.isTransient(new IllegalStateException())).isFalse();
        assertThat(SqsErrorClassification.isTransient(new CompletionException(service(403, "AccessDenied")))).isFalse();
        assertThat(SqsErrorClassification.isTransient(new CompletionException(null))).isFalse();
    }

    private static SqsException service(int status, String code) {
        return (SqsException) SqsException.builder().statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build()).build();
    }
}
