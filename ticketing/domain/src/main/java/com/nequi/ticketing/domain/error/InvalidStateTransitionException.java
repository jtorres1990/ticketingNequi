package com.nequi.ticketing.domain.error;

public final class InvalidStateTransitionException extends DomainException {

    public InvalidStateTransitionException(String message) {
        super(DomainErrorCode.INVALID_STATE_TRANSITION, message);
    }
}
