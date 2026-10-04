package com.nequi.ticketing.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.domain.error.ValidationException;
import com.nequi.ticketing.domain.event.InventoryDefinition.ComplimentaryRange;
import com.nequi.ticketing.domain.event.InventoryDefinition.Row;
import com.nequi.ticketing.domain.event.InventoryDefinition.Section;
import com.nequi.ticketing.domain.shared.IdempotencyKey;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class InventoryLookupTest {

    private static final InventoryDefinition DEFINITION = new InventoryDefinition(
            List.of(
                    new Section("A", List.of(new Row("1", 12), new Row("10", 2))),
                    new Section("VIP", List.of(new Row("R", 3)))),
            List.of(new ComplimentaryRange("A", "1", 1, 2), new ComplimentaryRange("VIP", "R", 3, 3)));

    @ParameterizedTest(name = "{0} belongs to the definition")
    @ValueSource(strings = {"A-1-1", "A-1-12", "A-10-2", "VIP-R-3"})
    @DisplayName("BR-033 recognises ticket identifiers generated from the definition")
    void recognisesGeneratedTicketIds(String ticketId) {
        assertThat(DEFINITION.containsTicket(ticketId)).isTrue();
    }

    @ParameterizedTest(name = "{0} does not belong to the definition")
    @ValueSource(strings = {"A-1-13", "A-1-0", "A-1-01", "A-10-3", "B-1-1", "VIP-R-", "VIP-R-x",
            "A-2-1", "A1-1", "A-1-12345", "VIP-R-٣", ""})
    @NullSource
    @DisplayName("ADR-023 rejects identifiers that match no seat of the definition")
    void rejectsUnknownTicketIds(String ticketId) {
        assertThat(DEFINITION.containsTicket(ticketId)).isFalse();
    }

    @Test
    @DisplayName("ADR-040 exposes the section codes usable as availability filter")
    void exposesSectionCodes() {
        assertThat(DEFINITION.sectionCodes()).containsExactly("A", "VIP");
        assertThat(DEFINITION.definesSection("VIP")).isTrue();
        assertThat(DEFINITION.definesSection("B")).isFalse();
        assertThat(DEFINITION.definesSection(null)).isFalse();
    }

    @Test
    @DisplayName("DS-005 counts complimentary seats from the validated ranges")
    void countsComplimentarySeats() {
        assertThat(DEFINITION.complimentarySeatCount()).isEqualTo(3);
        assertThat(DEFINITION.validate(17, InventoryLimits.DEPLOYED).complimentaryCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("VAL-016 validates the Idempotency-Key format shared by purchases and Event creation")
    void validatesIdempotencyKeyFormat() {
        assertThat(IdempotencyKey.validate("admin-key-000000001")).isEqualTo("admin-key-000000001");
        assertThatThrownBy(() -> IdempotencyKey.validate("short")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> IdempotencyKey.validate("invalid key with spaces"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> IdempotencyKey.validate(" ")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> IdempotencyKey.validate(null)).isInstanceOf(ValidationException.class);
    }
}
