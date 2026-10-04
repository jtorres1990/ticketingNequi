package com.nequi.ticketing.application.testdouble;

import com.nequi.ticketing.application.port.out.IdGenerator;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic UUID-shaped identifiers for tests; production uses random UUIDs (ADR-032). */
public final class SequentialIdGenerator implements IdGenerator {

    private final AtomicLong orders = new AtomicLong();
    private final AtomicLong events = new AtomicLong();

    @Override
    public String newOrderId() {
        return "00000000-0000-4000-8000-%012d".formatted(orders.incrementAndGet());
    }

    @Override
    public String newEventId() {
        return "10000000-0000-4000-8000-%012d".formatted(events.incrementAndGet());
    }

    public static String orderId(long sequence) {
        return "00000000-0000-4000-8000-%012d".formatted(sequence);
    }

    public static String eventId(long sequence) {
        return "10000000-0000-4000-8000-%012d".formatted(sequence);
    }
}
