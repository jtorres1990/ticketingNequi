package com.nequi.ticketing.infrastructure.observability;

import com.nequi.ticketing.application.port.in.CycleResult;
import com.nequi.ticketing.infrastructure.adapter.in.scheduler.PeriodicProcess;
import com.nequi.ticketing.infrastructure.adapter.in.scheduler.SchedulerEvents;
import java.time.Duration;

/**
 * Periodic scheduler notifications (CMP-014, ADR-028) as cycle, item and skipped-trigger metrics and records:
 * expired, quarantined, republished, reversal and provisioning-cleanup outcomes by process.
 */
final class ObservedSchedulerEvents implements SchedulerEvents {

    private final Telemetry telemetry;

    ObservedSchedulerEvents(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    public void cycleCompleted(PeriodicProcess process, String correlationId, CycleResult result, Duration duration) {
        String name = process.name();
        telemetry.counter(MetricNames.SCHEDULER_CYCLES, "process", name,
                "outcome", result.skipped() ? "skipped" : "completed").increment();
        telemetry.timer(MetricNames.SCHEDULER_CYCLE_DURATION, "process", name).record(duration);
        result.outcomes().forEach((outcome, count) -> {
            if (count > 0) {
                telemetry.counter(MetricNames.SCHEDULER_ITEMS, "process", name, "outcome", outcome.name())
                        .increment(count);
            }
        });
        if (result.total() > 0) {
            telemetry.log().info("scheduler.cycle.completed", "Periodic cycle completed")
                    .with("process", name).with("correlationId", correlationId)
                    .with("outcomes", result.outcomes().toString()).with("durationMs", duration.toMillis())
                    .write();
        }
    }

    @Override
    public void cycleFailed(PeriodicProcess process, String correlationId, Throwable cause, CycleResult result,
            int consecutiveFailures, Duration nextAttemptIn) {
        telemetry.counter(MetricNames.SCHEDULER_CYCLES, "process", process.name(), "outcome", "failed").increment();
        telemetry.log().warn("scheduler.cycle.failed", "Periodic cycle failed")
                .with("process", process.name()).with("correlationId", correlationId)
                .with("outcomes", result == null ? null : result.outcomes().toString())
                .with("consecutiveFailures", consecutiveFailures).with("nextAttemptInMs", nextAttemptIn.toMillis())
                .cause(cause).write();
    }

    @Override
    public void triggersSkipped(PeriodicProcess process, long skipped) {
        telemetry.counter(MetricNames.SCHEDULER_TRIGGERS_SKIPPED, "process", process.name(), "reason", "overlap")
                .increment(skipped);
        telemetry.log().info("scheduler.triggers.skipped", "Triggers skipped while the previous cycle was running")
                .with("process", process.name()).with("skipped", skipped).write();
    }

    @Override
    public void triggerPaused(PeriodicProcess process) {
        telemetry.counter(MetricNames.SCHEDULER_TRIGGERS_SKIPPED, "process", process.name(), "reason", "paused")
                .increment();
    }
}
