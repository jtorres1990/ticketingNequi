package com.nequi.ticketing.application.usecase;

import static com.nequi.ticketing.application.usecase.ApiFixture.CORRELATION;
import static com.nequi.ticketing.application.usecase.ApiFixture.CUSTOMER_A;
import static com.nequi.ticketing.application.usecase.ApiFixture.CUSTOMER_B;
import static com.nequi.ticketing.application.usecase.ApiFixture.DEFINITION;
import static com.nequi.ticketing.application.usecase.ApiFixture.EVENT_ID;
import static com.nequi.ticketing.application.usecase.ApiFixture.NOW;
import static com.nequi.ticketing.application.usecase.ApiFixture.command;
import static com.nequi.ticketing.application.usecase.ApiFixture.key;
import static com.nequi.ticketing.application.usecase.Rejections.invalid;
import static com.nequi.ticketing.application.usecase.Rejections.rejected;
import static com.nequi.ticketing.application.usecase.Rejections.value;
import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.ticketing.application.error.RequestRejectedException;
import com.nequi.ticketing.application.port.in.OrderView;
import com.nequi.ticketing.application.port.in.PurchaseResult;
import com.nequi.ticketing.application.port.in.StartPurchaseCommand;
import com.nequi.ticketing.application.port.out.FailedItem;
import com.nequi.ticketing.application.port.out.IdempotencyRecord;
import com.nequi.ticketing.application.port.out.ItemFailure;
import com.nequi.ticketing.application.port.out.OrderProcessingRequested;
import com.nequi.ticketing.application.port.out.OrderRecord;
import com.nequi.ticketing.application.port.out.PublishResult;
import com.nequi.ticketing.application.port.out.PublisherAvailability;
import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.application.testdouble.SequentialIdGenerator;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.audit.AuditRecord.ActorType;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.error.DomainErrorCode;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.InventoryLimits;
import com.nequi.ticketing.domain.order.Order;
import com.nequi.ticketing.domain.order.Order.FunctionalCause;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.order.Order.PaymentOutcome;
import com.nequi.ticketing.domain.order.PurchaseRequest;
import com.nequi.ticketing.domain.shared.ContentHash;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PurchaseServiceTest {

    private ApiFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new ApiFixture();
        fixture.seedEvent();
    }

    // ------------------------------------------------------------------ accepted purchase

    @Test
    @DisplayName("AC-004 ST-001 ST-006 reserves every ticket atomically, starts the ten minute Reservation and creates the Order")
    void reservesAllTicketsAndCreatesTheOrder() {
        PurchaseResult result = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1", "A-1-2", "B-1-1"));

        String orderId = SequentialIdGenerator.orderId(1);
        OrderView order = result.order();
        assertThat(result.replayed()).isFalse();
        assertThat(order.orderId()).isEqualTo(orderId);
        assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.failureCause()).isNull();
        assertThat(order.ticketIds()).containsExactly("A-1-1", "A-1-2", "B-1-1");
        assertThat(order.reservationExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(order.createdAt()).isEqualTo(NOW);
        assertThat(order.updatedAt()).isEqualTo(NOW);
        for (String ticketId : List.of("A-1-1", "A-1-2", "B-1-1")) {
            assertThat(fixture.store.ticket(EVENT_ID, ticketId).state()).isEqualTo(TicketState.RESERVED);
            assertThat(fixture.store.ticket(EVENT_ID, ticketId).orderId()).isEqualTo(orderId);
        }
        assertThat(fixture.store.ticket(EVENT_ID, "A-1-3").state()).isEqualTo(TicketState.AVAILABLE);
        assertThat(fixture.store.activeLock(CUSTOMER_A, EVENT_ID)).contains(orderId);
        IdempotencyRecord idempotency = fixture.store.purchaseIdempotency(CUSTOMER_A, key(1)).orElseThrow();
        assertThat(idempotency.resourceId()).isEqualTo(orderId);
        assertThat(idempotency.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(idempotency.requestHash()).isEqualTo(ContentHash.purchase(
                new PurchaseRequest(EVENT_ID, List.of("B-1-1", "A-1-2", "A-1-1"), key(1))));
    }

    @Test
    @DisplayName("AC-003 FR-005 FR-006 publishes MSG-001, marks enqueuedAt and returns the Order ID without waiting for payment")
    void publishesTheOrderAndReturnsItsIdentifier() {
        PurchaseResult result = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));

        assertThat(fixture.orderPublisher.published()).containsExactly(new OrderProcessingRequested(
                result.order().orderId(), EVENT_ID, NOW, CORRELATION, OrderProcessingRequested.Publisher.API));
        OrderRecord stored = fixture.store.order(result.order().orderId()).orElseThrow();
        assertThat(stored.enqueuedAt()).isEqualTo(NOW);
        assertThat(stored.order().paymentAttempt()).isNull();
    }

    @Test
    @DisplayName("FR-014 BR-010 ADR-031 audits the reservation with the CUSTOMER as actor in the same write")
    void auditsTheReservation() {
        PurchaseResult result = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1", "A-1-2"));

        AuditRecord audit = fixture.store.audits().getFirst();
        assertThat(fixture.store.audits()).hasSize(1);
        assertThat(audit.code()).isEqualTo(AuditCode.RESERVATION_CREATED);
        assertThat(audit.transitionIds()).containsExactly("ST-001", "ST-006");
        assertThat(audit.orderId()).isEqualTo(result.order().orderId());
        assertThat(audit.orderTo()).isEqualTo(OrderStatus.CREATED);
        assertThat(audit.ticketFrom()).isEqualTo(TicketState.AVAILABLE);
        assertThat(audit.ticketTo()).isEqualTo(TicketState.RESERVED);
        assertThat(audit.actor().type()).isEqualTo(ActorType.CUSTOMER);
        assertThat(audit.actor().id()).isEqualTo(CUSTOMER_A);
        assertThat(audit.correlationId()).isEqualTo(CORRELATION);
        assertThat(audit.occurredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("ADR-026 a failed enqueuedAt marker never fails the purchase; the sweep covers it")
    void ignoresTheEnqueueMarkerFailure() {
        fixture.store.failMarkEnqueued(true);

        PurchaseResult result = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));

        assertThat(result.order().status()).isEqualTo(OrderStatus.CREATED);
        assertThat(fixture.store.order(result.order().orderId()).orElseThrow().enqueuedAt()).isNull();
    }

    // ------------------------------------------------------------------ BR-031 (1) validation

    static Stream<Arguments> invalidPurchases() {
        List<String> eleven = IntStream.rangeClosed(1, 11).mapToObj(seat -> "A-1-" + seat).toList();
        return Stream.of(
                Arguments.of("zero tickets", command(CUSTOMER_A, key(1))),
                Arguments.of("more than ten tickets",
                        new StartPurchaseCommand(CUSTOMER_A, EVENT_ID, eleven, key(1), CORRELATION)),
                Arguments.of("repeated tickets", command(CUSTOMER_A, key(1), "A-1-1", "A-1-1")),
                Arguments.of("invalid Idempotency-Key", command(CUSTOMER_A, "short", "A-1-1")),
                Arguments.of("missing Idempotency-Key", command(CUSTOMER_A, null, "A-1-1")),
                Arguments.of("missing customer identity", command(null, key(1), "A-1-1")),
                Arguments.of("missing Event", new StartPurchaseCommand(CUSTOMER_A, null, List.of("A-1-1"), key(1), CORRELATION)),
                Arguments.of("missing ticket list", new StartPurchaseCommand(CUSTOMER_A, EVENT_ID, null, key(1), CORRELATION)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPurchases")
    @DisplayName("AC-032 ERR-010 VAL-012 rejects invalid purchases before reserving and writes nothing")
    void rejectsInvalidPurchasesWithoutEffects(String scenario, StartPurchaseCommand command) {
        invalid(fixture.purchases.startPurchase(command));

        assertNothingWasCreated();
    }

    // ------------------------------------------------------------------ BR-031 (2) idempotency

    @Test
    @DisplayName("BR-019 ADR-027 a replay with the same key and content returns the existing Order in its current state")
    void replaysAnAcceptedPurchase() {
        PurchaseResult first = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1", "A-1-2"));
        OrderRecord stored = fixture.store.order(first.order().orderId()).orElseThrow();
        Order confirmed = stored.order().startPayment(NOW).recordPaymentOutcome(PaymentOutcome.APPROVED).confirm(NOW);
        fixture.store.putOrder(new OrderRecord(confirmed, stored.createdAt(), NOW.plusSeconds(30), stored.enqueuedAt()));

        PurchaseResult replay = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-2", "A-1-1"));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.order().orderId()).isEqualTo(first.order().orderId());
        assertThat(replay.order().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(replay.order().updatedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(fixture.store.orders()).hasSize(1);
        assertThat(fixture.store.reservationAttempts()).isEqualTo(1);
        assertThat(fixture.orderPublisher.published()).hasSize(1);
    }

    @Test
    @DisplayName("ERR-021 ADR-027 the same key with different content is IDEMPOTENCY_KEY_REUSED without effects")
    void rejectsAReusedKey() {
        value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));

        rejected(fixture.purchase(CUSTOMER_A, key(1), "A-1-2"), DomainErrorCode.IDEMPOTENCY_KEY_REUSED);

        assertThat(fixture.store.orders()).hasSize(1);
        assertThat(fixture.store.ticket(EVENT_ID, "A-1-2").state()).isEqualTo(TicketState.AVAILABLE);
        assertThat(fixture.orderPublisher.published()).hasSize(1);
    }

    @Test
    @DisplayName("ADR-027 the idempotency key is bound to the CUSTOMER: another customer with the same key is a new purchase")
    void bindsTheKeyToTheCustomer() {
        PurchaseResult first = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));
        PurchaseResult second = value(fixture.purchase(CUSTOMER_B, key(1), "A-1-2"));

        assertThat(second.replayed()).isFalse();
        assertThat(second.order().orderId()).isNotEqualTo(first.order().orderId());
    }

    @Test
    @DisplayName("ADR-026 ADR-027 a replay republishes MSG-001 for a CREATED Order without enqueuedAt and marks it")
    void replayRepublishesAPendingOrder() {
        fixture.store.failMarkEnqueued(true);
        PurchaseResult first = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));
        fixture.store.failMarkEnqueued(false);
        fixture.clock.advance(Duration.ofSeconds(5));

        PurchaseResult replay = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));

        assertThat(replay.replayed()).isTrue();
        assertThat(fixture.orderPublisher.published()).hasSize(2);
        assertThat(fixture.store.order(first.order().orderId()).orElseThrow().enqueuedAt())
                .isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    @DisplayName("ADR-027 a replay of an enqueued Order does not republish")
    void replayDoesNotRepublishAnEnqueuedOrder() {
        value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));

        value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));

        assertThat(fixture.orderPublisher.published()).hasSize(1);
    }

    @Test
    @DisplayName("ADR-027 ADR-035 a replay skips republication while publication is unavailable and still answers")
    void replaySkipsRepublicationWhilePublicationIsUnavailable() {
        fixture.store.failMarkEnqueued(true);
        value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));
        fixture.orderPublisher.setAvailability(PublisherAvailability.unavailable(Duration.ofSeconds(4)));

        PurchaseResult replay = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));

        assertThat(replay.replayed()).isTrue();
        assertThat(fixture.orderPublisher.published()).hasSize(1);
    }

    @Test
    @DisplayName("ADR-027 a failed republication on a replay is ignored and the Order stays pending for the sweep")
    void replayIgnoresAFailedRepublication() {
        fixture.store.failMarkEnqueued(true);
        PurchaseResult first = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));
        fixture.store.failMarkEnqueued(false);
        fixture.orderPublisher.script(PublishResult.FAILED);

        PurchaseResult replay = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));

        assertThat(replay.order().status()).isEqualTo(OrderStatus.CREATED);
        assertThat(fixture.store.order(first.order().orderId()).orElseThrow().enqueuedAt()).isNull();
    }

    @Test
    @DisplayName("ADR-025 ADR-027 a replay of a quarantined Order neither republishes nor exposes the quarantine")
    void replayOfAQuarantinedOrder() {
        fixture.store.failMarkEnqueued(true);
        PurchaseResult first = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));
        fixture.store.failMarkEnqueued(false);
        OrderRecord stored = fixture.store.order(first.order().orderId()).orElseThrow();
        fixture.store.putOrder(new OrderRecord(stored.order().quarantine(NOW, "manual review"),
                stored.createdAt(), stored.updatedAt(), null));

        PurchaseResult replay = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));

        assertThat(replay.order().status()).isEqualTo(OrderStatus.CREATED);
        assertThat(fixture.orderPublisher.published()).hasSize(1);
    }

    @Test
    @DisplayName("AC-036 BR-019 FG-004 a purchase rejected for unavailability is reevaluated on repetition and registers the key")
    void reevaluatesARejectedPurchase() {
        fixture.store.setTicket(EVENT_ID, "A-1-1", TicketState.RESERVED, "other-order");
        rejected(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"), DomainErrorCode.TICKETS_UNAVAILABLE);
        assertThat(fixture.store.purchaseIdempotency(CUSTOMER_A, key(1))).isEmpty();
        fixture.store.setTicket(EVENT_ID, "A-1-1", TicketState.AVAILABLE, null);

        PurchaseResult retried = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));

        assertThat(retried.replayed()).isFalse();
        assertThat(retried.order().status()).isEqualTo(OrderStatus.CREATED);
        assertThat(fixture.store.purchaseIdempotency(CUSTOMER_A, key(1)).orElseThrow().resourceId())
                .isEqualTo(retried.order().orderId());
    }

    @Test
    @DisplayName("ADR-027 an idempotency record without its Order is reported as an internal inconsistency")
    void idempotencyRecordWithoutOrder() {
        PurchaseRequest request = new PurchaseRequest(EVENT_ID, List.of("A-1-1"), key(9));
        fixture.store.putPurchaseIdempotency(new IdempotencyRecord(CUSTOMER_A, key(9), "missing-order",
                ContentHash.purchase(request), NOW, NOW.plus(Duration.ofHours(24))));

        reactor.test.StepVerifier.create(fixture.purchase(CUSTOMER_A, key(9), "A-1-1"))
                .expectError(IllegalStateException.class)
                .verify(Duration.ofSeconds(5));
    }

    // ------------------------------------------------------------------ BR-031 (3)..(6)

    @Test
    @DisplayName("ERR-013 BR-027 VAL-014 an Event that does not exist is EVENT_NOT_FOUND")
    void rejectsAMissingEvent() {
        StartPurchaseCommand command = new StartPurchaseCommand(
                CUSTOMER_A, "e0000000-0000-4000-8000-000000000999", List.of("A-1-1"), key(1), CORRELATION);

        rejected(fixture.purchases.startPurchase(command), DomainErrorCode.EVENT_NOT_FOUND);

        assertNothingWasCreated();
    }

    @Test
    @DisplayName("AC-039 ERR-013 BR-027 an Event in PROVISIONING or FAILED is treated as non-existent")
    void rejectsEventsThatAreNotEnabled() {
        Event provisioning = Event.create("e-prov", "P", "V", NOW.plus(Duration.ofDays(5)), 25, DEFINITION,
                NOW, InventoryLimits.DEPLOYED);
        fixture.store.seedSnapshot(new ProvisioningSnapshot(provisioning, NOW, 0, null, null));
        Event failed = Event.create("e-fail", "F", "V", NOW.plus(Duration.ofDays(5)), 25, DEFINITION,
                NOW, InventoryLimits.DEPLOYED).fail();
        fixture.store.seedSnapshot(new ProvisioningSnapshot(failed, NOW, 0, null, NOW));

        for (String eventId : List.of("e-prov", "e-fail")) {
            StartPurchaseCommand command = new StartPurchaseCommand(CUSTOMER_A, eventId, List.of("A-1-1"), key(1), CORRELATION);
            rejected(fixture.purchases.startPurchase(command), DomainErrorCode.EVENT_NOT_FOUND);
        }
        assertNothingWasCreated();
    }

    @Test
    @DisplayName("AC-037 ERR-012 BR-025 a purchase at or after startsAt is EVENT_NOT_ON_SALE and creates nothing")
    void rejectsAPastEvent() {
        fixture.clock.set(NOW.plus(Duration.ofDays(10)));

        rejected(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"), DomainErrorCode.EVENT_NOT_ON_SALE);

        fixture.clock.advance(Duration.ofSeconds(1));
        rejected(fixture.purchase(CUSTOMER_A, key(2), "A-1-1"), DomainErrorCode.EVENT_NOT_ON_SALE);
        assertNothingWasCreated();
    }

    @Test
    @DisplayName("ADR-023 tickets outside the Event definition are UNKNOWN_TICKETS before any transaction")
    void rejectsUnknownTicketsBeforeReserving() {
        RequestRejectedException rejection = rejected(
                fixture.purchase(CUSTOMER_A, key(1), "Z-9-9", "A-1-1", "A-1-11"), DomainErrorCode.UNKNOWN_TICKETS);

        assertThat(rejection.ticketIds()).containsExactly("A-1-11", "Z-9-9");
        assertThat(fixture.store.reservationAttempts()).isZero();
        assertNothingWasCreated();
    }

    @Test
    @DisplayName("AC-046 ERR-015 ALT-010 FR-024 with the queue unavailable the purchase is SERVICE_UNAVAILABLE before reserving")
    void rejectsWhenPublicationIsUnavailable() {
        fixture.orderPublisher.setAvailability(PublisherAvailability.unavailable(Duration.ofSeconds(7)));

        RequestRejectedException rejection = rejected(
                fixture.purchase(CUSTOMER_A, key(1), "A-1-1"), DomainErrorCode.SERVICE_UNAVAILABLE);

        assertThat(rejection.retryAfter()).contains(Duration.ofSeconds(7));
        assertThat(fixture.store.reservationAttempts()).isZero();
        assertNothingWasCreated();
    }

    @Test
    @DisplayName("AC-046 IV-004 the Retry-After of an open publication circuit is at least one second")
    void retryAfterHasAOneSecondMinimum() {
        fixture.orderPublisher.setAvailability(PublisherAvailability.unavailable(Duration.ofMillis(200)));

        RequestRejectedException rejection = rejected(
                fixture.purchase(CUSTOMER_A, key(1), "A-1-1"), DomainErrorCode.SERVICE_UNAVAILABLE);

        assertThat(rejection.retryAfter()).contains(Duration.ofSeconds(1));
    }

    // ------------------------------------------------------------------ BR-031 (7)..(8) from the transaction

    @Test
    @DisplayName("AC-043 ERR-014 FR-021 BR-024 a second purchase with another key while an Order is CREATED is ACTIVE_ORDER_EXISTS")
    void rejectsASecondActiveOrder() {
        value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));

        rejected(fixture.purchase(CUSTOMER_A, key(2), "A-2-1"), DomainErrorCode.ACTIVE_ORDER_EXISTS);

        assertThat(fixture.store.orders()).hasSize(1);
        assertThat(fixture.store.ticket(EVENT_ID, "A-2-1").state()).isEqualTo(TicketState.AVAILABLE);
        assertThat(fixture.store.purchaseIdempotency(CUSTOMER_A, key(2))).isEmpty();
        assertThat(fixture.orderPublisher.published()).hasSize(1);
    }

    @Test
    @DisplayName("ADR-032 the active Order rule applies per Event: another Event is not blocked")
    void activeOrderRuleIsPerEvent() {
        value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));
        fixture.store.seedEnabledEvent("e-other", "Other", NOW.plus(Duration.ofDays(3)), DEFINITION);

        PurchaseResult other = value(fixture.purchases.startPurchase(
                new StartPurchaseCommand(CUSTOMER_A, "e-other", List.of("A-1-1"), key(2), CORRELATION)));

        assertThat(other.order().status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("AC-045 ALT-013 after the active Order reaches a terminal state the CUSTOMER can buy again")
    void allowsANewPurchaseAfterATerminalOrder() {
        fixture.orderPublisher.script(PublishResult.FAILED);
        PurchaseResult failed = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));
        assertThat(failed.order().status()).isEqualTo(OrderStatus.FAILED);

        PurchaseResult again = value(fixture.purchase(CUSTOMER_A, key(2), "A-1-1"));

        assertThat(again.order().status()).isEqualTo(OrderStatus.CREATED);
        assertThat(fixture.store.activeLock(CUSTOMER_A, EVENT_ID)).contains(again.order().orderId());
    }

    @Test
    @DisplayName("BR-024 §12.1 ADR-025 a quarantined Order keeps the active Order lock")
    void quarantinedOrderKeepsTheLock() {
        PurchaseResult first = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));
        OrderRecord stored = fixture.store.order(first.order().orderId()).orElseThrow();
        fixture.store.putOrder(new OrderRecord(stored.order().quarantine(NOW, "review"),
                stored.createdAt(), stored.updatedAt(), stored.enqueuedAt()));

        rejected(fixture.purchase(CUSTOMER_A, key(2), "A-2-2"), DomainErrorCode.ACTIVE_ORDER_EXISTS);
    }

    @Test
    @DisplayName("AC-016 ALT-002 ERR-002 FR-016 one unavailable ticket rejects the whole request and reserves none of the others")
    void rejectsTheWholeRequestWhenOneTicketIsUnavailable() {
        fixture.store.setTicket(EVENT_ID, "A-1-2", TicketState.SOLD, "previous-order");

        RequestRejectedException rejection = rejected(
                fixture.purchase(CUSTOMER_A, key(1), "A-1-1", "A-1-2", "A-1-3"), DomainErrorCode.TICKETS_UNAVAILABLE);

        assertThat(rejection.ticketIds()).containsExactly("A-1-2");
        assertThat(fixture.store.ticket(EVENT_ID, "A-1-1").state()).isEqualTo(TicketState.AVAILABLE);
        assertThat(fixture.store.ticket(EVENT_ID, "A-1-3").state()).isEqualTo(TicketState.AVAILABLE);
        assertThat(fixture.store.orders()).isEmpty();
        assertThat(fixture.store.activeLock(CUSTOMER_A, EVENT_ID)).isEmpty();
        assertThat(fixture.store.purchaseIdempotency(CUSTOMER_A, key(1))).isEmpty();
        assertThat(fixture.orderPublisher.published()).isEmpty();
    }

    @Test
    @DisplayName("BR-007 VAL-010 a COMPLIMENTARY ticket is never available for purchase")
    void complimentaryTicketIsUnavailable() {
        RequestRejectedException rejection = rejected(
                fixture.purchase(CUSTOMER_A, key(1), "B-1-5"), DomainErrorCode.TICKETS_UNAVAILABLE);

        assertThat(rejection.ticketIds()).containsExactly("B-1-5");
        assertThat(fixture.store.ticket(EVENT_ID, "B-1-5").state()).isEqualTo(TicketState.COMPLIMENTARY);
    }

    @Test
    @DisplayName("ADR-023 a Ticket item missing in the transaction is UNKNOWN_TICKETS")
    void missingTicketItemIsUnknown() {
        fixture.store.scriptReservations(TransactionOutcome.cancelled(List.of(
                ItemFailure.ticket(FailedItem.TICKET_MISSING, "A-1-1"),
                ItemFailure.ticket(FailedItem.TICKET_STATE, "A-1-2"))));

        RequestRejectedException rejection = rejected(
                fixture.purchase(CUSTOMER_A, key(1), "A-1-1", "A-1-2"), DomainErrorCode.UNKNOWN_TICKETS);

        assertThat(rejection.ticketIds()).containsExactly("A-1-1");
    }

    @Test
    @DisplayName("ADR-023 ADR-035 a persistent transactional conflict is SERVICE_UNAVAILABLE with Retry-After and persists nothing")
    void persistentConflictIsServiceUnavailable() {
        fixture.store.scriptReservations(TransactionOutcome.conflict());

        RequestRejectedException rejection = rejected(
                fixture.purchase(CUSTOMER_A, key(1), "A-1-1"), DomainErrorCode.SERVICE_UNAVAILABLE);

        assertThat(rejection.retryAfter()).contains(Duration.ofSeconds(1));
        assertNothingWasCreated();
    }

    @Test
    @DisplayName("ADR-023 a cancellation without a functional reason after re-reading idempotency is SERVICE_UNAVAILABLE")
    void cancellationWithoutFunctionalReasonIsServiceUnavailable() {
        fixture.store.scriptReservations(TransactionOutcome.cancelled(List.of(
                ItemFailure.of(FailedItem.IDEMPOTENCY_RECORD), ItemFailure.of(FailedItem.ORDER))));

        rejected(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"), DomainErrorCode.SERVICE_UNAVAILABLE);
    }

    // ------------------------------------------------------------------ AC-047 precedence

    @Test
    @DisplayName("AC-047 BR-031 a past Event is EVENT_NOT_ON_SALE even when the CUSTOMER has an active Order in it")
    void pastEventPrevailsOverActiveOrder() {
        value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));
        fixture.clock.set(NOW.plus(Duration.ofDays(11)));

        rejected(fixture.purchase(CUSTOMER_A, key(2), "A-1-2"), DomainErrorCode.EVENT_NOT_ON_SALE);
    }

    @Test
    @DisplayName("AC-047 BR-031 validation prevails over a reused key")
    void validationPrevailsOverIdempotency() {
        value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));

        invalid(fixture.purchase(CUSTOMER_A, key(1), "A-1-2", "A-1-2"));
    }

    @Test
    @DisplayName("AC-047 BR-031 a reused key prevails over a missing Event")
    void reusedKeyPrevailsOverMissingEvent() {
        value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));

        rejected(fixture.purchases.startPurchase(new StartPurchaseCommand(
                CUSTOMER_A, "missing-event", List.of("A-1-1"), key(1), CORRELATION)), DomainErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    @DisplayName("AC-047 BR-031 a replay prevails over the Event having become past")
    void replayPrevailsOverPastEvent() {
        PurchaseResult first = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));
        fixture.clock.set(NOW.plus(Duration.ofDays(11)));

        PurchaseResult replay = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.order().orderId()).isEqualTo(first.order().orderId());
    }

    @Test
    @DisplayName("AC-047 BR-031 a missing Event prevails over unknown tickets and queue unavailability")
    void missingEventPrevailsOverLaterChecks() {
        fixture.orderPublisher.setAvailability(PublisherAvailability.unavailable(Duration.ofSeconds(3)));

        rejected(fixture.purchases.startPurchase(new StartPurchaseCommand(
                CUSTOMER_A, "missing-event", List.of("Z-1-1"), key(1), CORRELATION)), DomainErrorCode.EVENT_NOT_FOUND);
    }

    @Test
    @DisplayName("AC-047 BR-031 a past Event prevails over unknown tickets")
    void pastEventPrevailsOverUnknownTickets() {
        fixture.clock.set(NOW.plus(Duration.ofDays(11)));

        rejected(fixture.purchase(CUSTOMER_A, key(1), "Z-1-1"), DomainErrorCode.EVENT_NOT_ON_SALE);
    }

    @Test
    @DisplayName("AC-047 BR-031 unknown tickets prevail over queue unavailability")
    void unknownTicketsPrevailOverQueueUnavailability() {
        fixture.orderPublisher.setAvailability(PublisherAvailability.unavailable(Duration.ofSeconds(3)));

        rejected(fixture.purchase(CUSTOMER_A, key(1), "Z-1-1"), DomainErrorCode.UNKNOWN_TICKETS);
    }

    @Test
    @DisplayName("AC-047 BR-031 queue unavailability prevails over an active Order and unavailable tickets")
    void queueUnavailabilityPrevailsOverTransactionReasons() {
        value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));
        fixture.orderPublisher.setAvailability(PublisherAvailability.unavailable(Duration.ofSeconds(3)));

        rejected(fixture.purchase(CUSTOMER_A, key(2), "A-1-1"), DomainErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("AC-047 BR-031 ADR-023 an active Order prevails over unavailable tickets in the transaction")
    void activeOrderPrevailsOverUnavailableTickets() {
        value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));

        rejected(fixture.purchase(CUSTOMER_A, key(2), "A-1-1", "A-1-2"), DomainErrorCode.ACTIVE_ORDER_EXISTS);
    }

    // ------------------------------------------------------------------ enqueue failure and compensation

    @Test
    @DisplayName("AC-022 ERR-007 ALT-006 ST-005 ST-009 a definitive enqueue failure leaves the Order FAILED and releases its tickets")
    void compensatesADefinitiveEnqueueFailure() {
        fixture.orderPublisher.script(PublishResult.FAILED);
        fixture.orderPublisher.onPublish(message -> fixture.clock.advance(Duration.ofSeconds(2)));

        PurchaseResult result = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1", "A-1-2"));

        OrderView order = result.order();
        assertThat(result.replayed()).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.failureCause()).isEqualTo(FunctionalCause.PROCESSING_UNAVAILABLE);
        assertThat(order.createdAt()).isEqualTo(NOW);
        assertThat(order.updatedAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(fixture.store.ticket(EVENT_ID, "A-1-1").state()).isEqualTo(TicketState.AVAILABLE);
        assertThat(fixture.store.ticket(EVENT_ID, "A-1-2").state()).isEqualTo(TicketState.AVAILABLE);
        assertThat(fixture.store.ticket(EVENT_ID, "A-1-2").orderId()).isNull();
        assertThat(fixture.store.activeLock(CUSTOMER_A, EVENT_ID)).isEmpty();
        assertThat(fixture.store.order(order.orderId()).orElseThrow().order().reversalPlan()).isNull();
        AuditRecord audit = fixture.store.audits().getLast();
        assertThat(audit.code()).isEqualTo(AuditCode.ENQUEUE_FAILED);
        assertThat(audit.transitionIds()).containsExactly("ST-005", "ST-009");
        assertThat(audit.cause()).isEqualTo("PROCESSING_UNAVAILABLE");
        assertThat(audit.actor().type()).isEqualTo(ActorType.SYSTEM);
    }

    @Test
    @DisplayName("AC-003 AC-022 an enqueue failure reported as an error signal is also compensated")
    void compensatesAPublicationErrorSignal() {
        PurchaseService purchases = new PurchaseService(fixture.store, fixture.store, fixture.store, fixture.store,
                new FailingPublisher(), fixture.clock, fixture.ids, fixture.settings);

        PurchaseResult result = value(purchases.startPurchase(command(CUSTOMER_A, key(1), "A-1-1")));

        assertThat(result.order().status()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    @DisplayName("ADR-026 a double failure (publication and compensation) is SERVICE_UNAVAILABLE and leaves the Order for the sweep")
    void doubleFailureIsServiceUnavailable() {
        fixture.orderPublisher.script(PublishResult.FAILED);
        fixture.store.scriptEnqueueFailures(TransactionOutcome.conflict());

        RequestRejectedException rejection = rejected(
                fixture.purchase(CUSTOMER_A, key(1), "A-1-1"), DomainErrorCode.SERVICE_UNAVAILABLE);

        assertThat(rejection.retryAfter()).contains(Duration.ofSeconds(1));
        OrderRecord pending = fixture.store.orders().getFirst();
        assertThat(pending.order().status()).isEqualTo(OrderStatus.CREATED);
        assertThat(pending.enqueuedAt()).isNull();
    }

    @Test
    @DisplayName("ADR-026 when the compensation finds a PaymentAttempt already started it answers with the current state")
    void compensationAfterPaymentStartedAnswersCurrentState() {
        fixture.orderPublisher.script(PublishResult.FAILED);
        fixture.orderPublisher.onPublish(message -> {
            OrderRecord stored = fixture.store.order(message.orderId()).orElseThrow();
            fixture.store.putOrder(new OrderRecord(stored.order().startPayment(NOW),
                    stored.createdAt(), NOW.plusSeconds(1), stored.enqueuedAt()));
        });

        PurchaseResult result = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));

        assertThat(result.replayed()).isFalse();
        assertThat(result.order().status()).isEqualTo(OrderStatus.CREATED);
        assertThat(result.order().updatedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(fixture.store.activeLock(CUSTOMER_A, EVENT_ID)).isPresent();
    }

    @Test
    @DisplayName("ADR-026 when the compensation finds a terminal Order it answers with that state")
    void compensationAfterTerminalStateAnswersCurrentState() {
        fixture.orderPublisher.script(PublishResult.FAILED);
        fixture.orderPublisher.onPublish(message -> {
            OrderRecord stored = fixture.store.order(message.orderId()).orElseThrow();
            Order expired = stored.order().expire(NOW.plus(Duration.ofMinutes(10)));
            fixture.store.putOrder(new OrderRecord(expired, stored.createdAt(), NOW, stored.enqueuedAt()));
        });

        PurchaseResult result = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"));

        assertThat(result.order().status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(result.order().failureCause()).isEqualTo(FunctionalCause.RESERVATION_EXPIRED);
    }

    @Test
    @DisplayName("ADR-025 a compensation cancelled by a Ticket condition with the Order CREATED quarantines the Order")
    void compensationCancelledByTicketQuarantines() {
        fixture.orderPublisher.script(PublishResult.FAILED);
        fixture.orderPublisher.onPublish(message ->
                fixture.store.setTicket(EVENT_ID, "A-1-2", TicketState.SOLD, "corrupted-order"));

        PurchaseResult result = value(fixture.purchase(CUSTOMER_A, key(1), "A-1-1", "A-1-2"));

        assertThat(result.order().status()).isEqualTo(OrderStatus.CREATED);
        Order stored = fixture.store.order(result.order().orderId()).orElseThrow().order();
        assertThat(stored.quarantinedAt()).isEqualTo(NOW);
        assertThat(stored.quarantineReason()).isEqualTo(PurchaseService.ENQUEUE_COMPENSATION_QUARANTINE_REASON);
        assertThat(fixture.store.ticket(EVENT_ID, "A-1-1").state()).isEqualTo(TicketState.RESERVED);
        assertThat(fixture.store.activeLock(CUSTOMER_A, EVENT_ID)).contains(result.order().orderId());
        AuditRecord audit = fixture.store.audits().getLast();
        assertThat(audit.code()).isEqualTo(AuditCode.ORDER_QUARANTINED);
        assertThat(audit.cause()).isEqualTo(PurchaseService.ENQUEUE_COMPENSATION_QUARANTINE_REASON);
    }

    @Test
    @DisplayName("ADR-026 a compensation cancelled without a Ticket reason while the Order is still compensable is a double failure")
    void compensationCancelledWithoutTicketReasonIsDoubleFailure() {
        fixture.orderPublisher.script(PublishResult.FAILED);
        fixture.store.scriptEnqueueFailures(TransactionOutcome.cancelled(List.of(ItemFailure.of(FailedItem.AUDIT))));

        rejected(fixture.purchase(CUSTOMER_A, key(1), "A-1-1"), DomainErrorCode.SERVICE_UNAVAILABLE);
    }

    // ------------------------------------------------------------------ helpers

    private void assertNothingWasCreated() {
        assertThat(fixture.store.orders()).isEmpty();
        assertThat(fixture.store.purchaseIdempotencyCount()).isZero();
        assertThat(fixture.store.activeLock(CUSTOMER_A, EVENT_ID)).isEmpty();
        assertThat(fixture.store.tickets(EVENT_ID))
                .filteredOn(ticket -> ticket.state() == TicketState.RESERVED)
                .isEmpty();
        assertThat(fixture.store.audits()).isEmpty();
        assertThat(fixture.orderPublisher.published()).isEmpty();
    }

    private static final class FailingPublisher implements com.nequi.ticketing.application.port.out.OrderQueuePublisher {
        @Override
        public reactor.core.publisher.Mono<PublishResult> publish(OrderProcessingRequested message) {
            return reactor.core.publisher.Mono.error(new IllegalStateException("broker down"));
        }

        @Override
        public PublisherAvailability availability() {
            return PublisherAvailability.available();
        }
    }
}
