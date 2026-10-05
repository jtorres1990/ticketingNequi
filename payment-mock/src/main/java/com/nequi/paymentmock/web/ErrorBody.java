package com.nequi.paymentmock.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Contract schema {@code Error}. The message is generic: it may name fields but never echoes input values,
 * headers, secrets or internal details (ADR-032, PM-IV-005).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorBody(String code, String message) {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String NOT_ACCEPTABLE = "NOT_ACCEPTABLE";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    public static final String SIMULATED_DEFINITIVE_ERROR = "SIMULATED_DEFINITIVE_ERROR";
    public static final String SIMULATED_TRANSIENT_FAILURE = "SIMULATED_TRANSIENT_FAILURE";
}
