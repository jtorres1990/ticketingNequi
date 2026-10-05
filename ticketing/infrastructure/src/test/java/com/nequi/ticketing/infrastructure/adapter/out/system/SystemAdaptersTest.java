package com.nequi.ticketing.infrastructure.adapter.out.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** CMP-021: Clock and Id generator adapters. */
class SystemAdaptersTest {

    @Test
    @DisplayName("CMP-021 the Clock adapter reads the UTC instant of the configured clock")
    void clock() {
        Instant fixed = Instant.parse("2026-11-01T12:00:00Z");
        assertThat(new SystemClock(Clock.fixed(fixed, ZoneOffset.UTC)).now()).isEqualTo(fixed);
        assertThat(new SystemClock().now()).isCloseTo(Instant.now(), org.assertj.core.api.Assertions.within(
                5, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("CMP-021 ADR-032 NFR-003 identifiers are random version 4 UUIDs generated without blocking on reactive threads")
    void randomIdentifiers() {
        RandomUuidGenerator generator = new RandomUuidGenerator();
        Set<String> ids = new HashSet<>();
        StepVerifier.create(Flux.range(0, 500).map(index -> index % 2 == 0 ? generator.newOrderId() : generator.newEventId())
                        .subscribeOn(Schedulers.parallel()))
                .recordWith(() -> ids)
                .expectNextCount(500)
                .verifyComplete();
        assertThat(ids).hasSize(500).allSatisfy(id -> {
            UUID uuid = UUID.fromString(id);
            assertThat(uuid.version()).isEqualTo(4);
            assertThat(uuid.variant()).isEqualTo(2);
        });
        assertThat(new RandomUuidGenerator(new SecureRandom()).newOrderId()).hasSize(36);
        assertThat(RandomUuidGenerator.defaultRandom()).isNotNull();
    }
}
