package com.nequi.ticketing.application.usecase;

import static com.nequi.ticketing.application.usecase.ApiFixture.ADMIN;
import static com.nequi.ticketing.application.usecase.ApiFixture.CORRELATION;
import static com.nequi.ticketing.application.usecase.ApiFixture.CUSTOMER_A;
import static com.nequi.ticketing.application.usecase.ApiFixture.DEFINITION;
import static com.nequi.ticketing.application.usecase.ApiFixture.NOW;
import static com.nequi.ticketing.application.usecase.ApiFixture.createEvent;
import static com.nequi.ticketing.application.usecase.Rejections.invalid;
import static com.nequi.ticketing.application.usecase.Rejections.rejected;
import static com.nequi.ticketing.application.usecase.Rejections.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.application.port.in.AvailabilityQuery;
import com.nequi.ticketing.application.port.in.CreateEventCommand;
import com.nequi.ticketing.application.port.in.EventCreationResult;
import com.nequi.ticketing.application.port.in.ListEventsQuery;
import com.nequi.ticketing.application.port.in.ProvisioningStatusView;
import com.nequi.ticketing.application.port.in.StartPurchaseCommand;
import com.nequi.ticketing.application.port.out.EventCatalog;
import com.nequi.ticketing.application.port.out.EventProvisioningRequested;
import com.nequi.ticketing.application.port.out.FailedItem;
import com.nequi.ticketing.application.port.out.IdempotencyRecord;
import com.nequi.ticketing.application.port.out.IdempotencyStore;
import com.nequi.ticketing.application.port.out.ItemFailure;
import com.nequi.ticketing.application.port.out.ProvisioningQueuePublisher;
import com.nequi.ticketing.application.port.out.ProvisioningSnapshot;
import com.nequi.ticketing.application.port.out.PublishResult;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import com.nequi.ticketing.application.testdouble.SequentialIdGenerator;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.audit.AuditRecord.ActorType;
import com.nequi.ticketing.domain.audit.AuditRecord.AuditCode;
import com.nequi.ticketing.domain.error.DomainErrorCode;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import com.nequi.ticketing.domain.event.InventoryDefinition;
import com.nequi.ticketing.domain.event.InventoryDefinition.Row;
import com.nequi.ticketing.domain.event.InventoryDefinition.Section;
import com.nequi.ticketing.domain.event.InventoryLimits;
import com.nequi.ticketing.domain.shared.ContentHash;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class EventManagementServiceTest {

    private static final Instant STARTS_AT = NOW.plus(Duration.ofDays(30));
    private static final String KEY = "event-key-00000001";

    private ApiFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new ApiFixture();
    }

    @Test
    @DisplayName("AC-001 ST-011 FR-001 a valid creation answers immediately with the eventId in PROVISIONING")
    void createsTheEventInProvisioning() {
        EventCreationResult result = value(fixture.eventManagement.createEvent(createEvent(KEY, STARTS_AT, 25, DEFINITION)));

        String eventId = SequentialIdGenerator.eventId(1);
        ProvisioningStatusView status = result.status();
        assertThat(result.replayed()).isFalse();
        assertThat(status.eventId()).isEqualTo(eventId);
        assertThat(status.provisioningStatus()).isEqualTo(ProvisioningStatus.PROVISIONING);
        assertThat(status.capacity()).isEqualTo(25);
        assertThat(status.complimentaryTickets()).isEqualTo(1);
        assertThat(status.provisionedTickets()).isZero();
        assertThat(status.createdAt()).isEqualTo(NOW);
        assertThat(status.enabledAt()).isNull();
        assertThat(status.failedAt()).isNull();
        assertThat(status.failureCause()).isNull();
        Event stored = fixture.store.findEvent(eventId).block(Duration.ofSeconds(5));
        assertThat(stored.provisioningStatus()).isEqualTo(ProvisioningStatus.PROVISIONING);
        assertThat(stored.availabilityShards()).isEqualTo(1);
        assertThat(stored.inventoryDefinition()).isEqualTo(DEFINITION);
    }

    @Test
    @DisplayName("AC-001 ADR-024 MSG-002 requests the asynchronous provisioning with only the eventId")
    void publishesTheProvisioningRequest() {
        EventCreationResult result = value(fixture.eventManagement.createEvent(createEvent(KEY, STARTS_AT, 25, DEFINITION)));

        assertThat(fixture.provisioningPublisher.published())
                .containsExactly(new EventProvisioningRequested(result.status().eventId(), CORRELATION));
    }

    @Test
    @DisplayName("BR-032 ADR-027 ADR-031 registers the idempotency record and the audit in the same write")
    void registersIdempotencyAndAudit() {
        CreateEventCommand command = createEvent(KEY, STARTS_AT, 25, DEFINITION);
        EventCreationResult result = value(fixture.eventManagement.createEvent(command));

        IdempotencyRecord record = fixture.store.findEventCreation(ADMIN, KEY).block(Duration.ofSeconds(5));
        assertThat(record.resourceId()).isEqualTo(result.status().eventId());
        assertThat(record.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(record.requestHash()).isEqualTo(ContentHash.event("Concert", "Arena", STARTS_AT, 25, DEFINITION));
        AuditRecord audit = fixture.store.audits().getFirst();
        assertThat(audit.code()).isEqualTo(AuditCode.EVENT_PROVISIONING_REQUESTED);
        assertThat(audit.transitionIds()).containsExactly("ST-011");
        assertThat(audit.eventId()).isEqualTo(result.status().eventId());
        assertThat(audit.actor().type()).isEqualTo(ActorType.ADMIN);
        assertThat(audit.actor().id()).isEqualTo(ADMIN);
    }

    @Test
    @DisplayName("ADR-024 a failed MSG-002 publication does not change the 202 answer; the Event waits in PROVISIONING")
    void failedProvisioningPublicationKeepsTheAnswer() {
        fixture.provisioningPublisher.setResult(PublishResult.FAILED);

        EventCreationResult result = value(fixture.eventManagement.createEvent(createEvent(KEY, STARTS_AT, 25, DEFINITION)));

        assertThat(result.replayed()).isFalse();
        assertThat(result.status().provisioningStatus()).isEqualTo(ProvisioningStatus.PROVISIONING);
        assertThat(fixture.store.eventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-024 ADR-035 a MSG-002 publication error signal is also absorbed")
    void provisioningPublicationErrorIsAbsorbed() {
        ProvisioningQueuePublisher failing = message -> Mono.error(new IllegalStateException("broker down"));
        EventManagementService service = new EventManagementService(
                fixture.store, fixture.store, failing, fixture.clock, fixture.ids, fixture.settings);

        EventCreationResult result = value(service.createEvent(createEvent(KEY, STARTS_AT, 25, DEFINITION)));

        assertThat(result.status().provisioningStatus()).isEqualTo(ProvisioningStatus.PROVISIONING);
    }

    @Test
    @DisplayName("AC-039 BR-027 an Event in PROVISIONING is visible to the ADMIN status only: not listed, no availability, no purchase")
    void provisioningEventIsInvisibleForCustomers() {
        EventCreationResult result = value(fixture.eventManagement.createEvent(createEvent(KEY, STARTS_AT, 25, DEFINITION)));
        String eventId = result.status().eventId();

        assertThat(value(fixture.eventManagement.getProvisioningStatus(eventId)).provisioningStatus())
                .isEqualTo(ProvisioningStatus.PROVISIONING);
        assertThat(value(fixture.catalog.listEvents(new ListEventsQuery(null, null))).items()).isEmpty();
        rejected(fixture.catalog.getAvailability(new AvailabilityQuery(eventId, null, null, null)),
                DomainErrorCode.EVENT_NOT_FOUND);
        rejected(fixture.purchases.startPurchase(new StartPurchaseCommand(
                CUSTOMER_A, eventId, List.of("A-1-1"), "purchase-key-00000001", CORRELATION)), DomainErrorCode.EVENT_NOT_FOUND);
    }

    @Test
    @DisplayName("AC-041 BR-032 a replay with the same key and content returns the same eventId with its current status")
    void replaysAnAcceptedCreation() {
        EventCreationResult first = value(fixture.eventManagement.createEvent(createEvent(KEY, STARTS_AT, 25, DEFINITION)));
        Event provisioning = fixture.store.findEvent(first.status().eventId()).block(Duration.ofSeconds(5));
        fixture.store.seedSnapshot(new ProvisioningSnapshot(provisioning.enable(25), NOW, 1, NOW.plusSeconds(20), null));
        fixture.clock.advance(Duration.ofMinutes(1));

        EventCreationResult replay = value(fixture.eventManagement.createEvent(createEvent(KEY, STARTS_AT, 25, DEFINITION)));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status().eventId()).isEqualTo(first.status().eventId());
        assertThat(replay.status().provisioningStatus()).isEqualTo(ProvisioningStatus.ENABLED);
        assertThat(replay.status().provisionedTickets()).isEqualTo(25);
        assertThat(replay.status().enabledAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(fixture.store.eventCount()).isEqualTo(1);
        assertThat(fixture.provisioningPublisher.published()).hasSize(1);
    }

    @Test
    @DisplayName("AC-041 ADR-027 the idempotency lookup precedes business validation: a replay after startsAt passed is still a replay")
    void replayPrecedesBusinessValidation() {
        Instant soon = NOW.plus(Duration.ofHours(1));
        EventCreationResult first = value(fixture.eventManagement.createEvent(createEvent(KEY, soon, 25, DEFINITION)));
        fixture.clock.set(soon.plus(Duration.ofMinutes(5)));

        EventCreationResult replay = value(fixture.eventManagement.createEvent(createEvent(KEY, soon, 25, DEFINITION)));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status().eventId()).isEqualTo(first.status().eventId());
    }

    @Test
    @DisplayName("AC-042 ERR-019 VAL-016 the same key with different content is IDEMPOTENCY_KEY_REUSED without effects")
    void rejectsAReusedKey() {
        value(fixture.eventManagement.createEvent(createEvent(KEY, STARTS_AT, 25, DEFINITION)));

        rejected(fixture.eventManagement.createEvent(createEvent(KEY, STARTS_AT.plusSeconds(60), 25, DEFINITION)),
                DomainErrorCode.IDEMPOTENCY_KEY_REUSED);

        assertThat(fixture.store.eventCount()).isEqualTo(1);
        assertThat(fixture.provisioningPublisher.published()).hasSize(1);
    }

    @Test
    @DisplayName("BR-032 ADR-032 the creation key is bound to the ADMIN subject")
    void bindsTheKeyToTheAdmin() {
        value(fixture.eventManagement.createEvent(createEvent(KEY, STARTS_AT, 25, DEFINITION)));
        CreateEventCommand otherAdmin = new CreateEventCommand(
                "admin-2", KEY, "Concert", "Arena", STARTS_AT, 25, DEFINITION, CORRELATION);

        EventCreationResult second = value(fixture.eventManagement.createEvent(otherAdmin));

        assertThat(second.replayed()).isFalse();
        assertThat(fixture.store.eventCount()).isEqualTo(2);
    }

    static Stream<Arguments> invalidCreations() {
        InventoryDefinition overCapacity = new InventoryDefinition(
                IntStream.rangeClosed(1, 51).mapToObj(index -> new Section("S" + index, List.of(new Row("1", 1000)))).toList(),
                List.of());
        return Stream.of(
                Arguments.of("AC-017 VAL-007 start instant not in the future", createEvent(KEY, NOW, 25, DEFINITION)),
                Arguments.of("AC-017 VAL-008 capacity not positive", createEvent(KEY, STARTS_AT, 0, DEFINITION)),
                Arguments.of("AC-033 VAL-008 capacity above 50,000", createEvent(KEY, STARTS_AT, 51_000, overCapacity)),
                Arguments.of("AC-017 VAL-009 capacity different from the derived seats", createEvent(KEY, STARTS_AT, 24, DEFINITION)),
                Arguments.of("AC-017 VAL-006 blank name",
                        new CreateEventCommand(ADMIN, KEY, " ", "Arena", STARTS_AT, 25, DEFINITION, CORRELATION)),
                Arguments.of("AC-017 VAL-006 missing venue",
                        new CreateEventCommand(ADMIN, KEY, "Concert", null, STARTS_AT, 25, DEFINITION, CORRELATION)),
                Arguments.of("AC-017 VAL-013 missing inventory definition", createEvent(KEY, STARTS_AT, 25, null)),
                Arguments.of("VAL-016 missing Idempotency-Key", createEvent(null, STARTS_AT, 25, DEFINITION)),
                Arguments.of("VAL-016 invalid Idempotency-Key", createEvent("bad key", STARTS_AT, 25, DEFINITION)),
                Arguments.of("ADR-032 missing ADMIN subject",
                        new CreateEventCommand(null, KEY, "Concert", "Arena", STARTS_AT, 25, DEFINITION, CORRELATION)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCreations")
    @DisplayName("AC-017 AC-033 ERR-011 an invalid creation is rejected completely and nothing is created")
    void rejectsInvalidCreationsWithoutEffects(String scenario, CreateEventCommand command) {
        invalid(fixture.eventManagement.createEvent(command));

        assertThat(fixture.store.eventCount()).isZero();
        assertThat(fixture.store.eventIdempotencyCount()).isZero();
        assertThat(fixture.store.audits()).isEmpty();
        assertThat(fixture.provisioningPublisher.published()).isEmpty();
    }

    @Test
    @DisplayName("ADR-027 a cancelled creation whose idempotency record appears on re-read is answered as a replay")
    void cancelledCreationResolvedAsReplay() {
        EventCatalog catalog = mock(EventCatalog.class);
        IdempotencyStore idempotency = mock(IdempotencyStore.class);
        String hash = ContentHash.event("Concert", "Arena", STARTS_AT, 25, DEFINITION);
        IdempotencyRecord winner = new IdempotencyRecord(ADMIN, KEY, "winner-event", hash, NOW, NOW.plus(Duration.ofHours(24)));
        Event winnerEvent = Event.create("winner-event", "Concert", "Arena", STARTS_AT, 25, DEFINITION, NOW, InventoryLimits.DEPLOYED);
        when(idempotency.findEventCreation(ADMIN, KEY)).thenReturn(Mono.empty(), Mono.just(winner));
        when(catalog.create(any())).thenReturn(Mono.just(TransactionOutcome.cancelled(List.of(
                ItemFailure.of(FailedItem.IDEMPOTENCY_RECORD)))));
        when(catalog.findProvisioningSnapshot("winner-event"))
                .thenReturn(Mono.just(new ProvisioningSnapshot(winnerEvent, NOW, 0, null, null)));
        EventManagementService service = new EventManagementService(
                catalog, idempotency, fixture.provisioningPublisher, fixture.clock, fixture.ids, fixture.settings);

        EventCreationResult result = value(service.createEvent(createEvent(KEY, STARTS_AT, 25, DEFINITION)));

        assertThat(result.replayed()).isTrue();
        assertThat(result.status().eventId()).isEqualTo("winner-event");
        assertThat(fixture.provisioningPublisher.published()).isEmpty();
    }

    @Test
    @DisplayName("ADR-035 a cancelled creation without idempotency record on re-read is SERVICE_UNAVAILABLE")
    void cancelledCreationWithoutRecordIsServiceUnavailable() {
        EventCatalog catalog = mock(EventCatalog.class);
        IdempotencyStore idempotency = mock(IdempotencyStore.class);
        when(idempotency.findEventCreation(anyString(), anyString())).thenReturn(Mono.empty());
        when(catalog.create(any())).thenReturn(Mono.just(TransactionOutcome.conflict()));
        EventManagementService service = new EventManagementService(
                catalog, idempotency, fixture.provisioningPublisher, fixture.clock, fixture.ids, fixture.settings);

        var rejection = rejected(service.createEvent(createEvent(KEY, STARTS_AT, 25, DEFINITION)),
                DomainErrorCode.SERVICE_UNAVAILABLE);

        assertThat(rejection.retryAfter()).contains(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("ADR-027 an idempotency record without its Event is reported as an internal inconsistency")
    void idempotencyRecordWithoutEvent() {
        EventCatalog catalog = mock(EventCatalog.class);
        IdempotencyStore idempotency = mock(IdempotencyStore.class);
        String hash = ContentHash.event("Concert", "Arena", STARTS_AT, 25, DEFINITION);
        when(idempotency.findEventCreation(ADMIN, KEY)).thenReturn(Mono.just(
                new IdempotencyRecord(ADMIN, KEY, "ghost", hash, NOW, NOW.plus(Duration.ofHours(24)))));
        when(catalog.findProvisioningSnapshot("ghost")).thenReturn(Mono.empty());
        EventManagementService service = new EventManagementService(
                catalog, idempotency, fixture.provisioningPublisher, fixture.clock, fixture.ids, fixture.settings);

        StepVerifier.create(service.createEvent(createEvent(KEY, STARTS_AT, 25, DEFINITION)))
                .expectError(IllegalStateException.class)
                .verify(Duration.ofSeconds(5));
    }

    // ------------------------------------------------------------------ provisioning status (API-006)

    @Test
    @DisplayName("FR-020 AC-039 the provisioning status reports the progress derived from confirmed batches")
    void reportsProvisioningProgress() {
        InventoryDefinition large = new InventoryDefinition(
                List.of(new Section("A", List.of(new Row("1", 1000), new Row("2", 50)))), List.of());
        Event event = Event.create("e-large", "Big", "Arena", STARTS_AT, 1050, large, NOW, InventoryLimits.DEPLOYED);
        fixture.store.seedSnapshot(new ProvisioningSnapshot(event, NOW, 3, null, null));

        ProvisioningStatusView status = value(fixture.eventManagement.getProvisioningStatus("e-large"));

        assertThat(status.provisioningStatus()).isEqualTo(ProvisioningStatus.PROVISIONING);
        assertThat(status.provisionedTickets()).isEqualTo(300);
        assertThat(status.complimentaryTickets()).isZero();

        fixture.store.seedSnapshot(new ProvisioningSnapshot(event, NOW, 11, null, null));
        assertThat(value(fixture.eventManagement.getProvisioningStatus("e-large")).provisionedTickets()).isEqualTo(1050);
    }

    @Test
    @DisplayName("AC-040 ERR-018 a FAILED Event is observed by the ADMIN with its functional cause")
    void reportsAFailedProvisioning() {
        Event failed = Event.create("e-failed", "F", "Arena", STARTS_AT, 25, DEFINITION, NOW, InventoryLimits.DEPLOYED).fail();
        fixture.store.seedSnapshot(new ProvisioningSnapshot(failed, NOW, 0, null, NOW.plusSeconds(90)));

        ProvisioningStatusView status = value(fixture.eventManagement.getProvisioningStatus("e-failed"));

        assertThat(status.provisioningStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(status.failureCause()).isEqualTo(ProvisioningStatusView.FailureCause.PROVISIONING_FAILED);
        assertThat(status.failedAt()).isEqualTo(NOW.plusSeconds(90));
        assertThat(status.provisionedTickets()).isZero();
    }

    @Test
    @DisplayName("ADR-035 the provisioning status of a non-existent Event is EVENT_NOT_FOUND")
    void provisioningStatusOfMissingEvent() {
        rejected(fixture.eventManagement.getProvisioningStatus("missing"), DomainErrorCode.EVENT_NOT_FOUND);
        invalid(fixture.eventManagement.getProvisioningStatus(" "));
    }
}
