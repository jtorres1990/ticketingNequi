package com.nequi.ticketing.application.testdouble;

import com.nequi.ticketing.application.port.out.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** Controlled Clock port for deterministic time in use case tests (ADR-038). */
public final class MutableClock implements Clock {

    private final AtomicReference<Instant> current;

    public MutableClock(Instant initial) {
        this.current = new AtomicReference<>(initial);
    }

    @Override
    public Instant now() {
        return current.get();
    }

    public void set(Instant instant) {
        current.set(instant);
    }

    public void advance(Duration duration) {
        current.updateAndGet(instant -> instant.plus(duration));
    }
}
