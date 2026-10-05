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
        assertThat(record.includedCodes()).isEmpty();
        assertThat(record.capacity()).isNull();
    }

    @Test
    @DisplayName("ADR-031 a transition record can include the reversal request and Event records carry inventory counts")
    void auditRecordIncludesCausesAndInventoryCounts() {
        Instant occurredAt = Instant.parse("2026-01-01T00:00:00Z");
        Actor worker = new Actor(ActorType.WORKER, "worker-1");
        AuditRecord expired = new AuditRecordBuilder()
                .code(AuditCode.RESERVATION_EXPIRED)
                .order("order")
                .include(AuditCode.LATE_APPROVAL_NOT_APPLIED)
                .include(AuditCode.PAYMENT_REVERSAL_REQUESTED)
                .actor(worker)
                .correlation("correlation")
                .occurredAt(occurredAt)
                .build();
        AuditRecord enabled = new AuditRecordBuilder()
                .code(AuditCode.EVENT_ENABLED)
                .event("event")
                .inventoryCounts(25, 24, 1)
                .actor(worker)
                .correlation("correlation")
                .occurredAt(occurredAt)
                .build();
        AuditRecord legacy = new AuditRecord(AuditCode.ORDER_QUARANTINED, List.of(), null, "order", null, null,
                null, null, List.of(), null, worker, "correlation", null, occurredAt);

        assertThat(expired.records(AuditCode.PAYMENT_REVERSAL_REQUESTED)).isTrue();
        assertThat(expired.records(AuditCode.RESERVATION_EXPIRED)).isTrue();
        assertThat(expired.records(AuditCode.PAYMENT_APPROVED)).isFalse();
        assertThat(enabled.capacity()).isEqualTo(25);
        assertThat(enabled.availableCount()).isEqualTo(24);
        assertThat(enabled.complimentaryCount()).isEqualTo(1);
        assertThat(legacy.includedCodes()).isEmpty();
        assertThat(new AuditRecord(AuditCode.EVENT_ENABLED, List.of(), "event", null, null, null, null, null, List.of(),
                null, worker, "c", null, occurredAt, null, 25, 25, 0).includedCodes()).isEmpty();
        assertThatThrownBy(() -> new AuditRecord(AuditCode.EVENT_ENABLED, List.of(), null, null, null, null, null, null,
                List.of(), null, worker, "c", null, occurredAt)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditRecordBuilder().code(AuditCode.RESERVATION_EXPIRED).order("order")
                .include(AuditCode.RESERVATION_EXPIRED).actor(worker).correlation("c").occurredAt(occurredAt).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ADR-035 sealed validation errors expose stable codes without transport concerns")
    void validationErrorHasStableCode() {
        ValidationException error = new ValidationException("bad input");
        assertThat(error.code()).isEqualTo(DomainErrorCode.VALIDATION_ERROR);
        assertThat(error.getMessage()).isEqualTo("bad input");
    }

    @Test
    @DisplayName("ADR-035 validation failures name the offending input in the terms of the HTTP contract")
    void validationFailuresNameTheirField() {
        assertThat(new ValidationException("plain").field()).isEmpty();
        assertThat(new ValidationException("cursor", "cursor is invalid").field()).contains("cursor");
        assertThat(new ValidationException("cursor", "cursor is invalid").code()).isEqualTo(DomainErrorCode.VALIDATION_ERROR);
        assertThatThrownBy(() -> new PurchaseRequest("event", List.of(), "valid_key_123456"))
                .isInstanceOfSatisfying(ValidationException.class,
                        error -> assertThat(error.field()).contains("ticketIds"));
        assertThatThrownBy(() -> new PurchaseRequest("event", List.of("A-1-1"), "bad key"))
                .isInstanceOfSatisfying(ValidationException.class,
                        error -> assertThat(error.field()).contains("Idempotency-Key"));
        InventoryDefinition overlapping = definition(
                List.of(new Section("A", List.of(new Row("1", 3)))),
                List.of(new ComplimentaryRange("A", "1", 1, 2), new ComplimentaryRange("A", "1", 2, 3)));
        assertThatThrownBy(() -> overlapping.validate(3, com.nequi.ticketing.domain.event.InventoryLimits.DEPLOYED))
                .isInstanceOfSatisfying(ValidationException.class,
                        error -> assertThat(error.field()).contains("inventory.complimentary"));
        assertThatThrownBy(() -> new Section("A", List.of()))
                .isInstanceOfSatisfying(ValidationException.class,
                        error -> assertThat(error.field()).contains("inventory.sections"));
        assertThatThrownBy(() -> overlapping.validate(4, com.nequi.ticketing.domain.event.InventoryLimits.DEPLOYED))
                .isInstanceOfSatisfying(ValidationException.class,
                        error -> assertThat(error.field()).contains("capacity"));
    }

    private static InventoryDefinition definition(
            List<Section> sections, List<ComplimentaryRange> complimentaryRanges) {
        return new InventoryDefinition(sections, complimentaryRanges);
    }
}
