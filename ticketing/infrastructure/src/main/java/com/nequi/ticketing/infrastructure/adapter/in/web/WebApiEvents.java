package com.nequi.ticketing.infrastructure.adapter.in.web;

/**
 * Non-blocking observation hook of the HTTP adapter (ADR-037), wired to logs and metrics by the bootstrap
 * module (INC-010). The adapter itself writes no log on reactive threads. The hook never receives tokens,
 * authorization headers or request bodies (ADR-032).
 */
public interface WebApiEvents {

    WebApiEvents NONE = new WebApiEvents() {
    };

    /** A request ended with {@code INTERNAL_ERROR}: the failure was not classified by the translation table. */
    default void unclassifiedFailure(String traceId, Throwable error) {
    }

    /** A request was rejected by the per-subject rate limiter of API-004 (the subject is not reported). */
    default void rateLimited(String traceId) {
    }
}
