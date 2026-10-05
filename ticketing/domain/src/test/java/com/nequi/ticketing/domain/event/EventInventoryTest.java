package com.nequi.ticketing.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.domain.error.InvalidStateTransitionException;
import com.nequi.ticketing.domain.error.ValidationException;
import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import com.nequi.ticketing.domain.event.InventoryDefinition.ComplimentaryRange;
import com.nequi.ticketing.domain.event.InventoryDefinition.Row;
import com.nequi.ticketing.domain.event.InventoryDefinition.Section;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class EventInventoryTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("VAL-006..VAL-009, VAL-013 creates deterministic tickets and batches")
    void validatesAndGeneratesDeterministicInventory() {
        var definition = new InventoryDefinition(
                List.of(
                        new Section("B", List.of(new Row("2", 1))),
                        new Section("A", List.of(new Row("2", 2), new Row("1", 2)))),
                List.of(new ComplimentaryRange("A", "1", 2, 2)));

        var validated = definition.validate(5, InventoryLimits.DEPLOYED);

        assertThat(validated.tickets()).extracting(InventoryDefinition.TicketSeed::ticketId)
                .containsExactly("A-1-1", "A-1-2", "A-2-1", "A-2-2", "B-2-1");
        assertThat(validated.tickets().get(1).complimentary()).isTrue();
        assertThat(validated.complimentaryCount()).isEqualTo(1);
        assertThat(validated.batches()).hasSize(1);
    }

    @Test
    @DisplayName("BR-021 provisioning uses deterministic batches of 100")
    void batchesOneHundredTickets() {
        var definition = definition(201);

        var batches = definition.validate(201, InventoryLimits.DEPLOYED).batches();

        assertThat(batches).extracting(List::size).containsExactly(100, 100, 1);
    }

    @ParameterizedTest(name = "capacity {0} yields {1} availability shards")
    @MethodSource("shardCases")
    @DisplayName("ADR-022 computes bounded availability shards")
    void computesAvailabilityShards(int capacity, int expected) {
        assertThat(ShardingPolicy.availabilityShards(capacity)).isEqualTo(expected);
    }

    static Stream<Arguments> shardCases() {
        return Stream.of(Arguments.of(1, 1), Arguments.of(2_000, 1), Arguments.of(2_001, 2),
                Arguments.of(50_000, 25), Arguments.of(100_000, 32));
    }

    @Test
    @DisplayName("ADR-022 stable hash always returns the same bounded shard")
    void stableHashIsDeterministic() {
        int first = ShardingPolicy.shard("order-123", ShardingPolicy.DEPLOYED.reservationShards());
        assertThat(ShardingPolicy.shard("order-123", ShardingPolicy.DEPLOYED.reservationShards())).isEqualTo(first);
        assertThat(first).isBetween(0, 7);
        assertThatThrownBy(() -> ShardingPolicy.shard("order", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("DS-011..DS-013 and ST-011..ST-013 allow only terminal provisioning transitions")
    void eventProvisioningLifecycleIsImmutableAfterTerminalState() {
        Event event = event();
        Event enabled = event.enable(2);
        Event failed = event.fail();

        assertThat(event.provisioningStatus()).isEqualTo(ProvisioningStatus.PROVISIONING);
        assertThat(enabled.provisioningStatus().terminal()).isTrue();
        assertThat(failed.provisioningStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThatThrownBy(() -> enabled.fail()).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> event.enable(1)).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("BR-022, BR-025 and BR-027 separate catalog and availability visibility")
    void evaluatesEventVisibilityAndPastBoundary() {
        Event provisioning = event();
        Event enabled = provisioning.enable(2);
        Instant start = enabled.startsAt();

        assertThat(provisioning.availabilityIsVisible()).isFalse();
        assertThat(enabled.availabilityIsVisible()).isTrue();
        assertThat(enabled.visibleAt(start.minusNanos(1))).isTrue();
        assertThat(enabled.visibleAt(start)).isFalse();
        assertThat(enabled.isPastAt(start)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    @DisplayName("VAL-006 rejects blank event fields")
    void rejectsBlankEventFields(String blank) {
        assertThatThrownBy(() -> Event.create("event", blank, "venue", NOW.plusSeconds(60), 2,
                definition(2), NOW, InventoryLimits.DEPLOYED)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Event.create("event", "name", blank, NOW.plusSeconds(60), 2,
                definition(2), NOW, InventoryLimits.DEPLOYED)).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("VAL-007 rejects non-future startsAt")
    void rejectsNonFutureStart() {
        assertThatThrownBy(() -> Event.create("event", "name", "venue", NOW, 2,
                definition(2), NOW, InventoryLimits.DEPLOYED)).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("ERR-011 rejects capacity, definition limits and duplicates")
    void rejectsInvalidCompactDefinitions() {
        assertThatThrownBy(() -> definition(1).validate(0, InventoryLimits.DEPLOYED))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> definition(1).validate(50_001, InventoryLimits.DEPLOYED))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new InventoryDefinition(List.of(), List.of()).validate(1, InventoryLimits.DEPLOYED))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new InventoryDefinition(
                List.of(new Section("A", List.of(new Row("1", 1))),
                        new Section("A", List.of(new Row("2", 1)))), List.of()).validate(2, InventoryLimits.DEPLOYED))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new InventoryDefinition(
                List.of(new Section("A", List.of(new Row("1", 1), new Row("1", 1)))), List.of())
                .validate(2, InventoryLimits.DEPLOYED)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> definition(2).validate(1, InventoryLimits.DEPLOYED))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("VAL-013 enforces rows, seats and complimentary range limits")
    void enforcesConfiguredDefinitionLimits() {
        var tiny = new InventoryLimits(10, 1, 1, 2, 1, 1);
        assertThatThrownBy(() -> definition(3).validate(3, tiny)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new InventoryDefinition(
                List.of(new Section("A", List.of(new Row("1", 1), new Row("2", 1)))), List.of())
                .validate(2, tiny)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new InventoryDefinition(
                List.of(new Section("A", List.of(new Row("1", 1)))),
                List.of(new ComplimentaryRange("A", "1", 1, 1), new ComplimentaryRange("A", "1", 1, 1)))
                .validate(1, tiny)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new InventoryLimits(0, 1, 1, 1, 0, 1))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("ADR-024 rejects missing, invalid and overlapping complimentary ranges")
    void rejectsInvalidComplimentaryRanges() {
        assertThatThrownBy(() -> withRanges(new ComplimentaryRange("X", "1", 1, 1)).validate(2, InventoryLimits.DEPLOYED))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> withRanges(new ComplimentaryRange("A", "1", 0, 1)).validate(2, InventoryLimits.DEPLOYED))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> withRanges(new ComplimentaryRange("A", "1", 2, 1)).validate(2, InventoryLimits.DEPLOYED))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> withRanges(new ComplimentaryRange("A", "1", 1, 2),
                new ComplimentaryRange("A", "1", 2, 2)).validate(2, InventoryLimits.DEPLOYED))
                .isInstanceOf(ValidationException.class);
    }

    private static Event event() {
        return Event.create("event-1", "Concert", "Arena", NOW.plusSeconds(3600), 2,
                definition(2), NOW, InventoryLimits.DEPLOYED);
    }

    private static InventoryDefinition definition(int seats) {
        return new InventoryDefinition(List.of(new Section("A", List.of(new Row("1", seats)))), List.of());
    }

    private static InventoryDefinition withRanges(ComplimentaryRange... ranges) {
        return new InventoryDefinition(List.of(new Section("A", List.of(new Row("1", 2)))), List.of(ranges));
    }
}
