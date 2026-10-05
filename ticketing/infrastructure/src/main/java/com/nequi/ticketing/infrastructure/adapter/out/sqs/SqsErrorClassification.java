package com.nequi.ticketing.infrastructure.adapter.out.sqs;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * Classification of SQS publication errors (ADR-026, ADR-035), established by SPK-018 against LocalStack
 * 4.14.0 and an SQS JSON protocol stub:
 * <ul>
 *   <li>transient (retried within the budget and counted by the circuit): the adapter timeout of an attempt
 *       ({@link TimeoutException}); client-side failures such as a refused connection or a stopped endpoint
 *       ({@link SdkClientException}); service errors with status 5xx; throttling
 *       ({@link AwsServiceException#isThrottlingException()}, including {@code RequestThrottled} with 403 and
 *       429);</li>
 *   <li>non-retryable (definitive at once, ignored by the circuit): every other service error, for example
 *       {@code QueueDoesNotExist}, {@code AccessDenied}, {@code InvalidMessageContents} or
 *       {@code InvalidParameterValue} (invalid attribute or oversized message); and any other exception.</li>
 * </ul>
 */
public final class SqsErrorClassification {

    private SqsErrorClassification() {
    }

    public static boolean isTransient(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof TimeoutException || cause instanceof SdkClientException) {
            return true;
        }
        if (cause instanceof AwsServiceException service) {
            return service.statusCode() >= 500 || service.isThrottlingException();
        }
        return false;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
