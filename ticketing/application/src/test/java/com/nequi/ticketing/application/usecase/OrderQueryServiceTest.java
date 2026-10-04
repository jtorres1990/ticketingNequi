package com.nequi.ticketing.application.usecase;

import static com.nequi.ticketing.application.usecase.ApiFixture.CUSTOMER_A;
import static com.nequi.ticketing.application.usecase.ApiFixture.CUSTOMER_B;
import static com.nequi.ticketing.application.usecase.ApiFixture.EVENT_ID;
import static com.nequi.ticketing.application.usecase.ApiFixture.NOW;
import static com.nequi.ticketing.application.usecase.ApiFixture.key;
import static com.nequi.ticketing.application.usecase.Rejections.invalid;
import static com.nequi.ticketing.application.usecase.Rejections.rejected;
import static com.nequi.ticketing.application.usecase.Rejections.value;
import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.ticketing.application.error.RequestRejectedException;
import com.nequi.ticketing.application.port.in.GetOrderQuery;
import com.nequi.ticketing.application.port.in.OrderView;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.domain.error.DomainErrorCode;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.FunctionalCause;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.order.Order.PaymentOutcome;
import com.nequi.ticketing.domain.order.PurchaseRequest;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OrderQueryServiceTest {

    private static final Instant AFTER_EXPIRY = NOW.plus(Duration.ofMinutes(10));

    private ApiFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new ApiFixture();
    }

    static Stream<Arguments> ordersInEveryState() {
        return Stream.of(
                Arguments.of(OrderStatus.CREATED, null, (UnaryOperator<Order>) order -> order),
                Arguments.of(OrderStatus.CONFIRMED, null, (UnaryOperator<Order>) order ->
                        order.startPayment(NOW).recordPaymentOutcome(PaymentOutcome.APPROVED).confirm(NOW)),
                Arguments.of(OrderStatus.REJECTED, FunctionalCause.PAYMENT_DECLINED, (UnaryOperator<Order>) order ->
                        order.startPayment(NOW).recordPaymentOutcome(PaymentOutcome.DECLINED).reject()),
                Arguments.of(OrderStatus.FAILED, FunctionalCause.PROCESSING_UNAVAILABLE, (UnaryOperator<Order>) Order::failEnqueue),
                Arguments.of(OrderStatus.FAILED, FunctionalCause.PROCESSING_FAILED, (UnaryOperator<Order>) order ->
                        order.startPayment(NOW).failProcessing(NOW)),
                Arguments.of(OrderStatus.EXPIRED, FunctionalCause.RESERVATION_EXPIRED, (UnaryOperator<Order>) order ->
                        order.expire(AFTER_EXPIRY)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("ordersInEveryState")
    @DisplayName("AC-006 FR-009 MF-004 the owner sees the current state and, when not confirmed, the functional cause")
    void ownerSeesStateAndCause(OrderStatus status, FunctionalCause cause, UnaryOperator<Order> transition) {
        Order order = transition.apply(order("order-1", CUSTOMER_A));
        fixture.store.putOrder(new OrderRecord(order, NOW, NOW.plusSeconds(40), NOW));

        OrderView view = value(fixture.orderQueries.getOrder(new GetOrderQuery(CUSTOMER_A, "order-1")));

        assertThat(view.orderId()).isEqualTo("order-1");
        assertThat(view.eventId()).isEqualTo(EVENT_ID);
        assertThat(view.ticketIds()).containsExactly("A-1-1", "A-1-2");
        assertThat(view.status()).isEqualTo(status);
        assertThat(view.failureCause()).isEqualTo(cause);
        assertThat(view.reservationExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(view.createdAt()).isEqualTo(NOW);
        assertThat(view.updatedAt()).isEqualTo(NOW.plusSeconds(40));
    }

    @Test
    @DisplayName("BR-023 ALT-007 ERR-006 ERR-009 VAL-011 a foreign Order and a non-existent Order are indistinguishable")
    void foreignAndMissingOrdersAreIndistinguishable() {
        fixture.store.putOrder(new OrderRecord(order("order-1", CUSTOMER_A), NOW, NOW, NOW));

        RequestRejectedException foreign = rejected(
                fixture.orderQueries.getOrder(new GetOrderQuery(CUSTOMER_B, "order-1")), DomainErrorCode.ORDER_NOT_FOUND);
        RequestRejectedException missing = rejected(
                fixture.orderQueries.getOrder(new GetOrderQuery(CUSTOMER_B, "order-2")), DomainErrorCode.ORDER_NOT_FOUND);

        assertThat(foreign.getMessage()).isEqualTo(missing.getMessage());
        assertThat(foreign.ticketIds()).isEqualTo(missing.ticketIds());
        assertThat(foreign.retryAfter()).isEqualTo(missing.retryAfter());
    }

    @Test
    @DisplayName("ADR-025 a quarantined Order is still seen in CREATED, even after reservationExpiresAt")
    void quarantineIsInvisible() {
        Order quarantined = order("order-1", CUSTOMER_A).quarantine(NOW.plusSeconds(30), "TICKET_CONDITION_FAILED");
        fixture.store.putOrder(new OrderRecord(quarantined, NOW, NOW, NOW));
        fixture.clock.set(NOW.plus(Duration.ofHours(1)));

        OrderView view = value(fixture.orderQueries.getOrder(new GetOrderQuery(CUSTOMER_A, "order-1")));

        assertThat(view.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(view.failureCause()).isNull();
        assertThat(view.reservationExpiresAt()).isBefore(fixture.clock.now());
    }

    @Test
    @DisplayName("FR-009 BR-028 the payment reversal mark is never exposed")
    void reversalMarkIsInvisible() {
        Order expiredWithReversal = order("order-1", CUSTOMER_A).startPayment(NOW).expire(AFTER_EXPIRY);
        assertThat(expiredWithReversal.reversalPlan()).isNotNull();
        fixture.store.putOrder(new OrderRecord(expiredWithReversal, NOW, AFTER_EXPIRY, NOW));

        OrderView view = value(fixture.orderQueries.getOrder(new GetOrderQuery(CUSTOMER_A, "order-1")));

        assertThat(view.status()).isEqualTo(OrderStatus.EXPIRED);
        List<String> components = Arrays.stream(OrderView.class.getRecordComponents()).map(RecordComponent::getName).toList();
        assertThat(components).containsExactly("orderId", "eventId", "ticketIds", "status", "failureCause",
                "reservationExpiresAt", "createdAt", "updatedAt");
    }

    @Test
    @DisplayName("AC-006 the Order created by a purchase is immediately queryable by its owner")
    void purchasedOrderIsQueryable() {
        fixture.seedEvent();
        String orderId = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1")).order().orderId();

        OrderView view = value(fixture.orderQueries.getOrder(new GetOrderQuery(CUSTOMER_A, orderId)));

        assertThat(view.status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("VAL-011 the query requires the authenticated owner and an identifier")
    void requiresOwnerAndIdentifier() {
        invalid(fixture.orderQueries.getOrder(new GetOrderQuery(null, "order-1")));
        invalid(fixture.orderQueries.getOrder(new GetOrderQuery(CUSTOMER_A, "")));
    }

    private static Order order(String orderId, String customerId) {
        return Order.create(orderId, customerId,
                new PurchaseRequest(EVENT_ID, List.of("A-1-1", "A-1-2"), key(1)), NOW);
    }
}
