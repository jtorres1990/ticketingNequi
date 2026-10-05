package com.nequi.paymentmock.web;

/**
 * A request that violates the contract and is detectable from the request alone: 400 {@code VALIDATION_ERROR}
 * (PM-IV-005). The message names the offending field but never contains input values.
 */
public class RequestValidationException extends RuntimeException {

    public RequestValidationException(String message) {
        super(message, null, false, false);
    }
}
