package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.instant;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.millis;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.s;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.strings;

import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.port.out.PaymentLease;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.FunctionalCause;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.order.Order.PaymentAttempt;
import com.nequi.ticketing.domain.order.Order.PaymentOutcome;
import com.nequi.ticketing.domain.order.Order.Reservation;
import com.nequi.ticketing.domain.order.ReversalPlan;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Order item of {@code ticketing.data-model.v2.md} §3 ({@code ORDER#<orderId>} / {@code #META}) with its
 * Reservation, enqueue marker, PaymentAttempt and lease, payment reversal mark, quarantine and the
 * {@code GSI3}/{@code GSI4} keys. Technical attributes are never exposed by API-005 (ADR-025).
 */
final class OrderItems {

    static final String ENTITY = "ORDER";
    static final String ENTITY_TYPE = "entityType";
    static final String ORDER_ID = "orderId";
    static final String CUSTOMER_ID = "customerId";
    static final String EVENT_ID = "eventId";
    static final String TICKET_IDS = "ticketIds";
    static final String STATUS = "status";
    static final String FAILURE_CAUSE = "failureCause";
    static final String CREATED_AT = "createdAt";
    static final String CREATED_AT_MS = "createdAtMs";
    static final String UPDATED_AT = "updatedAt";
    static final String TERMINAL_AT = "terminalAt";
    static final String RESERVATION_ID = "reservationId";
    static final String RESERVED_AT = "reservedAt";
    static final String EXPIRES_AT = "expiresAt";
    static final String EXPIRES_AT_MS = "expiresAtMs";
    static final String ENQUEUED_AT = "enqueuedAt";
    static final String PAYMENT_ATTEMPT_ID = "paymentAttemptId";
    static final String PAYMENT_ATTEMPT_NO = "paymentAttemptNo";
    static final String PAYMENT_STARTED_AT = "paymentStartedAt";
    static final String PAYMENT_OUTCOME = "paymentOutcome";
    static final String PAYMENT_COMPLETED_AT = "paymentCompletedAt";
    static final String PAYMENT_PROVIDER_REF = "paymentProviderRef";
    static final String PAYMENT_LEASE_OWNER = "paymentLeaseOwner";
    static final String PAYMENT_LEASE_UNTIL_MS = "paymentLeaseUntilMs";
    static final String REVERSAL_PENDING = "paymentReversalPending";
    static final String REVERSAL_REQUESTED_AT = "paymentReversalRequestedAt";
    static final String REVERSAL_ATTEMPTS = "paymentReversalAttempts";
    static final String REVERSAL_NEXT_ATTEMPT_AT_MS = "paymentReversalNextAttemptAtMs";
    static final String REVERSAL_COMPLETED_AT = "paymentReversalCompletedAt";
    static final String REVERSAL_EXHAUSTED_AT = "paymentReversalExhaustedAt";
    static final String QUARANTINED_AT = "quarantinedAt";
    static final String QUARANTINE_REASON = "quarantineReason";
    static final String GSI3PK = "GSI3PK";
    static final String GSI3SK = "GSI3SK";
    static final String GSI4PK = "GSI4PK";
    static final String GSI4SK = "GSI4SK";

    private OrderItems() {
    }

    /** AP-008: new Order in {@code CREATED}, in the {@code RESV#} range of {@code GSI3} and in {@code GSI4}. */
    static Map<String, AttributeValue> newOrder(Order order) {
        Reservation reservation = order.reservation();
        Instant createdAt = reservation.reservedAt();
        Map<String, AttributeValue> item = new HashMap<>(Keys.meta(Keys.order(order.orderId())));
        item.put(ENTITY_TYPE, s(ENTITY));
        item.put(ORDER_ID, s(order.orderId()));
        item.put(CUSTOMER_ID, s(order.customerId()));
        item.put(EVENT_ID, s(order.eventId()));
        item.put(TICKET_IDS, strings(order.ticketIds()));
        item.put(STATUS, s(order.status().name()));
        item.put(CREATED_AT, instant(createdAt));
        item.put(CREATED_AT_MS, millis(createdAt));
        item.put(UPDATED_AT, instant(createdAt));
        item.put(RESERVATION_ID, s(reservation.reservationId()));
        item.put(RESERVED_AT, instant(reservation.reservedAt()));
        item.put(EXPIRES_AT, instant(reservation.expiresAt()));
        item.put(EXPIRES_AT_MS, millis(reservation.expiresAt()));
        item.put(GSI3PK, s(Keys.reservations(order.orderId())));
        item.put(GSI3SK, s(Keys.millisSort(reservation.expiresAt(), order.orderId())));
        item.put(GSI4PK, s(Keys.pendingEnqueue(order.orderId())));
        item.put(GSI4SK, s(Keys.millisSort(createdAt, order.orderId())));
        return item;
    }

    static OrderRecord record(Map<String, AttributeValue> item) {
        String orderId = Attributes.string(item, ORDER_ID);
        String paymentAttemptId = Attributes.optionalString(item, PAYMENT_ATTEMPT_ID);
        PaymentAttempt attempt = paymentAttemptId == null ? null : new PaymentAttempt(
                paymentAttemptId,
                PaymentOutcome.valueOf(Attributes.string(item, PAYMENT_OUTCOME)),
                Attributes.instant(item, PAYMENT_STARTED_AT));
        String cause = Attributes.optionalString(item, FAILURE_CAUSE);
        Order order = new Order(
                orderId,
                Attributes.string(item, CUSTOMER_ID),
                Attributes.string(item, EVENT_ID),
                Attributes.stringList(item, TICKET_IDS),
                OrderStatus.valueOf(Attributes.string(item, STATUS)),
                cause == null ? null : FunctionalCause.valueOf(cause),
                new Reservation(
                        Attributes.string(item, RESERVATION_ID),
                        Attributes.instant(item, RESERVED_AT),
                        Attributes.instant(item, EXPIRES_AT)),
                attempt,
                Attributes.optionalInstant(item, QUARANTINED_AT),
                Attributes.optionalString(item, QUARANTINE_REASON),
                reversal(item, paymentAttemptId));
        String leaseOwner = Attributes.optionalString(item, PAYMENT_LEASE_OWNER);
        PaymentLease lease = leaseOwner == null ? null
                : new PaymentLease(leaseOwner, Attributes.optionalMillis(item, PAYMENT_LEASE_UNTIL_MS));
        return new OrderRecord(
                order,
                Attributes.instant(item, CREATED_AT),
                Attributes.instant(item, UPDATED_AT),
                Attributes.optionalInstant(item, ENQUEUED_AT),
                lease);
    }

    private static ReversalPlan reversal(Map<String, AttributeValue> item, String paymentAttemptId) {
        Instant requestedAt = Attributes.optionalInstant(item, REVERSAL_REQUESTED_AT);
        if (requestedAt == null) {
            return null;
        }
        return new ReversalPlan(
                paymentAttemptId,
                Attributes.integer(item, REVERSAL_ATTEMPTS),
                requestedAt,
                Attributes.optionalMillis(item, REVERSAL_NEXT_ATTEMPT_AT_MS),
                Attributes.present(item, REVERSAL_EXHAUSTED_AT),
                Attributes.optionalInstant(item, REVERSAL_COMPLETED_AT));
    }
}
