package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.error.RequestRejectedException;
import com.nequi.ticketing.application.port.in.GetOrderQuery;
import com.nequi.ticketing.application.port.in.GetOrderUseCase;
import com.nequi.ticketing.application.port.in.OrderView;
import com.nequi.ticketing.application.port.out.OrderReader;
import com.nequi.ticketing.domain.shared.DomainChecks;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * CMP-006 Order query (FR-009, BR-023, VAL-011, ALT-007, ERR-006, ERR-009). Reads the Order item alone
 * with strong consistency (AP-010); a non-existent Order and an Order of another customer follow the
 * same path and produce the same {@code ORDER_NOT_FOUND}. Quarantine and the payment reversal mark are
 * not exposed: a quarantined Order is still seen in {@code CREATED} (ADR-025).
 */
public final class OrderQueryService implements GetOrderUseCase {

    private final OrderReader orderReader;

    public OrderQueryService(OrderReader orderReader) {
        this.orderReader = Objects.requireNonNull(orderReader, "orderReader");
    }

    @Override
    public Mono<OrderView> getOrder(GetOrderQuery query) {
        return Mono.defer(() -> {
            Objects.requireNonNull(query, "query");
            String customerId = DomainChecks.required(query.customerId(), "customerId");
            String orderId = DomainChecks.required(query.orderId(), "orderId");
            return orderReader.findById(orderId)
                    .filter(record -> record.order().customerId().equals(customerId))
                    .map(OrderViews::of)
                    .switchIfEmpty(Mono.error(RequestRejectedException::orderNotFound));
        });
    }
}
