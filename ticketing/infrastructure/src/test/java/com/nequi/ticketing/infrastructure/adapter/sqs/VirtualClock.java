package com.nequi.ticketing.infrastructure.adapter.sqs;

import com.nequi.ticketing.application.port.out.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import reactor.test.scheduler.VirtualTimeScheduler;

/** Application clock that reads the virtual time of a {@link VirtualTimeScheduler}. */
public final class VirtualClock implements Clock {

    private final VirtualTimeScheduler scheduler;

    public VirtualClock(VirtualTimeScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public Instant now() {
        return Instant.ofEpochMilli(scheduler.now(TimeUnit.MILLISECONDS));
    }
}
