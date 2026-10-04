package com.nequi.ticketing.domain.error;

public sealed abstract class DomainException extends RuntimeException
        permits ValidationException, InvalidStateTransitionException {

    private final DomainErrorCode code;

    protected DomainException(DomainErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public final DomainErrorCode code() {
        return code;
    }
}
