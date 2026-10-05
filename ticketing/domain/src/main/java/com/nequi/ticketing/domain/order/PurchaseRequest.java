package com.nequi.ticketing.domain.order;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

import com.nequi.ticketing.domain.error.ValidationException;
import com.nequi.ticketing.domain.shared.IdempotencyKey;
import java.util.HashSet;
import java.util.List;

/**
 * A purchase request validated by VAL-012: between 1 and the configurable maximum of Tickets (approved 10,
 * {@link OrderRules}), without blank or repeated identifiers, and a valid {@code Idempotency-Key}. The canonical
 * constructor applies the approved maximum; {@link #of(String, List, String, OrderRules)} applies the configured
 * one (IV-012).
 */
public record PurchaseRequest(String eventId, List<String> ticketIds, String idempotencyKey) {

    public PurchaseRequest {
        eventId = required(eventId, "eventId");
        required(ticketIds, "ticketIds");
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        requireTicketCount(ticketIds, OrderRules.DEPLOYED.maximumTicketsPerOrder());
        if (ticketIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new ValidationException("ticketIds", "ticketIds must not contain blank values");
        }
        ticketIds = List.copyOf(ticketIds);
        if (new HashSet<>(ticketIds).size() != ticketIds.size()) {
            throw new ValidationException("ticketIds", "ticketIds must be unique");
        }
        IdempotencyKey.validate(idempotencyKey);
    }

    /** VAL-012 with the configured maximum of Tickets per Order (IV-012). */
    public static PurchaseRequest of(String eventId, List<String> ticketIds, String idempotencyKey, OrderRules rules) {
        required(rules, "orderRules");
        required(eventId, "eventId");
        required(ticketIds, "ticketIds");
        required(idempotencyKey, "idempotencyKey");
        requireTicketCount(ticketIds, rules.maximumTicketsPerOrder());
        return new PurchaseRequest(eventId, ticketIds, idempotencyKey);
    }

    private static void requireTicketCount(List<String> ticketIds, int maximum) {
        if (ticketIds.isEmpty() || ticketIds.size() > maximum) {
            throw new ValidationException("ticketIds", "a purchase must contain between 1 and " + maximum + " tickets");
        }
    }
}
