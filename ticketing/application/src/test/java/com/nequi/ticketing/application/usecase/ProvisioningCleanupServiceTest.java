package com.nequi.ticketing.application.usecase;

import static com.nequi.ticketing.application.usecase.Rejections.value;
import static com.nequi.ticketing.application.usecase.WorkerFixture.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.ticketing.application.port.in.CycleResult;
import com.nequi.ticketing.application.port.in.ItemOutcome;
import com.nequi.ticketing.application.port.out.EventProvisioningRequested;
import com.nequi.ticketing.application.port.out.FailedItem;
import com.nequi.ticketing.application.port.out.ItemFailure;
import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.port.out.PublishResult;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.application.testdouble.InMemoryTicketingStore.Operation;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import com.nequi.ticketing.domain.event.InventoryLimits;
import com.nequi.ticketing.domain.ticket.Ticket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProvisioningCleanupServiceTest {

    private static final String EVENT = "10000000-0000-4000-8000-000000000088";
    private static final Instant STARTS_AT = NOW.plus(Duration.ofDays(20));

    private WorkerFixture fixture;
    private Event event;

    @BeforeEach
    void setUp() {
        fixture = new WorkerFixture();
        event = fixture.store.seedProvisioningEvent(EVENT, STARTS_AT, ApiFixture.DEFINITION, NOW);
    }

    private void seed(int republishCount, Instant lastProgressAt) {
        fixture.store.seedSnapshot(new ProvisioningSnapshot(event, NOW, 0, null, null, null, null, lastProgressAt,
                republishCount, null));
    }

    @Test
    @DisplayName("ADR-024 FR-001 an Event without progress for more than 3 minutes is republished and its counter incremented")
    void stalledEventIsRepublished() {
        fixture.clock.advance(Duration.ofMinutes(3).plusMillis(1));

        CycleResult result = fixture.cleanUp();

        ProvisioningSnapshot snapshot = fixture.store.snapshot(EVENT).orElseThrow();
        assertThat(result.count(ItemOutcome.PROVISIONING_REPUBLISHED)).isEqualTo(1);
        assertThat(snapshot.republishCount()).isEqualTo(1);
        assertThat(snapshot.lastProgressAt()).isEqualTo(fixture.clock.now());
        assertThat(fixture.provisioningPublisher.published())
                .containsExactly(new EventProvisioningRequested(EVENT, WorkerFixture.CORRELATION));
        assertThat(fixture.cleanUp().total()).isZero();
    }

    @Test
    @DisplayName("ADR-024 an Event with progress in the last 3 minutes is not stalled")
    void recentProgressIsNotStalled() {
        fixture.clock.advance(Duration.ofMinutes(3));

        assertThat(fixture.cleanUp().total()).isZero();
        assertThat(fixture.provisioningPublisher.published()).isEmpty();
    }

    @Test
    @DisplayName("AC-040 ERR-018 ST-013 an Event stalled after 3 republications is marked FAILED with its audit")
    void stalledAfterThreeRepublicationsFails() {
        seed(3, NOW);
        fixture.clock.advance(Duration.ofMinutes(4));

        CycleResult result = fixture.cleanUp();

        ProvisioningSnapshot snapshot = fixture.store.snapshot(EVENT).orElseThrow();
        assertThat(result.count(ItemOutcome.PROVISIONING_FAILED)).isEqualTo(1);
        assertThat(snapshot.event().provisioningStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(fixture.store.audits().getLast().code()).isEqualTo(AuditCode.EVENT_PROVISIONING_FAILED);
        assertThat(fixture.provisioningPublisher.published()).isEmpty();
    }

    @Test
    @DisplayName("ADR-024 FR-003 the Tickets of a FAILED Event are purged once and the Event stays consultable")
    void failedEventTicketsArePurged() {
        List<Ticket> tickets = event.inventoryDefinition().validate(25, InventoryLimits.DEPLOYED).tickets().stream()
                .map(seed -> Ticket.provision(EVENT, seed)).toList();
        fixture.store.writeBatch(event, tickets.subList(0, 12)).block(Duration.ofSeconds(5));
        fixture.store.seedSnapshot(new ProvisioningSnapshot(event.fail(), NOW, 0, null, NOW));

        CycleResult result = fixture.cleanUp();

        ProvisioningSnapshot snapshot = fixture.store.snapshot(EVENT).orElseThrow();
        assertThat(result.count(ItemOutcome.TICKETS_PURGED)).isEqualTo(1);
        assertThat(fixture.store.tickets(EVENT)).isEmpty();
        assertThat(snapshot.ticketsPurgedAt()).isEqualTo(NOW);
        assertThat(snapshot.event().provisioningStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(fixture.cleanUp().total()).isZero();
    }

    @Test
    @DisplayName("ADR-024 a republication whose publication fails is counted as failed; a lost registration is not applicable")
    void republicationOutcomes() {
        fixture.clock.advance(Duration.ofMinutes(5));
        fixture.provisioningPublisher.setResult(PublishResult.FAILED);

        assertThat(fixture.cleanUp().count(ItemOutcome.FAILED)).isEqualTo(1);
        assertThat(fixture.store.snapshot(EVENT).orElseThrow().republishCount()).isEqualTo(1);

        fixture.clock.advance(Duration.ofMinutes(5));
        fixture.store.beforeNext(Operation.REGISTER_REPUBLICATION, () -> seed(1, fixture.clock.now().minus(Duration.ofMinutes(4))));
        assertThat(fixture.cleanUp().count(ItemOutcome.NOT_APPLICABLE)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-024 a stale candidate that progressed or left PROVISIONING is ignored after the strongly consistent read")
    void staleCandidatesAreIgnored() {
        fixture.clock.advance(Duration.ofMinutes(5));
        fixture.store.beforeNext(Operation.FIND_SNAPSHOT, () -> seed(0, fixture.clock.now()));

        assertThat(fixture.cleanUp().count(ItemOutcome.NOT_APPLICABLE)).isEqualTo(1);

        fixture.clock.advance(Duration.ofMinutes(5));
        fixture.store.beforeNext(Operation.FIND_SNAPSHOT, () -> fixture.store.seedSnapshot(
                new ProvisioningSnapshot(event.enable(25), NOW, 1, NOW, null)));
        assertThat(fixture.cleanUp().count(ItemOutcome.NOT_APPLICABLE)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-028 rule 3 query and candidate failures are counted and retried by the next cycle")
    void failuresAreCounted() {
        fixture.store.failNext(Operation.FIND_STALLED, 1);
        fixture.store.failNext(Operation.FIND_FAILED, 1);

        CycleResult result = fixture.cleanUp();

        assertThat(result.count(ItemOutcome.FAILED)).isEqualTo(2);
    }

    @Test
    @DisplayName("ADR-024 lost or conflicting FAILED marks and purge marks are not applied twice")
    void markOutcomes() {
        seed(3, NOW);
        fixture.clock.advance(Duration.ofMinutes(4));
        fixture.store.script(Operation.MARK_FAILED,
                TransactionOutcome.cancelled(List.of(ItemFailure.of(FailedItem.EVENT))), TransactionOutcome.conflict());

        assertThat(fixture.cleanUp().count(ItemOutcome.NOT_APPLICABLE)).isEqualTo(1);
        assertThat(fixture.cleanUp().count(ItemOutcome.FAILED)).isEqualTo(1);

        fixture.store.seedSnapshot(new ProvisioningSnapshot(event.fail(), NOW, 0, null, NOW));
        fixture.store.beforeNext(Operation.MARK_PURGED, () -> fixture.store.seedSnapshot(
                new ProvisioningSnapshot(event.enable(25), NOW, 1, NOW, null)));
        assertThat(fixture.cleanUp().count(ItemOutcome.NOT_APPLICABLE)).isEqualTo(1);

        fixture.store.seedSnapshot(new ProvisioningSnapshot(event.fail(), NOW, 0, null, NOW, null, null, NOW, 0, NOW));
        assertThat(fixture.cleanUp().total()).isZero();

        fixture.store.seedSnapshot(new ProvisioningSnapshot(event.fail(), NOW, 0, null, NOW));
        fixture.store.beforeNext(Operation.FIND_SNAPSHOT, () -> fixture.store.seedSnapshot(
                new ProvisioningSnapshot(event.fail(), NOW, 0, null, NOW, null, null, NOW, 0, NOW)));
        assertThat(fixture.cleanUp().count(ItemOutcome.NOT_APPLICABLE)).isEqualTo(1);
    }
}
