package com.nequi.ticketing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Java25CompatibilityTest {

    @Test
    @DisplayName("TC-001 Java 25 compiles records, sealed types, and pattern matching")
    void java25CompilesApprovedLanguageFeatures() {
        Outcome outcome = new Success("ready");

        String value = switch (outcome) {
            case Success success -> success.value();
            case Failure failure -> failure.reason();
        };

        assertThat(value).isEqualTo("ready");
    }

    private sealed interface Outcome permits Success, Failure {
    }

    private record Success(String value) implements Outcome {
    }

    private record Failure(String reason) implements Outcome {
    }
}

