package com.nequi.paymentmock.domain;

/** Rule matcher; the declaration order is the precedence order of ADR-030: orderId, ticketId, customerRef, eventId. */
public enum MatchField {
    ORDER_ID("orderId"),
    TICKET_ID("ticketId"),
    CUSTOMER_REF("customerRef"),
    EVENT_ID("eventId");

    private final String jsonName;

    MatchField(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}
