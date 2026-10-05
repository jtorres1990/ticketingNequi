package com.nequi.ticketing.domain.shared;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

import com.nequi.ticketing.domain.error.ValidationException;
import java.util.regex.Pattern;

/** Idempotency-Key format shared by purchases and Event creation (ADR-027, VAL-016). */
public final class IdempotencyKey {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{16,64}");

    private IdempotencyKey() {
    }

    public static String validate(String candidate) {
        String key = required(candidate, "idempotencyKey");
        if (!FORMAT.matcher(key).matches()) {
            throw new ValidationException("Idempotency-Key", "Idempotency-Key must use 16..64 URL-safe characters");
        }
        return key;
    }
}
