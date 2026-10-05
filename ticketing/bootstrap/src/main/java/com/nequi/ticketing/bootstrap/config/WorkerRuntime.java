package com.nequi.ticketing.bootstrap.config;

import com.nequi.ticketing.infrastructure.adapter.in.scheduler.WorkerScheduler;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.CircuitGateBinding;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.SqsConsumers;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import reactor.core.publisher.Mono;

/**
 * Lifecycle of the {@code worker} role (ADR-037 "apagado ordenado"): on start, the two consumer loops and the four
 * periodic triggers start; on the stop signal, both loops stop receiving and the triggers start no new cycle, and
 * the stop completes when the messages and cycles in flight have completed. The stop is asynchronous (no thread
 * waits) and bounded by the shutdown phase timeout ({@code spring.lifecycle.timeout-per-shutdown-phase}, 35 s,
 * IV-004), longer than the publication budget (2 s) and the processing cap (30 s). The consumption client and the
 * circuit binding are released after the stop; the DynamoDB and publication clients afterwards (CMP-021).
 */
public final class WorkerRuntime implements SmartLifecycle, DisposableBean {

    private final SqsConsumers consumers;
    private final WorkerScheduler scheduler;
    private final CircuitGateBinding binding;
    private final AtomicBoolean running = new AtomicBoolean();

    WorkerRuntime(SqsConsumers consumers, WorkerScheduler scheduler, CircuitGateBinding binding) {
        this.consumers = Objects.requireNonNull(consumers, "consumers");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            consumers.start();
            scheduler.start();
        }
    }

    @Override
    public void stop(Runnable callback) {
        if (!running.compareAndSet(true, false)) {
            callback.run();
            return;
        }
        Mono.when(consumers.stop(), scheduler.stop())
                .subscribe(null, error -> callback.run(), callback);
    }

    @Override
    public void stop() {
        stop(() -> {
        });
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** Stops before the web server (lower phases stop later), so health keeps answering while work completes. */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE;
    }

    /** Cancels whatever did not complete within the shutdown timeout and releases the consumption client. */
    @Override
    public void destroy() {
        scheduler.dispose();
        binding.dispose();
        consumers.close();
    }
}
