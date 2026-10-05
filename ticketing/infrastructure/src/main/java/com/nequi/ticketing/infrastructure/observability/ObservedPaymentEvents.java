package com.nequi.ticketing.infrastructure.observability;

import com.nequi.ticketing.infrastructure.adapter.out.payment.PaymentEvents;

/** Payment gateway notifications (CMP-013) as metrics and WARN records; the API key is never received. */
final class ObservedPaymentEvents implements PaymentEvents {

    private final Telemetry telemetry;

    ObservedPaymentEvents(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    public void callFailed(String operation, String paymentAttemptId, String reason) {
        telemetry.counter(MetricNames.PAYMENT_CALL_FAILURES, "operation", String.valueOf(operation),
                "reason", String.valueOf(reason)).increment();
        telemetry.log().warn("payment.call.failed", "Payment Mock call failed")
                .with("operation", operation).with("paymentAttemptId", paymentAttemptId).with("reason", reason)
                .write();
    }

    @Override
    public void dependencyUnavailable(String operation, String paymentAttemptId, String reason) {
        telemetry.counter(MetricNames.PAYMENT_UNAVAILABLE, "operation", String.valueOf(operation),
                "reason", String.valueOf(reason)).increment();
        telemetry.log().warn("payment.unavailable", "Payment Mock operation ended without a provider result")
                .with("operation", operation).with("paymentAttemptId", paymentAttemptId).with("reason", reason)
                .write();
    }
}
