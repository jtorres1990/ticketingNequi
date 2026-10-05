package com.nequi.paymentmock.application;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holder of the current {@link Generation} and of the process-wide rule counter. The counter is never reset, so
 * rule identifiers are never reused, not even after a reset (PM-IV-006). All state is in memory and is lost when the
 * process stops (ADR-030).
 */
public final class MockState {

    private final AtomicReference<Generation> current = new AtomicReference<>(new Generation());
    private final AtomicLong ruleSequence = new AtomicLong();

    Generation current() {
        return current.get();
    }

    /** Atomic logical reset: one reference swap, no partially cleared state is ever visible. */
    void reset() {
        current.set(new Generation());
    }

    long nextRuleSequence() {
        return ruleSequence.incrementAndGet();
    }
}
