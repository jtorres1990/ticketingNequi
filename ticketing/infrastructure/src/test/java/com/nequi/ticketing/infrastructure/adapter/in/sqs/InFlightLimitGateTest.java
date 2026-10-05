package com.nequi.ticketing.infrastructure.adapter.in.sqs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** In-flight limit of the switchable gate used while the Payment Mock circuit is half-open (ADR-035, ADR-039). */
class InFlightLimitGateTest {

    private final SwitchableConsumptionGate gate = new SwitchableConsumptionGate();

    @Test
    @DisplayName("ADR-035 limitInFlight: at most N permits outstanding; a finished message returns its permit, capped at N")
    void limitInFlight() {
        AtomicInteger changes = new AtomicInteger();
        gate.addListener(changes::incrementAndGet);
        gate.limitInFlight(3);

        assertThat(changes.get()).isEqualTo(1);
        assertThat(gate.isPaused()).isFalse();
        assertThat(gate.acquire(10)).isEqualTo(3);
        assertThat(gate.acquire(1)).isZero();
        gate.completed(1);
        assertThat(gate.acquire(10)).isEqualTo(1);
        gate.release(1);
        gate.completed(5);
        assertThat(gate.acquire(10)).isEqualTo(3);
        gate.completed(0);
        assertThat(gate.acquire(0)).isZero();
        assertThatThrownBy(() -> gate.limitInFlight(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ADR-035 completions only return permits while the in-flight limit is active")
    void completionsOutsideTheLimit() {
        gate.probe(2);
        assertThat(gate.acquire(5)).isEqualTo(2);
        gate.completed(2);
        assertThat(gate.acquire(5)).isZero();

        gate.pause();
        gate.completed(1);
        assertThat(gate.acquire(5)).isZero();
        gate.open();
        gate.completed(1);
        assertThat(gate.acquire(5)).isEqualTo(5);
        ConsumptionGate.alwaysOpen().completed(1);
    }
}
