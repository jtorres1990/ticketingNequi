package com.nequi.ticketing.infrastructure.adapter.in.scheduler;

import com.nequi.ticketing.application.port.in.CycleResult;
import java.time.Duration;

/**
 * Observation hook of the periodic scheduler (CMP-014). The scheduler never logs or records metrics on reactive
 * threads itself; it reports here, and the observability wiring of INC-010 (CMP-018, ADR-037) turns these
 * notifications into structured logs and metrics (cycle outcomes, failures, skipped triggers). Implementations
 * must not block (NFR-003); an exception thrown by a notification is ignored so that it never stops a process.
 * Every method has a no-op default.
 */
public interface SchedulerEvents {

    SchedulerEvents NONE = new SchedulerEvents() {
    };

    /** A cycle ended; {@code result.skipped()} is a cycle the use case skipped (sweep with publication down). */
    default void cycleCompleted(PeriodicProcess process, String correlationId, CycleResult result, Duration duration) {
    }

    /**
     * A cycle failed (ADR-028 rule 3): it signalled {@code cause}, or every candidate and index query of
     * {@code result} failed. Exactly one of {@code cause} and {@code result} is {@code null}. The process runs
     * again after {@code nextAttemptIn}.
     */
    default void cycleFailed(PeriodicProcess process, String correlationId, Throwable cause, CycleResult result,
            int consecutiveFailures, Duration nextAttemptIn) {
    }

    /** {@code skipped} triggers of the process fell while its previous cycle was still running (no overlap). */
    default void triggersSkipped(PeriodicProcess process, long skipped) {
    }

    /** A trigger was skipped because the process is paused (reversals with the Payment Mock circuit open). */
    default void triggerPaused(PeriodicProcess process) {
    }
}
