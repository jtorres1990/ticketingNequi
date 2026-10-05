package com.nequi.ticketing.infrastructure.adapter.in.web;

import com.nequi.ticketing.domain.error.DomainErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Rejection decided by the HTTP adapter itself before reaching a use case: syntactic validation of the request
 * (with one entry per invalid field), body too large, rate limit, missing identity. It is translated by
 * {@link ApiProblems} like any other result and never carries technical detail.
 */
final class ApiRejection extends RuntimeException {

    private final DomainErrorCode code;
    private final List<FieldError> errors;
    private final Duration retryAfter;

    private ApiRejection(DomainErrorCode code, List<FieldError> errors, Duration retryAfter) {
        super(code.name(), null, false, false);
        this.code = code;
        this.errors = List.copyOf(errors);
        this.retryAfter = retryAfter;
    }

    static ApiRejection invalid(List<FieldError> errors) {
        if (errors.isEmpty()) {
            throw new IllegalArgumentException("a validation rejection needs at least one field error");
        }
        return new ApiRejection(DomainErrorCode.VALIDATION_ERROR, errors, null);
    }

    static ApiRejection invalid(String field, String reason) {
        return invalid(List.of(new FieldError(field, reason)));
    }

    static ApiRejection payloadTooLarge() {
        return new ApiRejection(DomainErrorCode.PAYLOAD_TOO_LARGE, List.of(), null);
    }

    static ApiRejection rateLimited(Duration retryAfter) {
        return new ApiRejection(DomainErrorCode.RATE_LIMITED, List.of(), Objects.requireNonNull(retryAfter, "retryAfter"));
    }

    static ApiRejection unauthenticated() {
        return new ApiRejection(DomainErrorCode.UNAUTHENTICATED, List.of(), null);
    }

    DomainErrorCode code() {
        return code;
    }

    List<FieldError> errors() {
        return errors;
    }

    Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /** Entry of the Problem Details {@code errors} member of a {@code VALIDATION_ERROR}. */
    record FieldError(String field, String reason) {

        FieldError {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
