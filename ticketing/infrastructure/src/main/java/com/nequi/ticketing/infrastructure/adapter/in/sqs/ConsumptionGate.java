package com.nequi.ticketing.infrastructure.adapter.in.sqs;

/**
 * Gate that governs whether a consumer loop may receive (ADR-029, ADR-035, ADR-039): open (receive
 * normally), paused (do not receive, so no reception of {@code maxReceiveCount} is consumed) or probing
 * (receive only a limited number of probe messages). The Orders loop (CMP-012) is governed by a gate that
 * INC-007 connects to the state of the Payment Mock circuit breaker; implementations must not block.
 */
public interface ConsumptionGate {

    /** Number of messages the loop may receive now, at most {@code wanted}; {@code 0} while paused. */
    int acquire(int wanted);

    /** Returns permits obtained with {@link #acquire} that were not used by the reception. */
    void release(int unused);

    /** Whether the gate is paused; an in-flight long poll is abandoned when the gate pauses. */
    boolean isPaused();

    /** Registers a listener invoked after every change of the gate. */
    void addListener(Runnable listener);

    /** A gate that is always open (the provisioning loop does not depend on the Payment Mock). */
    static ConsumptionGate alwaysOpen() {
        return new ConsumptionGate() {
            @Override
            public int acquire(int wanted) {
                return Math.max(0, wanted);
            }

            @Override
            public void release(int unused) {
                // nothing to return: the gate never limits the reception
            }

            @Override
            public boolean isPaused() {
                return false;
            }

            @Override
            public void addListener(Runnable listener) {
                // the gate never changes
            }
        };
    }
}
