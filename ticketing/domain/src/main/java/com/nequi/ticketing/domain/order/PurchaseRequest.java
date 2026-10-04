package com.nequi.ticketing.domain.order;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

import com.nequi.ticketing.domain.error.ValidationException;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

public record PurchaseRequest(String eventId, List<String> ticketIds, String idempotencyKey) {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{16,64}");

    public PurchaseRequest {
        eventId = required(eventId, "eventId");
        required(ticketIds, "ticketIds");
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        if (ticketIds.isEmpty() || ticketIds.size() > 10) {
            throw new ValidationException("a purchase must contain between 1 and 10 tickets");
        }
        if (ticketIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new ValidationException("ticketIds must not contain blank values");
        }
        ticketIds = List.copyOf(ticketIds);
        if (new HashSet<>(ticketIds).size() != ticketIds.size()) {
            throw new ValidationException("ticketIds must be unique");
        }
        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new ValidationException("Idempotency-Key must use 16..64 URL-safe characters");
        }
    }
}
