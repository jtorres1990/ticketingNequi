package com.nequi.ticketing.domain.error;

import java.util.Optional;

/**
 * Input validation failure ({@code VALIDATION_ERROR}). The optional {@code field} names the offending input in
 * the terms of the HTTP contract (for example {@code ticketIds}, {@code inventory.sections}, {@code cursor}) so
 * that the inbound adapter can fill the Problem Details {@code errors} member (ADR-035). The message is
 * functional and never carries technical detail.
 */
public final class ValidationException extends DomainException {

    private final String field;

    public ValidationException(String message) {
        this(null, message);
    }

    public ValidationException(String field, String message) {
        super(DomainErrorCode.VALIDATION_ERROR, message);
        this.field = field;
    }

    public Optional<String> field() {
        return Optional.ofNullable(field);
    }
}
