package com.nequi.ticketing.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.domain.audit.AuditRecord.Actor;
import com.nequi.ticketing.domain.audit.AuditRecord.ActorType;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.error.DomainErrorCode;
import com.nequi.ticketing.domain.error.PurchaseRejectionPolicy;
import com.nequi.ticketing.domain.error.ValidationException;
import com.nequi.ticketing.domain.event.InventoryDefinition;
import com.nequi.ticketing.domain.event.InventoryDefinition.ComplimentaryRange;
import com.nequi.ticketing.domain.event.InventoryDefinition.Row;
import com.nequi.ticketing.domain.event.InventoryDefinition.Section;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import com.nequi.ticketing.domain.order.PurchaseRequest;
import com.nequi.ticketing.domain.shared.ContentHash;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DomainSupportTest {

    @Test
    @DisplayName("ADR-027 purchase hash is canonical and excludes the idempotency key")
    void purchaseHashIsCanonical() {
        PurchaseRequest first = new PurchaseRequest("event", List.of("B", "A"), "valid_key_123456");
        PurchaseRequest second = new PurchaseRequest("event", List.of("A", "B"), "another_key_1234");

        assertThat(ContentHash.purchase(first)).isEqualTo(ContentHash.purchase(second)).hasSize(64);
    }

    @Test
    @DisplayName("ADR-027 Event hash normalizes inventory ordering")
    void eventHashIsCanonical() {
        InventoryDefinition first = definition(List.of(
                new Section("B", List.of(new Row("2", 1))),
                new Section("A", List.of(new Row("1", 1)))),
                List.of(new ComplimentaryRange("B", "2", 1, 1)));
        InventoryDefinition second = definition(List.of(
                new Section("A", List.of(new Row("1", 1))),
                new Section("B", List.of(new Row("2", 1)))),
                List.of(new ComplimentaryRange("B", "2", 1, 1)));

        assertThat(ContentHash.event("name", "venue", Instant.EPOCH, 2, first))
                .isEqualTo(ContentHash.event("name", "venue", Instant.EPOCH, 2, second));
    }

    @Test
    @DisplayName("BR-031 selects the first synchronous rejection by normative precedence")
    void rejectionPrecedenceIsStable() {
        assertThat(PurchaseRejectionPolicy.firstOf(List.of(
                DomainErrorCode.TICKETS_UNAVAILABLE,
                DomainErrorCode.EVENT_NOT_ON_SALE,
                DomainErrorCode.ACTIVE_ORDER_EXISTS))).isEqualTo(DomainErrorCode.EVENT_NOT_ON_SALE);
        assertThat(PurchaseRejectionPolicy.precedence()).startsWith(
                DomainErrorCode.VALIDATION_ERROR, DomainErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThatThrownBy(() -> PurchaseRejectionPolicy.firstOf(List.of(DomainErrorCode.INTERNAL_ERROR)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ADR-031 builds an immutable audit record with state transitions and actors")
    void buildsAuditRecord() {
        Instant occurredAt = Instant.parse("2026-01-01T00:00:00Z");
        AuditRecord record = new AuditRecordBuilder()
                .code(AuditCode.PAYMENT_APPROVED)
                .transitions("ST-004", "ST-007")
                .event("event")
                .order("order")
                .orderStates(OrderStatus.CREATED, OrderStatus.CONFIRMED)
                .ticketStates(TicketState.PENDING_CONFIRMATION, TicketState.SOLD)
                .tickets(List.of("A-1-1"))
                .cause("approved")
                .actor(new Actor(ActorType.WORKER, "worker-1"))
                .correlation("correlation")
                .paymentAttempt("order-1")
                .occurredAt(occurredAt)
                .build();

        assertThat(record.code()).isEqualTo(AuditCode.PAYMENT_APPROVED);
        assertThat(record.transitionIds()).containsExactly("ST-004", "ST-007");
        assertThat(record.occurredAt()).isEqualTo(occurredAt);
    }

    @Test
    @DisplayName("ADR-035 sealed validation errors expose stable codes without transport concerns")
    void validationErrorHasStableCode() {
        ValidationException error = new ValidationException("bad input");
        assertThat(error.code()).isEqualTo(DomainErrorCode.VALIDATION_ERROR);
        assertThat(error.getMessage()).isEqualTo("bad input");
    }

    private static InventoryDefinition definition(
            List<Section> sections, List<ComplimentaryRange> complimentaryRanges) {
        return new InventoryDefinition(sections, complimentaryRanges);
    }
}
