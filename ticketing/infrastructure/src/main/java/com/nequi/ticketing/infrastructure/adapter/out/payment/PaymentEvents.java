package com.nequi.ticketing.infrastructure.adapter.out.payment;

/**
 * Observation hook of the Payment gateway adapter (CMP-013). Like the SQS adapters, the adapter never logs
 * nor records metrics on reactive threads; it reports here and the observability wiring of INC-010
 * (ADR-037) turns these notifications into structured logs and metrics. Implementations must not block
 * (NFR-003) and never receive the API key. Every method has a no-op default.
 */
public interface PaymentEvents {

    /** Operation name of API-101. */
    String AUTHORIZE = "authorize";

    /** Operation name of API-102. */
    String CANCEL = "cancel";

    PaymentEvents NONE = new PaymentEvents() {
    };

    /**
     * One call failed: {@code reason} is a stable code ({@code TIMEOUT}, {@code CONNECTION_FAILURE},
     * {@code HTTP_<status>}, {@code INVALID_RESPONSE}, {@code CONTRACT_ERROR_<status>}, ...). A failed
     * authorization call may still be retried.
     */
    default void callFailed(String operation, String paymentAttemptId, String reason) {
    }

    /**
     * The operation ended without a provider result ("dependency unavailable"): transient failures after the
     * bounded policy, open circuit or deadline reached.
     */
    default void dependencyUnavailable(String operation, String paymentAttemptId, String reason) {
    }
}
