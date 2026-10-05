package com.nequi.ticketing.infrastructure.adapter.in.sqs;

import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitState;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.ManagedCircuitBreaker;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Pause and resume driven by the state of the Payment Mock circuit breaker (CMP-026; ADR-035, ADR-039): the
 * gates of the Orders loop (CMP-012) and of the reversal process (CMP-024, triggered by INC-009) are
 * <ul>
 *   <li>paused while the circuit is open and rejecting calls, so that no reception of
 *       {@code maxReceiveCount} and no reversal attempt is consumed;</li>
 *   <li>limited to the probe calls of the circuit in flight once the open time has passed and while the
 *       circuit is half-open, so that a few probe messages reach the Payment Mock;</li>
 *   <li>open again when the circuit closes.</li>
 * </ul>
 * The open circuit only moves to half-open on the next call, and no call happens while the gates are
 * paused, so the binding wakes up on its scheduler when the open time has passed and lets the probes
 * through (otherwise consumption would never resume). The in-flight limit, instead of a fixed number of
 * probe messages, keeps the loop receiving when a probe message ends without calling the Payment Mock
 * (duplicate, Order already terminal, cutoff margin), so the circuit always gets its probe calls.
 *
 * <p>Reconciliation is serialized without locks (work-in-progress counter): any state change, from any
 * thread, re-reads the circuit and applies the corresponding mode exactly once (NFR-003).
 */
public final class CircuitGateBinding implements Disposable {

    private enum Mode {
        RUNNING,
        PAUSED,
        PROBING
    }

    private final ManagedCircuitBreaker circuit;
    private final List<SwitchableConsumptionGate> gates;
    private final Scheduler scheduler;
    private final AtomicInteger work = new AtomicInteger();
    private final AtomicReference<Disposable> wakeUp = new AtomicReference<>(Disposables.disposed());
    private Mode applied;
    private volatile boolean disposed;

    private CircuitGateBinding(ManagedCircuitBreaker circuit, List<SwitchableConsumptionGate> gates,
            Scheduler scheduler) {
        this.circuit = circuit;
        this.gates = gates;
        this.scheduler = scheduler;
    }

    /** Binds the gates to the circuit on the parallel scheduler and applies the current state at once. */
    public static CircuitGateBinding bind(ManagedCircuitBreaker circuit, SwitchableConsumptionGate... gates) {
        return bind(circuit, Schedulers.parallel(), gates);
    }

    /** Variant with an explicit timer scheduler (virtual time in tests). */
    public static CircuitGateBinding bind(ManagedCircuitBreaker circuit, Scheduler scheduler,
            SwitchableConsumptionGate... gates) {
        Objects.requireNonNull(circuit, "circuit");
        Objects.requireNonNull(scheduler, "scheduler");
        List<SwitchableConsumptionGate> bound = List.of(Objects.requireNonNull(gates, "gates"));
        if (bound.isEmpty()) {
            throw new IllegalArgumentException("at least one gate is required");
        }
        CircuitGateBinding binding = new CircuitGateBinding(circuit, bound, scheduler);
        circuit.onStateChange(state -> binding.reconcile());
        binding.reconcile();
        return binding;
    }

    /** Stops driving the gates; they keep their last mode. */
    @Override
    public void dispose() {
        disposed = true;
        wakeUp.getAndSet(Disposables.disposed()).dispose();
    }

    @Override
    public boolean isDisposed() {
        return disposed;
    }

    private void reconcile() {
        if (work.getAndIncrement() != 0) {
            return;
        }
        do {
            if (!disposed) {
                Mode target = target();
                if (target != applied) {
                    applied = target;
                    apply(target);
                }
                if (target == Mode.PAUSED) {
                    scheduleWakeUp();
                }
            }
        } while (work.decrementAndGet() != 0);
    }

    private Mode target() {
        CircuitState state = circuit.state();
        if (state == CircuitState.CLOSED) {
            return Mode.RUNNING;
        }
        if (state == CircuitState.OPEN && circuit.rejectingCalls()) {
            return Mode.PAUSED;
        }
        return Mode.PROBING;
    }

    private void apply(Mode mode) {
        for (SwitchableConsumptionGate gate : gates) {
            switch (mode) {
                case RUNNING -> gate.open();
                case PAUSED -> gate.pause();
                case PROBING -> gate.limitInFlight(circuit.probeCalls());
            }
        }
    }

    /** Wakes up just after the open time, when the circuit admits probe calls (strictly after, SPK-019). */
    private void scheduleWakeUp() {
        Duration delay = circuit.remainingOpenTime().plusMillis(1);
        Disposable next = scheduler.schedule(this::reconcile, delay.toMillis(), TimeUnit.MILLISECONDS);
        wakeUp.getAndSet(next).dispose();
        if (disposed) {
            next.dispose();
        }
    }
}
