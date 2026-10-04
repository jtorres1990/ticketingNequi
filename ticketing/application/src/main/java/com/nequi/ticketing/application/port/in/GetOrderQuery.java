package com.nequi.ticketing.application.port.in;

/** API-005 query; {@code customerId} is the JWT subject of the authenticated CUSTOMER (VAL-011). */
public record GetOrderQuery(String customerId, String orderId) {
}
