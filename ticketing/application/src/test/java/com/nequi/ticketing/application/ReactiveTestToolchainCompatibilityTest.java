package com.nequi.ticketing.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ReactiveTestToolchainCompatibilityTest {

    @Test
    @DisplayName("TC-014 Mockito inline mock maker works on Java 25 without dynamic agent loading")
    void mockitoMocksFinalTypesWithTheConfiguredAgent() {
        FinalDependency dependency = mock(FinalDependency.class);
        when(dependency.value()).thenReturn("mocked");

        StepVerifier.create(Mono.fromSupplier(dependency::value))
                .expectNext("mocked")
                .verifyComplete();
    }

    @Test
    @DisplayName("TC-014 reactor-test controls virtual time on Java 25")
    void reactorTestControlsVirtualTime() {
        StepVerifier.withVirtualTime(() -> Mono.delay(Duration.ofHours(1)))
                .expectSubscription()
                .thenAwait(Duration.ofHours(1))
                .expectNext(0L)
                .verifyComplete();
    }

    private static final class FinalDependency {
        String value() {
            return "real";
        }
    }
}

