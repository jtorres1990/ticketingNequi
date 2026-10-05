package com.nequi.ticketing.infrastructure.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.application.port.in.CleanUpProvisioningUseCase;
import com.nequi.ticketing.application.port.in.CycleRequest;
import com.nequi.ticketing.application.port.in.CycleResult;
import com.nequi.ticketing.application.port.in.ExpireReservationsUseCase;
import com.nequi.ticketing.application.port.in.ItemOutcome;
import com.nequi.ticketing.application.port.in.RepublishPendingOrdersUseCase;
import com.nequi.ticketing.application.port.in.ReversePaymentsUseCase;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.CircuitGateBinding;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.ConsumptionGate;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.SwitchableConsumptionGate;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitBreakerSettings;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitState;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.ManagedCircuitBreaker;
import com.nequi.ticketing.infrastructure.adapter.sqs.VirtualClock;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

/**
 * CMP-014 with its four triggers and virtual time (ADR-028): periods, isolation between processes, pause of the
 * reversals driven by the Payment Mock circuit (IV-016, ADR-035) and ordered shutdown.
 */
class WorkerSchedulerTest {

    private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
    private final RecordingSchedulerEvents events = new RecordingSchedulerEvents();
    private final Map<PeriodicProcess, List<Long>> starts = new EnumMap<>(PeriodicProcess.class);
    private final Map<PeriodicProcess, AtomicReference<Function<CycleRequest, Mono<CycleResult>>>> behaviours =
            new EnumMap<>(PeriodicProcess.class);
    private WorkerScheduler workerScheduler;

    WorkerSchedulerTest() {
        for (PeriodicProcess process : PeriodicProcess.values()) {
            starts.put(process, new CopyOnWriteArrayList<>());
            behaviours.put(process, new AtomicReference<>(request -> Mono.just(ok())));
        }
    }

    @AfterEach
    void tearDown() {
        if (workerScheduler != null) {
            workerScheduler.dispose();
        }
        scheduler.dispose();
    }

    @Test
    @DisplayName("ADR-028 four independent triggers: expiration every 5 s, sweep and reversals every 10 s, cleanup every 60 s")
    void periods() {
        start(ConsumptionGate.alwaysOpen());

        scheduler.advanceTimeBy(Duration.ofMinutes(5));

        assertIntervals(PeriodicProcess.EXPIRATION, 5_000L, 59);
        assertIntervals(PeriodicProcess.REPUBLISH, 10_000L, 29);
        assertIntervals(PeriodicProcess.REVERSAL, 10_000L, 29);
        assertIntervals(PeriodicProcess.PROVISIONING_CLEANUP, 60_000L, 4);
        assertThat(events.failed).isEmpty();
    }

    @Test
    @DisplayName("ADR-028 isolation: a sweep that never ends, failing reversals and a slow cleanup do not delay the expiration")
    void isolation() {
        behaviours.get(PeriodicProcess.REPUBLISH).set(request -> Mono.never());
        behaviours.get(PeriodicProcess.REVERSAL).set(request -> Mono.error(new IllegalStateException("down")));
        behaviours.get(PeriodicProcess.PROVISIONING_CLEANUP)
                .set(request -> Mono.delay(Duration.ofSeconds(200), scheduler).thenReturn(ok()));
        start(ConsumptionGate.alwaysOpen());

        scheduler.advanceTimeBy(Duration.ofMinutes(5));

        assertIntervals(PeriodicProcess.EXPIRATION, 5_000L, 59);
        assertThat(starts.get(PeriodicProcess.REPUBLISH)).hasSize(1);
        assertThat(intervals(PeriodicProcess.REVERSAL)).startsWith(10_000L, 20_000L, 40_000L, 50_000L, 50_000L);
        assertThat(intervals(PeriodicProcess.PROVISIONING_CLEANUP)).containsOnly(240_000L);
        assertThat(events.failed(PeriodicProcess.EXPIRATION)).isEmpty();
        assertThat(events.completed(PeriodicProcess.EXPIRATION)).hasSizeGreaterThanOrEqualTo(60);
    }

    @Test
    @DisplayName("IV-016 ADR-035 reversals: open circuit pauses the cycle (no attempt consumed) while expiration continues; half-open runs it as probe calls; closed resumes")
    void reversalsFollowThePaymentMockCircuit() {
        ManagedCircuitBreaker circuit = ManagedCircuitBreaker.create("payment-mock", CircuitBreakerSettings.paymentMock(),
                new VirtualClock(scheduler), IOException.class::isInstance);
        SwitchableConsumptionGate ordersGate = new SwitchableConsumptionGate();
        SwitchableConsumptionGate reversalGate = new SwitchableConsumptionGate();
        CircuitGateBinding.bind(circuit, scheduler, ordersGate, reversalGate);
        AtomicReference<Mono<String>> cancellation = new AtomicReference<>(Mono.just("cancelled"));
        behaviours.get(PeriodicProcess.REVERSAL).set(request -> cancellation.get()
                .transform(circuit.operator())
                .thenReturn(result(ItemOutcome.REVERSAL_CONFIRMED))
                .onErrorReturn(result(ItemOutcome.REVERSAL_RESCHEDULED)));
        start(reversalGate);
        scheduler.advanceTimeBy(Duration.ofSeconds(10));
        int reversalsBeforeOpening = starts.get(PeriodicProcess.REVERSAL).size();
        int expirationsBeforeOpening = starts.get(PeriodicProcess.EXPIRATION).size();

        failCalls(circuit, 10);
        assertThat(circuit.state()).isEqualTo(CircuitState.OPEN);
        scheduler.advanceTimeBy(Duration.ofSeconds(15));

        assertThat(starts.get(PeriodicProcess.REVERSAL)).hasSize(reversalsBeforeOpening);
        assertThat(events.paused).isNotEmpty().containsOnly(PeriodicProcess.REVERSAL);
        assertThat(starts.get(PeriodicProcess.EXPIRATION)).hasSize(expirationsBeforeOpening + 3);

        scheduler.advanceTimeBy(Duration.ofMillis(1));
        assertThat(reversalGate.isPaused()).isFalse();
        scheduler.advanceTimeBy(Duration.ofSeconds(10));
        assertThat(starts.get(PeriodicProcess.REVERSAL)).hasSize(reversalsBeforeOpening + 1);
        assertThat(circuit.state()).isEqualTo(CircuitState.HALF_OPEN);

        scheduler.advanceTimeBy(Duration.ofSeconds(20));
        assertThat(circuit.state()).isEqualTo(CircuitState.CLOSED);
        int afterClosing = starts.get(PeriodicProcess.REVERSAL).size();
        scheduler.advanceTimeBy(Duration.ofSeconds(30));
        assertThat(starts.get(PeriodicProcess.REVERSAL)).hasSize(afterClosing + 3);
        assertThat(events.failed).isEmpty();
    }

    @Test
    @DisplayName("ADR-037 ordered stop of the four triggers waits for the cycles in flight; dispose cancels them")
    void stopAndDispose() {
        behaviours.get(PeriodicProcess.PROVISIONING_CLEANUP)
                .set(request -> Mono.delay(Duration.ofSeconds(90), scheduler).thenReturn(ok()));
        start(ConsumptionGate.alwaysOpen());
        scheduler.advanceTimeBy(Duration.ofSeconds(60));
        assertThat(starts.get(PeriodicProcess.PROVISIONING_CLEANUP)).hasSize(1);

        AtomicBoolean stopped = new AtomicBoolean();
        workerScheduler.stop().subscribe(null, null, () -> stopped.set(true));
        int expirations = starts.get(PeriodicProcess.EXPIRATION).size();
        scheduler.advanceTimeBy(Duration.ofSeconds(60));
        assertThat(stopped).isFalse();
        assertThat(starts.get(PeriodicProcess.EXPIRATION)).hasSize(expirations);
        scheduler.advanceTimeBy(Duration.ofSeconds(60));
        assertThat(stopped).isTrue();

        workerScheduler.dispose();
        assertThat(workerScheduler.isDisposed()).isTrue();
    }

    @Test
    @DisplayName("NFR-003 the default scheduler runs the triggers on the parallel scheduler with random phases")
    void defaultScheduler() {
        WorkerScheduler parallel = WorkerScheduler.create(expiration(), republish(), reversal(), cleanup(),
                ConsumptionGate.alwaysOpen(), WorkerSchedulerSettings.DEPLOYED, SchedulerEvents.NONE);
        assertThat(parallel.isDisposed()).isFalse();
        parallel.start();
        parallel.dispose();
        assertThat(parallel.isDisposed()).isTrue();
        assertThatThrownBy(() -> WorkerScheduler.create(expiration(), republish(), reversal(), cleanup(), null,
                WorkerSchedulerSettings.DEPLOYED, SchedulerEvents.NONE)).isInstanceOf(NullPointerException.class);
    }

    private void start(ConsumptionGate reversalGate) {
        workerScheduler = WorkerScheduler.create(expiration(), republish(), reversal(), cleanup(), reversalGate,
                WorkerSchedulerSettings.DEPLOYED, events, scheduler, new SplittableRandom(11));
        workerScheduler.start();
    }

    private ExpireReservationsUseCase expiration() {
        return request -> invoke(PeriodicProcess.EXPIRATION, request);
    }

    private RepublishPendingOrdersUseCase republish() {
        return request -> invoke(PeriodicProcess.REPUBLISH, request);
    }

    private ReversePaymentsUseCase reversal() {
        return request -> invoke(PeriodicProcess.REVERSAL, request);
    }

    private CleanUpProvisioningUseCase cleanup() {
        return request -> invoke(PeriodicProcess.PROVISIONING_CLEANUP, request);
    }

    private Mono<CycleResult> invoke(PeriodicProcess process, CycleRequest request) {
        starts.get(process).add(scheduler.now(TimeUnit.MILLISECONDS));
        return behaviours.get(process).get().apply(request);
    }

    private void assertIntervals(PeriodicProcess process, long period, int count) {
        List<Long> processStarts = starts.get(process);
        assertThat(processStarts.getFirst()).isBetween(0L, period - 1);
        assertThat(intervals(process)).hasSizeGreaterThanOrEqualTo(count).containsOnly(period);
    }

    private List<Long> intervals(PeriodicProcess process) {
        List<Long> processStarts = starts.get(process);
        List<Long> intervals = new ArrayList<>();
        for (int index = 1; index < processStarts.size(); index++) {
            intervals.add(processStarts.get(index) - processStarts.get(index - 1));
        }
        return intervals;
    }

    private static void failCalls(ManagedCircuitBreaker circuit, int calls) {
        for (int call = 0; call < calls; call++) {
            Mono.<String>error(new IOException("payment mock down")).transform(circuit.operator())
                    .onErrorResume(error -> Mono.empty()).subscribe();
        }
    }

    private static CycleResult ok() {
        return result(ItemOutcome.NOT_APPLICABLE);
    }

    private static CycleResult result(ItemOutcome outcome) {
        return new CycleResult(false, Map.of(outcome, 1L));
    }
}
