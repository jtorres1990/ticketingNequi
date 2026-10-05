package com.nequi.ticketing.infrastructure.observability;

import com.nequi.ticketing.infrastructure.adapter.in.web.WebApiEvents;

/** HTTP adapter notifications (CMP-001, CMP-016, CMP-017); never tokens, headers nor bodies (ADR-032). */
final class ObservedWebApiEvents implements WebApiEvents {

    private final Telemetry telemetry;

    ObservedWebApiEvents(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    public void unclassifiedFailure(String traceId, Throwable error) {
        telemetry.counter(MetricNames.HTTP_UNCLASSIFIED).increment();
        telemetry.log().error("http.unclassified.failure", "Request ended with INTERNAL_ERROR")
                .with("traceId", traceId).cause(error).write();
    }

    @Override
    public void rateLimited(String traceId) {
        telemetry.counter(MetricNames.HTTP_RATE_LIMITED).increment();
        telemetry.log().debug("http.rate.limited", "Purchase rejected by the per-subject rate limiter")
                .with("traceId", traceId).write();
    }
}
