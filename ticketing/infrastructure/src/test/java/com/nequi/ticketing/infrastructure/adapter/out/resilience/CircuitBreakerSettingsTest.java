package com.nequi.ticketing.infrastructure.adapter.out.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Approved circuit values (ADR-035) and validation of the slow-call criterion. */
class CircuitBreakerSettingsTest {

    @Test
    @DisplayName("ADR-035 Payment Mock circuit: 20 / 10 / 50 % of failures or of calls > 2 s / 15 s / 3 probes")
    void paymentMock() {
        CircuitBreakerSettings settings = CircuitBreakerSettings.paymentMock();

        assertThat(settings.slidingWindowSize()).isEqualTo(20);
        assertThat(settings.minimumNumberOfCalls()).isEqualTo(10);
        assertThat(settings.failureRateThresholdPercent()).isEqualTo(50f);
        assertThat(settings.openDuration()).isEqualTo(Duration.ofSeconds(15));
        assertThat(settings.halfOpenProbeCalls()).isEqualTo(3);
        assertThat(settings.slowCallsOpenTheCircuit()).isTrue();
        assertThat(settings.slowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(2));
        assertThat(settings.slowCallRateThresholdPercent()).isEqualTo(50f);
    }

    @Test
    @DisplayName("ADR-035 SQS publication circuit has no slow-call criterion")
    void sqsPublicationWithoutSlowCalls() {
        CircuitBreakerSettings settings = CircuitBreakerSettings.sqsPublication();

        assertThat(settings.slowCallsOpenTheCircuit()).isFalse();
        assertThat(settings.slowCallDurationThreshold()).isNull();
        assertThat(settings.slowCallRateThresholdPercent()).isZero();
    }

    @Test
    @DisplayName("ADR-035 an incomplete or out-of-range slow-call criterion is rejected")
    void invalidSlowCalls() {
        Duration open = Duration.ofSeconds(15);
        assertThatThrownBy(() -> new CircuitBreakerSettings(20, 10, 50f, open, 3, null, 50f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreakerSettings(20, 10, 50f, open, 3, Duration.ZERO, 50f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreakerSettings(20, 10, 50f, open, 3, Duration.ofSeconds(2), 0f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreakerSettings(20, 10, 50f, open, 3, Duration.ofSeconds(2), 101f))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
