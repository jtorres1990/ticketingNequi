package com.nequi.ticketing.architecture.fixture.infrastructure.adapter.in.web;

import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.PurchaseRequest;
import java.time.Instant;
import java.util.List;

/** Control fixture: production code that applies the approved defaults instead of the configured rules. */
public final class ForbiddenDefaultRulesFixture {

    private ForbiddenDefaultRulesFixture() {
    }

    public static Order start(Order order, Instant now) {
        return order.startPayment(now);
    }

    public static PurchaseRequest request(String eventId, List<String> tickets, String key) {
        return new PurchaseRequest(eventId, tickets, key);
    }
}
