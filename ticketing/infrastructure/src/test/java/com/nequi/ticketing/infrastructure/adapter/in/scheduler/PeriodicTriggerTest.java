package com.nequi.ticketing.infrastructure.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.application.port.in.CycleRequest;
import com.nequi.ticketing.application.port.in.CycleResult;
import com.nequi.ticketing.application.port.in.ItemOutcome;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.test.scheduler.VirtualTimeScheduler;

/**
 * One trigger of CMP-014 with virtual time (ADR-028, IV-004): random phase, fixed period, no overlap, bounded
 * growing wait after a failed cycle, pause, shard order and correlation, ordered shutdown.
 */
class PeriodicTriggerTest {

    private static final PeriodicProcessSettings EXPIRATION = WorkerSchedulerSettings.DEPLOYED.expiration();
    private static final PeriodicProcessSettings REVERSAL = WorkerSchedulerSettings.DEPLOYED.reversal();

    private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
    private final RecordingSchedulerEvents events = new RecordingSchedulerEvents();
    private final List<Long> starts = new CopyOnWriteArrayList<>();
    private final List<CycleRequest> requests = new CopyOnWriteArrayList<>();
    private final Deque<Supplier<Mono<CycleResult>>> script = new ArrayDeque<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private Supplier<Mono<CycleResult>> fallback = () -> Mono.just(result(ItemOutcome.EXPIRED, 1));

    @AfterEach
    void tearDown() {
        scheduler.dispose();
    }

    @Test
    @DisplayName("ADR-028 first trigger after the random phase, then one cycle every period (5 s)")
    void periodicity() {
        PeriodicTrigger trigger = trigger(PeriodicProcess.EXPIRATION, EXPIRATION, new FixedRandom(2_000));
        trigger.start();

        scheduler.advanceTimeBy(Duration.ofMillis(1_999));
        assertThat(starts).isEmpty();
        scheduler.advanceTimeBy(Duration.ofMillis(20_001));

        assertThat(starts).containsExactly(2_000L, 7_000L, 12_000L, 17_000L, 22_000L);
        assertThat(events.completed).hasSize(5);
        assertThat(events.failed).isEmpty();
        assertThat(events.skipped).isEmpty();
        assertThat(trigger.running()).isTrue();
    }

    @Test
    @DisplayName("ADR-028 jitter: the phase of each instance is random and stays within the period")
    void randomPhaseWithinThePeriod() {
        Set<Long> firstStarts = new HashSet<>();
        for (int instance = 0; instance < 20; instance++) {
            List<Long> instanceStarts = new ArrayList<>();
            PeriodicTrigger trigger = new PeriodicTrigger(PeriodicProcess.EXPIRATION, EXPIRATION,
                    request -> Mono.fromRunnable(() -> instanceStarts.add(now())).thenReturn(result(ItemOutcome.EXPIRED, 1)),
                    () -> false, scheduler, SchedulerEvents.NONE, new SplittableRandom(instance));
            long startedAt = now();
            trigger.start();
            scheduler.advanceTimeBy(Duration.ofMillis(4_999));
            assertThat(instanceStarts).hasSize(1);
            firstStarts.add(instanceStarts.getFirst() - startedAt);
            trigger.dispose();
            scheduler.advanceTimeBy(Duration.ofSeconds(1));
        }
        assertThat(firstStarts).allMatch(phase -> phase >= 0 && phase < 5_000);
        assertThat(firstStarts).hasSizeGreaterThan(10);
    }

    @Test
    @DisplayName("ADR-028 no overlap: triggers that fall while a cycle runs are skipped; the next cycle starts at the first trigger after its end")
    void noOverlap() {
        fallback = () -> Mono.delay(Duration.ofSeconds(12), scheduler).thenReturn(result(ItemOutcome.EXPIRED, 1));
        PeriodicTrigger trigger = trigger(PeriodicProcess.EXPIRATION, EXPIRATION, new FixedRandom(0));
        trigger.start();

        scheduler.advanceTimeBy(Duration.ofSeconds(44));

        assertThat(starts).containsExactly(0L, 15_000L, 30_000L);
        assertThat(maxInFlight).hasValue(1);
        assertThat(events.skipped).hasSize(3)
                .containsOnly(new RecordingSchedulerEvents.Skipped(PeriodicProcess.EXPIRATION, 2));
        assertThat(events.completed).extracting(RecordingSchedulerEvents.Completed::duration)
                .containsOnly(Duration.ofSeconds(12));
    }

    @Test
    @DisplayName("ADR-028 a cycle that ends exactly at the next trigger is followed at once, without skipping")
    void cycleEndingAtTheTrigger() {
        fallback = () -> Mono.delay(Duration.ofSeconds(5), scheduler).thenReturn(result(ItemOutcome.EXPIRED, 1));
        trigger(PeriodicProcess.EXPIRATION, EXPIRATION, new FixedRandom(0)).start();

        scheduler.advanceTimeBy(Duration.ofSeconds(12));

        assertThat(starts).containsExactly(0L, 5_000L, 10_000L);
        assertThat(events.skipped).isEmpty();
    }

    @Test
    @DisplayName("ADR-028 rule 3 IV-004 expiration: failed cycles retry after 1, 2, 4, 5, 5 s; a success returns to the 5 s period")
    void expirationFailureBackoff() {
        for (int failure = 0; failure < 5; failure++) {
            script.add(() -> Mono.error(new IllegalStateException("index unavailable")));
        }
        trigger(PeriodicProcess.EXPIRATION, EXPIRATION, new FixedRandom(0)).start();

        scheduler.advanceTimeBy(Duration.ofSeconds(27));

        assertThat(starts).containsExactly(0L, 1_000L, 3_000L, 7_000L, 12_000L, 17_000L, 22_000L, 27_000L);
        assertThat(events.failed).extracting(RecordingSchedulerEvents.Failed::consecutiveFailures)
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(events.failed).extracting(RecordingSchedulerEvents.Failed::nextAttemptIn).containsExactly(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(5),
                Duration.ofSeconds(5));
        assertThat(events.failed).allSatisfy(failed -> {
            assertThat(failed.cause()).hasMessage("index unavailable");
            assertThat(failed.result()).isNull();
        });
        assertThat(events.completed).hasSize(3);
    }

    @Test
    @DisplayName("ADR-028 rule 3 a cycle fails when every candidate and query failed; mixed, skipped and empty cycles do not")
    void failureClassification() {
        script.add(() -> Mono.just(result(ItemOutcome.FAILED, 8)));
        script.add(() -> Mono.just(new CycleResult(false, Map.of(ItemOutcome.FAILED, 1L, ItemOutcome.NOT_APPLICABLE, 1L))));
        script.add(() -> Mono.just(CycleResult.skippedCycle()));
        script.add(Mono::empty);
        script.add(() -> Mono.just(new CycleResult(false, Map.of())));
        trigger(PeriodicProcess.REVERSAL, REVERSAL, new FixedRandom(0)).start();

        scheduler.advanceTimeBy(Duration.ofSeconds(50));

        assertThat(starts).containsExactly(0L, 10_000L, 20_000L, 30_000L, 40_000L, 50_000L);
        assertThat(events.failed).singleElement().satisfies(failed -> {
            assertThat(failed.cause()).isNull();
            assertThat(failed.result().count(ItemOutcome.FAILED)).isEqualTo(8);
            assertThat(failed.nextAttemptIn()).isEqualTo(Duration.ofSeconds(10));
        });
        assertThat(events.completed).extracting(completed -> completed.result().skipped())
                .containsExactly(false, true, false, false, false);
    }

    @Test
    @DisplayName("ADR-028 rule 3 a use case that throws instead of signalling is a failed cycle")
    void synchronousThrowIsAFailure() {
        PeriodicTrigger trigger = new PeriodicTrigger(PeriodicProcess.EXPIRATION, EXPIRATION, request -> {
            starts.add(now());
            throw new IllegalStateException("thrown");
        }, () -> false, scheduler, events, new FixedRandom(0));
        trigger.start();

        scheduler.advanceTimeBy(Duration.ofSeconds(3));

        assertThat(starts).containsExactly(0L, 1_000L, 3_000L);
        assertThat(events.failed).extracting(failed -> failed.cause().getMessage()).containsOnly("thrown");
    }

    @Test
    @DisplayName("IV-016 a paused process skips its triggers without invoking the use case and resumes at the next trigger")
    void pause() {
        AtomicBoolean paused = new AtomicBoolean(true);
        trigger(PeriodicProcess.REVERSAL, REVERSAL, new FixedRandom(0), paused::get).start();

        scheduler.advanceTimeBy(Duration.ofSeconds(35));
        assertThat(starts).isEmpty();
        assertThat(events.paused).containsExactly(PeriodicProcess.REVERSAL, PeriodicProcess.REVERSAL,
                PeriodicProcess.REVERSAL, PeriodicProcess.REVERSAL);

        paused.set(false);
        scheduler.advanceTimeBy(Duration.ofSeconds(15));
        assertThat(starts).containsExactly(40_000L, 50_000L);
        assertThat(events.failed).isEmpty();
    }

    @Test
    @DisplayName("ADR-028 each cycle visits every shard in a random order and has its own correlation id")
    void shardOrderAndCorrelation() {
        trigger(PeriodicProcess.EXPIRATION, EXPIRATION, new SplittableRandom(7)).start();
        scheduler.advanceTimeBy(Duration.ofSeconds(60));

        assertThat(requests).hasSizeGreaterThanOrEqualTo(12);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.shardOrder()).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5, 6, 7);
            assertThat(request.correlationId()).matches("[0-9a-f]{32}");
        });
        assertThat(requests.stream().map(CycleRequest::shardOrder).distinct().count()).isGreaterThan(1);
        assertThat(requests.stream().map(CycleRequest::correlationId).distinct().count()).isEqualTo(requests.size());
        assertThat(events.completed).extracting(RecordingSchedulerEvents.Completed::correlationId)
                .containsExactlyElementsOf(requests.stream().map(CycleRequest::correlationId).toList());
    }

    @Test
    @DisplayName("ADR-028 shard counts: RESV# 8, PENDQ# 8, REVERSAL# 4; the provisioning cleanup has no shards")
    void shardCounts() {
        for (PeriodicProcess process : PeriodicProcess.values()) {
            requests.clear();
            PeriodicTrigger trigger = trigger(process, WorkerSchedulerSettings.DEPLOYED.of(process), new FixedRandom(0));
            trigger.start();
            scheduler.advanceTime();
            assertThat(requests).singleElement().satisfies(request ->
                    assertThat(request.shardOrder()).hasSize(process.shardCount()));
            trigger.dispose();
        }
        assertThat(List.of(PeriodicProcess.EXPIRATION.shardCount(), PeriodicProcess.REPUBLISH.shardCount(),
                PeriodicProcess.REVERSAL.shardCount(), PeriodicProcess.PROVISIONING_CLEANUP.shardCount()))
                .containsExactly(8, 8, 4, 0);
    }

    @Test
    @DisplayName("ADR-028 an observation hook that throws never stops the process")
    void failingHookIsIgnored() {
        SchedulerEvents throwing = new SchedulerEvents() {
            @Override
            public void cycleCompleted(PeriodicProcess process, String correlationId, CycleResult result,
                    Duration duration) {
                throw new IllegalStateException("hook failure");
            }
        };
        new PeriodicTrigger(PeriodicProcess.EXPIRATION, EXPIRATION, this::invoke, () -> false, scheduler, throwing,
                new FixedRandom(0)).start();

        scheduler.advanceTimeBy(Duration.ofSeconds(10));

        assertThat(starts).containsExactly(0L, 5_000L, 10_000L);
    }

    @Test
    @DisplayName("ADR-037 ordered stop: no new cycle starts and stop completes when the cycle in flight ends")
    void orderedStop() {
        fallback = () -> Mono.delay(Duration.ofSeconds(3), scheduler).thenReturn(result(ItemOutcome.EXPIRED, 1));
        PeriodicTrigger trigger = trigger(PeriodicProcess.EXPIRATION, EXPIRATION, new FixedRandom(0));
        trigger.start();
        scheduler.advanceTimeBy(Duration.ofSeconds(1));

        AtomicBoolean stopped = new AtomicBoolean();
        trigger.stop().subscribe(null, null, () -> stopped.set(true));
        scheduler.advanceTimeBy(Duration.ofSeconds(1));
        assertThat(stopped).isFalse();
        scheduler.advanceTimeBy(Duration.ofSeconds(1));
        assertThat(stopped).isTrue();
        assertThat(events.completed).hasSize(1);

        scheduler.advanceTimeBy(Duration.ofSeconds(60));
        assertThat(starts).containsExactly(0L);
        assertThat(trigger.running()).isFalse();
    }

    @Test
    @DisplayName("ADR-037 stop while idle completes at once and cancels the pending trigger; a second start has no effect")
    void stopWhileIdle() {
        PeriodicTrigger trigger = trigger(PeriodicProcess.EXPIRATION, EXPIRATION, new FixedRandom(1_000));
        AtomicBoolean neverStarted = new AtomicBoolean();
        trigger.stop().subscribe(null, null, () -> neverStarted.set(true));
        assertThat(neverStarted).isTrue();

        PeriodicTrigger second = trigger(PeriodicProcess.EXPIRATION, EXPIRATION, new FixedRandom(1_000));
        second.start();
        second.start();
        scheduler.advanceTimeBy(Duration.ofSeconds(7));
        assertThat(starts).containsExactly(1_000L, 6_000L);

        AtomicBoolean stopped = new AtomicBoolean();
        second.stop().subscribe(null, null, () -> stopped.set(true));
        assertThat(stopped).isTrue();
        scheduler.advanceTimeBy(Duration.ofSeconds(30));
        assertThat(starts).containsExactly(1_000L, 6_000L);
    }

    @Test
    @DisplayName("ADR-037 a cycle that ends after stop does not schedule another one")
    void cycleEndingAfterStop() {
        script.add(() -> Mono.delay(Duration.ofSeconds(2), scheduler).then(Mono.error(new IllegalStateException("late"))));
        PeriodicTrigger trigger = trigger(PeriodicProcess.EXPIRATION, EXPIRATION, new FixedRandom(0));
        trigger.start();
        scheduler.advanceTimeBy(Duration.ofSeconds(1));
        AtomicBoolean stopped = new AtomicBoolean();
        trigger.stop().subscribe(null, null, () -> stopped.set(true));

        scheduler.advanceTimeBy(Duration.ofSeconds(30));

        assertThat(stopped).isTrue();
        assertThat(events.failed).hasSize(1);
        assertThat(starts).containsExactly(0L);
    }

    @Test
    @DisplayName("ADR-037 dispose cancels the cycle in flight and stops the process")
    void disposeCancelsTheCycle() {
        AtomicBoolean cancelled = new AtomicBoolean();
        fallback = () -> Mono.<CycleResult>never().doOnCancel(() -> cancelled.set(true));
        PeriodicTrigger trigger = trigger(PeriodicProcess.EXPIRATION, EXPIRATION, new FixedRandom(0));
        trigger.start();
        scheduler.advanceTime();

        trigger.dispose();
        scheduler.advanceTimeBy(Duration.ofSeconds(30));

        assertThat(cancelled).isTrue();
        assertThat(starts).containsExactly(0L);
        assertThat(trigger.running()).isFalse();
    }

    @Test
    @DisplayName("ADR-037 stop racing with a trigger: no cycle starts, whether the stop lands before or after the pause check")
    void stopRacingWithATrigger() {
        AtomicReference<PeriodicTrigger> self = new AtomicReference<>();
        AtomicBoolean pausedAnswer = new AtomicBoolean(false);
        PeriodicTrigger trigger = trigger(PeriodicProcess.REVERSAL, REVERSAL, new FixedRandom(0), () -> {
            self.get().stop().subscribe();
            return pausedAnswer.get();
        });
        self.set(trigger);
        trigger.start();
        scheduler.advanceTimeBy(Duration.ofSeconds(30));
        assertThat(starts).isEmpty();
        assertThat(trigger.running()).isFalse();

        pausedAnswer.set(true);
        PeriodicTrigger paused = trigger(PeriodicProcess.REVERSAL, REVERSAL, new FixedRandom(0), () -> {
            self.get().stop().subscribe();
            return pausedAnswer.get();
        });
        self.set(paused);
        paused.start();
        scheduler.advanceTimeBy(Duration.ofSeconds(30));
        assertThat(starts).isEmpty();
        assertThat(events.paused).containsExactly(PeriodicProcess.REVERSAL);
    }

    @Test
    @DisplayName("ADR-037 a timer set while a stop lands is inert: the stale trigger finds the process stopped")
    void staleTimerAfterStop() {
        AtomicReference<Runnable> beforeNextTimer = new AtomicReference<>();
        Scheduler racing = new Scheduler() {
            @Override
            public Disposable schedule(Runnable task) {
                return scheduler.schedule(task);
            }

            @Override
            public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
                Runnable hook = beforeNextTimer.getAndSet(null);
                if (hook != null) {
                    hook.run();
                }
                return scheduler.schedule(task, delay, unit);
            }

            @Override
            public long now(TimeUnit unit) {
                return scheduler.now(unit);
            }

            @Override
            public Worker createWorker() {
                return scheduler.createWorker();
            }
        };
        PeriodicTrigger trigger = new PeriodicTrigger(PeriodicProcess.EXPIRATION, EXPIRATION, this::invoke,
                () -> false, racing, events, new FixedRandom(0));
        trigger.start();
        scheduler.advanceTime();
        assertThat(starts).containsExactly(0L);

        AtomicBoolean stopped = new AtomicBoolean();
        beforeNextTimer.set(() -> trigger.stop().subscribe(null, null, () -> stopped.set(true)));
        scheduler.advanceTimeBy(Duration.ofSeconds(5));
        scheduler.advanceTimeBy(Duration.ofSeconds(30));

        assertThat(stopped).isTrue();
        assertThat(starts).containsExactly(0L, 5_000L);
        assertThat(trigger.running()).isFalse();
    }

    @Test
    @DisplayName("ADR-037 the default observation hook ignores every notification")
    void defaultHook() {
        SchedulerEvents.NONE.cycleCompleted(PeriodicProcess.EXPIRATION, "c", result(ItemOutcome.EXPIRED, 1), Duration.ZERO);
        SchedulerEvents.NONE.cycleFailed(PeriodicProcess.REVERSAL, "c", new IllegalStateException("x"), null, 1,
                Duration.ofSeconds(10));
        SchedulerEvents.NONE.triggersSkipped(PeriodicProcess.REPUBLISH, 2);
        SchedulerEvents.NONE.triggerPaused(PeriodicProcess.REVERSAL);
        assertThat(SchedulerEvents.NONE).isNotNull();
    }

    @Test
    @DisplayName("ADR-028 a scheduler that rejects timers stops the process instead of failing")
    void rejectedTimer() {
        Scheduler rejecting = new Scheduler() {
            @Override
            public Disposable schedule(Runnable task) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public Worker createWorker() {
                throw new UnsupportedOperationException("not used");
            }
        };
        PeriodicTrigger trigger = new PeriodicTrigger(PeriodicProcess.EXPIRATION, EXPIRATION, this::invoke,
                () -> false, rejecting, events, new FixedRandom(0));

        trigger.start();

        assertThat(trigger.running()).isFalse();
        AtomicBoolean stopped = new AtomicBoolean();
        trigger.stop().subscribe(null, null, () -> stopped.set(true));
        assertThat(stopped).isTrue();
    }

    @Test
    @DisplayName("ADR-028 rule 3 IV-004 approved schedules: 5/10/10/60 s; failure waits 1-5 s, 10-50 s, 10-50 s, 60-300 s")
    void deployedSettings() {
        WorkerSchedulerSettings deployed = WorkerSchedulerSettings.DEPLOYED;
        assertThat(List.of(deployed.expiration().period(), deployed.republish().period(), deployed.reversal().period(),
                deployed.provisioningCleanup().period())).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofSeconds(10), Duration.ofSeconds(60));
        assertThat(waits(deployed.expiration())).containsExactly(1L, 2L, 4L, 5L, 5L);
        assertThat(waits(deployed.republish())).containsExactly(10L, 20L, 40L, 50L, 50L);
        assertThat(waits(deployed.reversal())).containsExactly(10L, 20L, 40L, 50L, 50L);
        assertThat(waits(deployed.provisioningCleanup())).containsExactly(60L, 120L, 240L, 300L, 300L);
        assertThat(deployed.provisioningCleanup().failureWait(1_000)).isEqualTo(Duration.ofSeconds(300));
        for (PeriodicProcess process : PeriodicProcess.values()) {
            assertThat(deployed.of(process)).isNotNull();
        }
    }

    @Test
    @DisplayName("ADR-028 invalid schedules are rejected")
    void invalidSettings() {
        Duration second = Duration.ofSeconds(1);
        assertThatThrownBy(() -> new PeriodicProcessSettings(Duration.ZERO, second, second))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeriodicProcessSettings(second, Duration.ofNanos(1), second))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeriodicProcessSettings(second, second, Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeriodicProcessSettings(second, second, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> EXPIRATION.failureWait(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerSchedulerSettings(EXPIRATION, EXPIRATION, EXPIRATION, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WorkerSchedulerSettings.DEPLOYED.of(null)).isInstanceOf(NullPointerException.class);
    }

    private static List<Long> waits(PeriodicProcessSettings settings) {
        List<Long> waits = new ArrayList<>();
        for (int failures = 1; failures <= 5; failures++) {
            waits.add(settings.failureWait(failures).toSeconds());
        }
        return waits;
    }

    private PeriodicTrigger trigger(PeriodicProcess process, PeriodicProcessSettings settings, RandomGenerator random) {
        return trigger(process, settings, random, () -> false);
    }

    private PeriodicTrigger trigger(PeriodicProcess process, PeriodicProcessSettings settings, RandomGenerator random,
            BooleanSupplier paused) {
        return new PeriodicTrigger(process, settings, this::invoke, paused, scheduler, events, random);
    }

    private Mono<CycleResult> invoke(CycleRequest request) {
        starts.add(now());
        requests.add(request);
        Supplier<Mono<CycleResult>> next = script.isEmpty() ? fallback : script.poll();
        return Mono.defer(next)
                .doOnSubscribe(subscription -> maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max))
                .doFinally(signal -> inFlight.decrementAndGet());
    }

    private long now() {
        return scheduler.now(TimeUnit.MILLISECONDS);
    }

    static CycleResult result(ItemOutcome outcome, long count) {
        return new CycleResult(false, Map.of(outcome, count));
    }
}
