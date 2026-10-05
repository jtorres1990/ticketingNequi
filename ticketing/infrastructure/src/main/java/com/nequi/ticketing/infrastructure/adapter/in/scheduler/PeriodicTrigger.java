package com.nequi.ticketing.infrastructure.adapter.in.scheduler;

import com.nequi.ticketing.application.port.in.CycleRequest;
import com.nequi.ticketing.application.port.in.CycleResult;
import com.nequi.ticketing.application.port.in.ItemOutcome;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;

/**
 * Independent trigger of one periodic process (CMP-014, ADR-028):
 * <ul>
 *   <li>its own timer on the scheduler, so that the duration or the failure of another process never delays it
 *       (rule 1, rule 2); the concurrency of the cycle is the one of its use case;</li>
 *   <li>first trigger after a random phase within the period (jitter between worker instances), then one trigger
 *       every period on a fixed grid anchored at the start of the last cycle;</li>
 *   <li>no overlap: a trigger that falls while the previous cycle is still running is skipped and the next cycle
 *       starts at the first trigger after the end of the running one (rule 2);</li>
 *   <li>a failed cycle is reported and the next one runs after a bounded growing wait (rule 3, IV-004); a cycle
 *       fails when it signals an error or when every candidate and index query it processed failed;</li>
 *   <li>each cycle receives its own correlation id and a random order of the index shards (ADR-028);</li>
 *   <li>a trigger is skipped while the process is paused (reversals with the Payment Mock circuit open,
 *       IV-016).</li>
 * </ul>
 * Nothing blocks: every wait is a timer on the scheduler (NFR-003). Exactly one timer or one cycle is pending
 * at a time, so the mutable state is confined to that chain.
 */
final class PeriodicTrigger {

    private static final CycleResult EMPTY_RESULT = new CycleResult(false, Map.of());
    private static final HexFormat HEX = HexFormat.of();

    private final PeriodicProcess process;
    private final PeriodicProcessSettings settings;
    private final Function<CycleRequest, Mono<CycleResult>> cycle;
    private final BooleanSupplier paused;
    private final Scheduler scheduler;
    private final SchedulerEvents events;
    private final RandomGenerator random;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicReference<Disposable> timer = new AtomicReference<>(Disposables.disposed());
    private final AtomicReference<Disposable> inFlight = new AtomicReference<>(Disposables.disposed());
    private final Sinks.Empty<Void> idle = Sinks.empty();
    private volatile boolean running;
    private volatile boolean busy;
    private volatile int consecutiveFailures;

    PeriodicTrigger(PeriodicProcess process, PeriodicProcessSettings settings,
            Function<CycleRequest, Mono<CycleResult>> cycle, BooleanSupplier paused, Scheduler scheduler,
            SchedulerEvents events, RandomGenerator random) {
        this.process = Objects.requireNonNull(process, "process");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.cycle = Objects.requireNonNull(cycle, "cycle");
        this.paused = Objects.requireNonNull(paused, "paused");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.events = Objects.requireNonNull(events, "events");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Schedules the first trigger after a random phase within the period; a second call has no effect. */
    void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        running = true;
        schedule(random.nextLong(settings.period().toMillis()));
    }

    /** No further cycle starts; completes when the cycle in flight, if any, has ended (ordered shutdown). */
    Mono<Void> stop() {
        running = false;
        timer.get().dispose();
        return Mono.defer(() -> busy ? idle.asMono() : Mono.empty());
    }

    /** No further cycle starts and the cycle in flight, if any, is cancelled. */
    void dispose() {
        running = false;
        timer.get().dispose();
        inFlight.get().dispose();
        idle.tryEmitEmpty();
    }

    boolean running() {
        return running;
    }

    private void fire() {
        if (!running) {
            return;
        }
        long startedAt = now();
        if (paused.getAsBoolean()) {
            notifySafely(() -> events.triggerPaused(process));
            next(settings.period().toMillis());
            return;
        }
        busy = true;
        if (!running) {
            becomeIdle();
            return;
        }
        CycleRequest request = new CycleRequest(correlationId(), shardOrder());
        Disposable subscription = Mono.defer(() -> cycle.apply(request))
                .defaultIfEmpty(EMPTY_RESULT)
                .subscribe(
                        result -> finish(request, startedAt, result, null),
                        error -> finish(request, startedAt, null, error));
        inFlight.set(subscription);
    }

    private void finish(CycleRequest request, long startedAt, CycleResult result, Throwable error) {
        long endedAt = now();
        busy = false;
        if (error != null || everyItemFailed(result)) {
            int failures = consecutiveFailures + 1;
            consecutiveFailures = failures;
            Duration wait = settings.failureWait(failures);
            notifySafely(() -> events.cycleFailed(process, request.correlationId(), error, result, failures, wait));
            next(wait.toMillis());
            return;
        }
        consecutiveFailures = 0;
        long period = settings.period().toMillis();
        long elapsed = endedAt - startedAt;
        long periods = Math.max(1, Math.ceilDiv(elapsed, period));
        notifySafely(() -> events.cycleCompleted(process, request.correlationId(), result, Duration.ofMillis(elapsed)));
        if (periods > 1) {
            notifySafely(() -> events.triggersSkipped(process, periods - 1));
        }
        next(startedAt + periods * period - endedAt);
    }

    private static boolean everyItemFailed(CycleResult result) {
        long failed = result.count(ItemOutcome.FAILED);
        return failed > 0 && failed == result.total();
    }

    private void next(long delayMillis) {
        if (running) {
            schedule(delayMillis);
        } else {
            becomeIdle();
        }
    }

    private void schedule(long delayMillis) {
        try {
            timer.set(scheduler.schedule(this::fire, Math.max(0, delayMillis), TimeUnit.MILLISECONDS));
        } catch (RejectedExecutionException rejected) {
            // the scheduler is shutting down: the process cannot run any more
            running = false;
            becomeIdle();
        }
    }

    private void becomeIdle() {
        busy = false;
        idle.tryEmitEmpty();
    }

    private long now() {
        return scheduler.now(TimeUnit.MILLISECONDS);
    }

    private String correlationId() {
        return HEX.toHexDigits(random.nextLong()) + HEX.toHexDigits(random.nextLong() | 1L);
    }

    /** A random permutation of the shards of the process (Fisher-Yates); empty for a process without shards. */
    private List<Integer> shardOrder() {
        List<Integer> shards = new ArrayList<>(process.shardCount());
        for (int shard = 0; shard < process.shardCount(); shard++) {
            shards.add(shard);
        }
        for (int index = shards.size() - 1; index > 0; index--) {
            Collections.swap(shards, index, random.nextInt(index + 1));
        }
        return shards;
    }

    private static void notifySafely(Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException ignored) {
            // an observation hook must never stop a periodic process (ADR-028)
        }
    }
}
