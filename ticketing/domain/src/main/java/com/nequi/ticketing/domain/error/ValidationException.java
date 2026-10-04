package com.nequi.ticketing.domain.error;

public final class ValidationException extends DomainException {

    public ValidationException(String message) {
        super(DomainErrorCode.VALIDATION_ERROR, message);
    }
}
