package com.nequi.ticketing.domain.order;

import static com.nequi.ticketing.domain.shared.DomainChecks.required;

public record ActiveOrderKey(String customerId, String eventId) {
    public ActiveOrderKey {
        customerId = required(customerId, "customerId");
        eventId = required(eventId, "eventId");
    }

    public static ActiveOrderKey of(Order order) {
        required(order, "order");
        return new ActiveOrderKey(order.customerId(), order.eventId());
    }
}
