package com.nequi.ticketing.domain.order;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

import java.time.Duration;

/**
 * Configurable Order rules (spec §13.1, plan Annex A, IV-012, IV-015) with the approved deployed values in
 * {@link #DEPLOYED}: at most 10 Tickets per Order (FG-001, ADR-003, VAL-012) and a payment cutoff margin of 15 s
 * before {@code expiresAt} (AV-003, ADR-008, BR-029).
 *
 * <p>The maximum can be lowered but never raised above {@link #ABSOLUTE_MAXIMUM_TICKETS}: the approved
 * transaction sizes (consolidation addendum: 14 items in the reservation, 13 in the terminal transitions, 12 in
 * the payment start) are those of an Order of 10 Tickets. The Reservation duration (10 min) is not configurable
 * (spec §13.1) and stays in {@link Order#RESERVATION_DURATION}.
 */
public record OrderRules(int maximumTicketsPerOrder, Duration paymentCutoff) {

    /** Upper bound of the Tickets of an Order fixed by the approved transaction sizes (addendum). */
    public static final int ABSOLUTE_MAXIMUM_TICKETS = 10;

    public static final OrderRules DEPLOYED = new OrderRules(10, Duration.ofSeconds(15));

    public OrderRules {
        required(paymentCutoff, "paymentCutoff");
        if (maximumTicketsPerOrder < 1 || maximumTicketsPerOrder > ABSOLUTE_MAXIMUM_TICKETS) {
            throw new IllegalArgumentException("maximumTicketsPerOrder must be between 1 and "
                    + ABSOLUTE_MAXIMUM_TICKETS + " (approved transaction sizes)");
        }
        if (paymentCutoff.isNegative() || paymentCutoff.isZero()
                || paymentCutoff.compareTo(Order.RESERVATION_DURATION) >= 0) {
            throw new IllegalArgumentException("paymentCutoff must be positive and shorter than the Reservation");
        }
    }
}
