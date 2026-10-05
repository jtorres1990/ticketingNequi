package com.nequi.ticketing.infrastructure.adapter.in.sqs;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ConsumptionGate} switched by its owner: {@link #open()}, {@link #pause()} or {@link #probe(int)}.
 * INC-007 drives it from the state-change events of the Payment Mock circuit breaker (open = pause,
 * half-open = probe with the permitted probe calls, closed = open). Lock-free; listeners are invoked on the
 * thread that switches the gate and must not block (NFR-003).
 */
public final class SwitchableConsumptionGate implements ConsumptionGate {

    private static final Mode OPEN = new Mode(ModeKind.OPEN, null);
    private static final Mode PAUSED = new Mode(ModeKind.PAUSED, null);

    private final AtomicReference<Mode> mode = new AtomicReference<>(OPEN);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public void open() {
        switchTo(OPEN);
    }

    public void pause() {
        switchTo(PAUSED);
    }

    /** Half-open: the loop may receive at most {@code messages} messages until the gate is switched again. */
    public void probe(int messages) {
        if (messages < 1) {
            throw new IllegalArgumentException("probe messages must be positive");
        }
        switchTo(new Mode(ModeKind.PROBING, new AtomicInteger(messages)));
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
            case PROBING -> take(current.remaining(), wanted);
        };
    }

    @Override
    public void release(int unused) {
        Mode current = mode.get();
        if (unused > 0 && current.kind() == ModeKind.PROBING) {
            current.remaining().addAndGet(unused);
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

    private void switchTo(Mode next) {
        mode.set(next);
        listeners.forEach(Runnable::run);
    }

    private enum ModeKind {
        OPEN,
        PAUSED,
        PROBING
    }

    private record Mode(ModeKind kind, AtomicInteger remaining) {
    }
}
