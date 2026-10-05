package com.nequi.ticketing.infrastructure.adapter.out.system;

import com.nequi.ticketing.application.port.out.Clock;
import java.time.Instant;
import java.util.Objects;

/** Clock port (CMP-021, ADR-034): the UTC instant of the server; every temporal rule reads it through the port. */
public final class SystemClock implements Clock {

    private final java.time.Clock clock;

    public SystemClock() {
        this(java.time.Clock.systemUTC());
    }

    public SystemClock(java.time.Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Instant now() {
        return clock.instant();
    }
}
