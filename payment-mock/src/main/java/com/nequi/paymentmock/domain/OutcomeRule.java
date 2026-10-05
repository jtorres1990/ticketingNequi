package com.nequi.paymentmock.domain;

import java.util.Objects;

/**
 * Outcome rule (AV-004): exactly one matcher and one behaviour. {@code sequence} is the process-wide creation
 * counter: it defines recency and {@code ruleId = "rule-" + sequence} (PM-IV-006).
 */
public record OutcomeRule(String ruleId, long sequence, MatchField field, String value, Behaviour behaviour) {

    public OutcomeRule {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(behaviour, "behaviour");
    }

    public static OutcomeRule create(long sequence, MatchField field, String value, Behaviour behaviour) {
        return new OutcomeRule("rule-" + sequence, sequence, field, value, behaviour);
    }

    /** Exact, case-sensitive match; {@code ticketId} matches when it is one of the requested Tickets. */
    public boolean matches(AttemptPayload payload) {
        return switch (field) {
            case ORDER_ID -> value.equals(payload.orderId());
            case TICKET_ID -> payload.ticketIds().contains(value);
            case CUSTOMER_REF -> value.equals(payload.customerRef());
            case EVENT_ID -> value.equals(payload.eventId());
        };
    }
}
