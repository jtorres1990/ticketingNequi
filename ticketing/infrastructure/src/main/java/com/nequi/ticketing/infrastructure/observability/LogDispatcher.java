package com.nequi.ticketing.infrastructure.observability;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Moves the structured log writes of the observability hooks off the reactive threads (CMP-018, ADR-037,
 * NFR-003). The adapters report to their hooks on event-loop and {@code parallel} threads, where a synchronous
 * console appender is blocking I/O; the hooks only enqueue a log call here, without locks (a lock-free queue and
 * {@link LockSupport#unpark}), and one dedicated platform thread (never a virtual thread, ADR-034) writes them.
 *
 * <p>The queue is bounded: when {@code capacity} calls are pending, a new call is dropped and counted
 * ({@link #dropped()}), so that a log storm never grows the memory nor blocks a request. Metrics are recorded
 * by the hooks directly and are never dropped.
 */
public final class LogDispatcher implements AutoCloseable {

    private static final long IDLE_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    private final int capacity;
    private final Thread writer;
    private volatile boolean running = true;

    private LogDispatcher(int capacity, String threadName) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.writer = Thread.ofPlatform().name(Objects.requireNonNull(threadName, "threadName")).daemon(true)
                .unstarted(this::drainLoop);
    }

    /** Starts a dispatcher with its writer thread. */
    public static LogDispatcher start(int capacity) {
        LogDispatcher dispatcher = new LogDispatcher(capacity, "ticketing-log-writer");
        dispatcher.writer.start();
        return dispatcher;
    }

    /** Enqueues a log call without blocking; returns {@code false} when it was dropped (queue full or closed). */
    public boolean submit(Runnable logCall) {
        Objects.requireNonNull(logCall, "logCall");
        if (!running) {
            dropped.incrementAndGet();
            return false;
        }
        if (pending.incrementAndGet() > capacity) {
            pending.decrementAndGet();
            dropped.incrementAndGet();
            return false;
        }
        queue.offer(logCall);
        LockSupport.unpark(writer);
        return true;
    }

    /** Log calls dropped because the queue was full or the dispatcher was closed. */
    public long dropped() {
        return dropped.get();
    }

    /** Log calls waiting to be written. */
    public int pending() {
        return Math.max(0, pending.get());
    }

    /** Stops accepting calls, writes the pending ones and waits for the writer at most {@code timeout}. */
    public void close(Duration timeout) {
        running = false;
        LockSupport.unpark(writer);
        try {
            writer.join(Objects.requireNonNull(timeout, "timeout"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(5));
    }

    private void drainLoop() {
        while (running || !queue.isEmpty()) {
            Runnable next = queue.poll();
            if (next == null) {
                LockSupport.parkNanos(this, IDLE_PARK_NANOS);
                continue;
            }
            pending.decrementAndGet();
            try {
                next.run();
            } catch (RuntimeException | LinkageError ignored) {
                // A failing log call never stops the writer.
            }
        }
    }
}
