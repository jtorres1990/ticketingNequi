package com.nequi.ticketing.infrastructure.adapter.in.sqs;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ConsumptionGate} switched by its owner: {@link #open()}, {@link #pause()}, {@link #probe(int)} (at
 * most N messages until switched again) or {@link #limitInFlight(int)} (at most N messages in flight: the
 * permit of a message returns when it finishes). {@link CircuitGateBinding} drives it from the Payment Mock
 * circuit breaker (open = pause, half-open = in-flight limit of the probe calls, closed = open). Lock-free;
 * listeners are invoked on the thread that switches the gate and must not block (NFR-003).
 */
public final class SwitchableConsumptionGate implements ConsumptionGate {

    private static final Mode OPEN = new Mode(ModeKind.OPEN, null, 0);
    private static final Mode PAUSED = new Mode(ModeKind.PAUSED, null, 0);

    private final AtomicReference<Mode> mode = new AtomicReference<>(OPEN);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public void open() {
        switchTo(OPEN);
    }

    public void pause() {
        switchTo(PAUSED);
    }

    /** The loop may receive at most {@code messages} messages until the gate is switched again. */
    public void probe(int messages) {
        switchTo(new Mode(ModeKind.PROBING, new AtomicInteger(positive(messages)), messages));
    }

    /**
     * Half-open (ADR-035, ADR-039): at most {@code messages} received messages in flight; each permit returns
     * when its message finishes ({@link #completed(int)}), so the loop keeps receiving a few messages at a
     * time until the gate is switched again. The returned permits are capped at {@code messages}.
     */
    public void limitInFlight(int messages) {
        switchTo(new Mode(ModeKind.LIMITED, new AtomicInteger(positive(messages)), messages));
    }

    @Override
    public int acquire(int wanted) {
        if (wanted <= 0) {
            return 0;
        }
        Mode current = mode.get();
        return switch (current.kind()) {
            case OPEN -> wanted;
            case PAUSED -> 0;
            case PROBING, LIMITED -> take(current.remaining(), wanted);
        };
    }

    @Override
    public void release(int unused) {
        Mode current = mode.get();
        if (unused > 0 && (current.kind() == ModeKind.PROBING || current.kind() == ModeKind.LIMITED)) {
            give(current, unused);
        }
    }

    @Override
    public void completed(int messages) {
        Mode current = mode.get();
        if (messages > 0 && current.kind() == ModeKind.LIMITED) {
            give(current, messages);
        }
    }

    @Override
    public boolean isPaused() {
        return mode.get().kind() == ModeKind.PAUSED;
    }

    @Override
    public void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private static int positive(int messages) {
        if (messages < 1) {
            throw new IllegalArgumentException("probe messages must be positive");
        }
        return messages;
    }

    private static int take(AtomicInteger remaining, int wanted) {
        while (true) {
            int available = remaining.get();
            int granted = Math.min(available, wanted);
            if (granted <= 0) {
                return 0;
            }
            if (remaining.compareAndSet(available, available - granted)) {
                return granted;
            }
        }
    }

    private static void give(Mode mode, int permits) {
        mode.remaining().accumulateAndGet(permits, (available, returned) -> Math.min(mode.limit(), available + returned));
    }

    private void switchTo(Mode next) {
        mode.set(next);
        listeners.forEach(Runnable::run);
    }

    private enum ModeKind {
        OPEN,
        PAUSED,
        PROBING,
        LIMITED
    }

    private record Mode(ModeKind kind, AtomicInteger remaining, int limit) {
    }
}
