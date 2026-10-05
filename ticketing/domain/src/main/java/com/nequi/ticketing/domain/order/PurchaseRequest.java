package com.nequi.ticketing.domain.order;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

import com.nequi.ticketing.domain.error.ValidationException;
import com.nequi.ticketing.domain.shared.IdempotencyKey;
import java.util.HashSet;
import java.util.List;

public record PurchaseRequest(String eventId, List<String> ticketIds, String idempotencyKey) {

    public PurchaseRequest {
        eventId = required(eventId, "eventId");
        required(ticketIds, "ticketIds");
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        if (ticketIds.isEmpty() || ticketIds.size() > 10) {
            throw new ValidationException("ticketIds", "a purchase must contain between 1 and 10 tickets");
        }
        if (ticketIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new ValidationException("ticketIds", "ticketIds must not contain blank values");
        }
        ticketIds = List.copyOf(ticketIds);
        if (new HashSet<>(ticketIds).size() != ticketIds.size()) {
            throw new ValidationException("ticketIds", "ticketIds must be unique");
        }
        IdempotencyKey.validate(idempotencyKey);
    }
}
