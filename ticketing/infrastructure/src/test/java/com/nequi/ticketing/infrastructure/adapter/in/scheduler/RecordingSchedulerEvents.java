package com.nequi.ticketing.infrastructure.adapter.in.scheduler;

import com.nequi.ticketing.application.port.in.CycleResult;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records every notification of the scheduler, in order. */
final class RecordingSchedulerEvents implements SchedulerEvents {

    record Completed(PeriodicProcess process, String correlationId, CycleResult result, Duration duration) {
    }

    record Failed(PeriodicProcess process, String correlationId, Throwable cause, CycleResult result,
            int consecutiveFailures, Duration nextAttemptIn) {
    }

    record Skipped(PeriodicProcess process, long skipped) {
    }

    final List<Completed> completed = new CopyOnWriteArrayList<>();
    final List<Failed> failed = new CopyOnWriteArrayList<>();
    final List<Skipped> skipped = new CopyOnWriteArrayList<>();
    final List<PeriodicProcess> paused = new CopyOnWriteArrayList<>();

    @Override
    public void cycleCompleted(PeriodicProcess process, String correlationId, CycleResult result, Duration duration) {
        completed.add(new Completed(process, correlationId, result, duration));
    }

    @Override
    public void cycleFailed(PeriodicProcess process, String correlationId, Throwable cause, CycleResult result,
            int consecutiveFailures, Duration nextAttemptIn) {
        failed.add(new Failed(process, correlationId, cause, result, consecutiveFailures, nextAttemptIn));
    }

    @Override
    public void triggersSkipped(PeriodicProcess process, long count) {
        skipped.add(new Skipped(process, count));
    }

    @Override
    public void triggerPaused(PeriodicProcess process) {
        paused.add(process);
    }

    List<Completed> completed(PeriodicProcess process) {
        return completed.stream().filter(event -> event.process() == process).toList();
    }

    List<Failed> failed(PeriodicProcess process) {
        return failed.stream().filter(event -> event.process() == process).toList();
    }
}
