package com.nequi.ticketing.domain.order;

import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.order.Order.PaymentAttempt;
import com.nequi.ticketing.domain.order.Order.PaymentOutcome;

public final class ReversalPolicy {

    private ReversalPolicy() {
    }

    public static boolean shouldMark(OrderStatus terminalStatus, PaymentAttempt attempt) {
        if (attempt == null || terminalStatus == OrderStatus.CONFIRMED || terminalStatus == OrderStatus.REJECTED) {
            return false;
        }
        return attempt.outcome() == PaymentOutcome.APPROVED || attempt.outcome() == PaymentOutcome.UNKNOWN;
    }

    public static boolean quarantineOnTicketConditionFailure(Order order) {
        return order != null && order.status() == OrderStatus.CREATED && order.quarantinedAt() == null;
    }
}
