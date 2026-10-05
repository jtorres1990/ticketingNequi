package com.nequi.ticketing.infrastructure.adapter.out.payment;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.springframework.web.reactive.function.client.WebClientRequestException;

/**
 * Failure of one call to the Payment Mock, classified by the adapter (ADR-030, ADR-035): a transient failure
 * (timeout, connection, 5xx, unexpected status or invalid response) counts as a failure for the circuit
 * breaker and an authorization may be retried; a contract error (any 4xx, including authentication) is
 * definitive, is never retried and is ignored by the circuit statistics. Without stack trace: the class and
 * the stable reason code are the only information the adapter reports.
 */
abstract sealed class PaymentCallFailure extends RuntimeException
        permits PaymentCallFailure.Transient, PaymentCallFailure.Contract {

    static final String TIMEOUT = "TIMEOUT";
    static final String CONNECTION_FAILURE = "CONNECTION_FAILURE";
    static final String INVALID_RESPONSE = "INVALID_RESPONSE";
    static final String UNEXPECTED_ERROR = "UNEXPECTED_ERROR";

    private final String reason;

    private PaymentCallFailure(String reason) {
        super(reason, null, false, false);
        this.reason = reason;
    }

    String reason() {
        return reason;
    }

    static Transient transientFailure(String reason) {
        return new Transient(reason);
    }

    static Contract contract(int status) {
        return new Contract(status);
    }

    /** Errors raised outside the classification (transport, timeout) are transient with a stable reason. */
    static PaymentCallFailure classify(Throwable error) {
        if (error instanceof PaymentCallFailure failure) {
            return failure;
        }
        if (error instanceof TimeoutException) {
            return new Transient(TIMEOUT);
        }
        if (error instanceof WebClientRequestException || error instanceof IOException) {
            return new Transient(CONNECTION_FAILURE);
        }
        return new Transient(UNEXPECTED_ERROR);
    }

    /** Classification of the circuit breaker: only transient failures count (ADR-035). */
    static boolean countsAsFailure(Throwable error) {
        return error instanceof Transient;
    }

    static final class Transient extends PaymentCallFailure {
        private Transient(String reason) {
            super(reason);
        }
    }

    static final class Contract extends PaymentCallFailure {
        private final int status;

        private Contract(int status) {
            super("CONTRACT_ERROR_" + status);
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}
