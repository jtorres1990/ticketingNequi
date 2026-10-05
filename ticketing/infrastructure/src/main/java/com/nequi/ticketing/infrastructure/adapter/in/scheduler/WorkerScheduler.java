package com.nequi.ticketing.infrastructure.adapter.in.scheduler;

import com.nequi.ticketing.application.port.in.CleanUpProvisioningUseCase;
import com.nequi.ticketing.application.port.in.ExpireReservationsUseCase;
import com.nequi.ticketing.application.port.in.RepublishPendingOrdersUseCase;
import com.nequi.ticketing.application.port.in.ReversePaymentsUseCase;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.ConsumptionGate;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * CMP-014 Periodic scheduler adapter of the {@code worker} role (FR-011, BR-030, ERR-003, NFR-003; ADR-028):
 * four independent triggers, one per process, each with its own timer, period and failure wait:
 * <ul>
 *   <li>Expire Reservations (CMP-008, AP-016) every 5 s;</li>
 *   <li>Republish pending Orders (CMP-023, AP-028) every 10 s (the use case skips the cycle while the
 *       publication circuit is open, ADR-026);</li>
 *   <li>Reverse payments (CMP-024, AP-029) every 10 s, paused while the reversal gate is paused, that is, while
 *       the Payment Mock circuit is open and rejecting calls ({@code CircuitGateBinding}): a cancellation that
 *       is not confirmed consumes one of the 10 reversal attempts (ADR-025, ADR-035, IV-016). With the circuit
 *       half-open the gate is not paused and the cycle runs, so that its cancellations are valid probe
 *       calls;</li>
 *   <li>Clean up provisioning (CMP-015, AP-018) every 60 s.</li>
 * </ul>
 * The concurrency of each process (16 / 8 / 4 / 2) is applied by its use case. A slow or failing process never
 * delays another one: they share no timer, no cycle and no lock, and nothing blocks a thread. Composition and
 * activation in the {@code worker} role belong to the bootstrap module (INC-010).
 */
public final class WorkerScheduler implements Disposable {

    private final List<PeriodicTrigger> triggers;
    private volatile boolean disposed;

    private WorkerScheduler(List<PeriodicTrigger> triggers) {
        this.triggers = triggers;
    }

    /** Scheduler on {@link Schedulers#parallel()} with random phases, shard orders and correlation ids. */
    public static WorkerScheduler create(
            ExpireReservationsUseCase expiration,
            RepublishPendingOrdersUseCase republish,
            ReversePaymentsUseCase reversal,
            CleanUpProvisioningUseCase provisioningCleanup,
            ConsumptionGate reversalGate,
            WorkerSchedulerSettings settings,
            SchedulerEvents events) {
        return create(expiration, republish, reversal, provisioningCleanup, reversalGate, settings, events,
                Schedulers.parallel(), new SplittableRandom());
    }

    /**
     * Variant with an explicit timer scheduler (virtual time in tests) and source of randomness; each trigger
     * derives its own generator from {@code random} when it is created.
     */
    public static WorkerScheduler create(
            ExpireReservationsUseCase expiration,
            RepublishPendingOrdersUseCase republish,
            ReversePaymentsUseCase reversal,
            CleanUpProvisioningUseCase provisioningCleanup,
            ConsumptionGate reversalGate,
            WorkerSchedulerSettings settings,
            SchedulerEvents events,
            Scheduler scheduler,
            RandomGenerator random) {
        Objects.requireNonNull(expiration, "expiration");
        Objects.requireNonNull(republish, "republish");
        Objects.requireNonNull(reversal, "reversal");
        Objects.requireNonNull(provisioningCleanup, "provisioningCleanup");
        Objects.requireNonNull(reversalGate, "reversalGate");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(random, "random");
        return new WorkerScheduler(List.of(
                new PeriodicTrigger(PeriodicProcess.EXPIRATION, settings.shardCount(PeriodicProcess.EXPIRATION),
                        settings.expiration(), expiration::expireDue, () -> false, scheduler, events,
                        new SplittableRandom(random.nextLong())),
                new PeriodicTrigger(PeriodicProcess.REPUBLISH, settings.shardCount(PeriodicProcess.REPUBLISH),
                        settings.republish(), republish::republishPending, () -> false, scheduler, events,
                        new SplittableRandom(random.nextLong())),
                new PeriodicTrigger(PeriodicProcess.REVERSAL, settings.shardCount(PeriodicProcess.REVERSAL),
                        settings.reversal(), reversal::reverseDue, reversalGate::isPaused, scheduler, events,
                        new SplittableRandom(random.nextLong())),
                new PeriodicTrigger(PeriodicProcess.PROVISIONING_CLEANUP,
                        settings.shardCount(PeriodicProcess.PROVISIONING_CLEANUP), settings.provisioningCleanup(),
                        provisioningCleanup::cleanUp, () -> false, scheduler, events,
                        new SplittableRandom(random.nextLong()))));
    }

    /** Starts the four triggers; each one fires for the first time after a random phase within its period. */
    public void start() {
        triggers.forEach(PeriodicTrigger::start);
    }

    /** No further cycle starts; completes when the cycles in flight have ended (ordered shutdown, ADR-037). */
    public Mono<Void> stop() {
        return Mono.when(triggers.stream().map(PeriodicTrigger::stop).toList());
    }

    /** Stops the triggers and cancels the cycles in flight. */
    @Override
    public void dispose() {
        disposed = true;
        triggers.forEach(PeriodicTrigger::dispose);
    }

    @Override
    public boolean isDisposed() {
        return disposed;
    }
}
