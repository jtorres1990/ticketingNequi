package com.nequi.ticketing.infrastructure.adapter.in.scheduler;

import java.time.Duration;
import java.util.Objects;

/**
 * Scheduling of the four periodic processes of the {@code worker} role (CMP-014). {@link #DEPLOYED} holds the
 * approved values: periods 5 s / 10 s / 10 s / 60 s (ADR-028, ADR-026, ADR-025, ADR-024; AV-003) and the bounded
 * growing wait after a failed cycle (IV-004): expiration 1 s up to 5 s, so that it keeps the 15 s release
 * deadline; the other processes their own period up to 5 times the period. The concurrency of each process
 * (16 / 8 / 4 / 2) is applied by its use case ({@code WorkerUseCaseSettings}). The bootstrap module binds these
 * values from configuration (INC-010).
 */
public record WorkerSchedulerSettings(
        PeriodicProcessSettings expiration,
        PeriodicProcessSettings republish,
        PeriodicProcessSettings reversal,
        PeriodicProcessSettings provisioningCleanup) {

    public static final WorkerSchedulerSettings DEPLOYED = new WorkerSchedulerSettings(
            new PeriodicProcessSettings(Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(5)),
            new PeriodicProcessSettings(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(50)),
            new PeriodicProcessSettings(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(50)),
            new PeriodicProcessSettings(Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(300)));

    public WorkerSchedulerSettings {
        Objects.requireNonNull(expiration, "expiration");
        Objects.requireNonNull(republish, "republish");
        Objects.requireNonNull(reversal, "reversal");
        Objects.requireNonNull(provisioningCleanup, "provisioningCleanup");
    }

    public PeriodicProcessSettings of(PeriodicProcess process) {
        return switch (Objects.requireNonNull(process, "process")) {
            case EXPIRATION -> expiration;
            case REPUBLISH -> republish;
            case REVERSAL -> reversal;
            case PROVISIONING_CLEANUP -> provisioningCleanup;
        };
    }
}
