package com.nequi.ticketing.domain.shared;

import com.nequi.ticketing.domain.error.ValidationException;
import java.time.Instant;

public final class DomainChecks {

    private DomainChecks() {
    }

    public static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(field, field + " is required");
        }
        return value;
    }

    public static Instant required(Instant value, String field) {
        if (value == null) {
            throw new ValidationException(field, field + " is required");
        }
        return value;
    }

    public static <T> T required(T value, String field) {
        if (value == null) {
            throw new ValidationException(field, field + " is required");
        }
        return value;
    }
}
