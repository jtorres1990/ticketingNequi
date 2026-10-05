package com.nequi.ticketing.infrastructure.adapter.in.sqs;

import com.nequi.ticketing.infrastructure.adapter.sqs.SqsEvents;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/**
 * Reactive consumer loop of one queue (ADR-039), shared by CMP-012 and CMP-025:
 * <ul>
 *   <li>long polling that asks for at most {@code min(maxMessages, free slots, gate permits)} messages, so
 *       that no received message waits for a slot while its visibility runs;</li>
 *   <li>bounded concurrency: each message is handled independently and frees its slot when done;</li>
 *   <li>pause and resume through the {@link ConsumptionGate}; an in-flight long poll is abandoned when the
 *       gate pauses, so that no reception is consumed while paused (ADR-035);</li>
 *   <li>continuity after a receive error with a bounded growing wait (IV-004);</li>
 *   <li>ordered shutdown: stop receiving, abandon the in-flight long poll and complete the messages in
 *       flight (ADR-037).</li>
 * </ul>
 * The loop never blocks: every wait is a signal (slot freed, gate changed, stop) or a timer on the
 * scheduler. Wake-ups are race-free because the waiter takes the current signal before checking the
 * condition, and every change completes the signal it replaces.
 */
final class ConsumerLoop {

    private final String queue;
    private final SqsAsyncClient client;
    private final ConsumerLoopSettings settings;
    private final ConsumptionGate gate;
    private final Scheduler scheduler;
    private final SqsEvents events;
    private final Function<Message, Mono<Void>> handler;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicReference<Sinks.Empty<Void>> wakeUp = new AtomicReference<>(Sinks.empty());
    private final AtomicReference<Sinks.Empty<Void>> receiveInterrupt = new AtomicReference<>(Sinks.empty());
    private final Sinks.Empty<Void> stopped = Sinks.empty();
    private final Sinks.Empty<Void> terminated = Sinks.empty();
    private volatile boolean running;
    private int consecutiveErrors;

    ConsumerLoop(String queue, SqsAsyncClient client, ConsumerLoopSettings settings, ConsumptionGate gate,
            Scheduler scheduler, SqsEvents events, Function<Message, Mono<Void>> handler) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.client = Objects.requireNonNull(client, "client");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.events = Objects.requireNonNull(events, "events");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        running = true;
        gate.addListener(this::onGateChange);
        Mono.defer(this::cycle)
                .repeat(() -> running)
                .doFinally(signal -> terminated.tryEmitEmpty())
                .subscribeOn(scheduler)
                .subscribe();
    }

    /** Stops receiving and completes when the loop has ended and no message is in flight. */
    Mono<Void> stop() {
        running = false;
        stopped.tryEmitEmpty();
        receiveInterrupt.get().tryEmitEmpty();
        signal();
        if (!started.get()) {
            return Mono.empty();
        }
        return terminated.asMono().then(Mono.defer(this::awaitIdle));
    }

    boolean running() {
        return running;
    }

    int inFlight() {
        return inFlight.get();
    }

    private Mono<Void> cycle() {
        if (!running) {
            return Mono.empty();
        }
        Mono<Void> next = nextWakeUp();
        int free = settings.concurrency() - inFlight.get();
        int wanted = Math.min(settings.maxMessages(), free);
        int permits = wanted > 0 ? gate.acquire(wanted) : 0;
        if (permits == 0) {
            return next;
        }
        return receive(permits);
    }

    private Mono<Void> receive(int permits) {
        Sinks.Empty<Void> interrupt = Sinks.empty();
        receiveInterrupt.set(interrupt);
        if (!running || gate.isPaused()) {
            gate.release(permits);
            return Mono.empty();
        }
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(settings.queueUrl())
                .maxNumberOfMessages(permits)
                .waitTimeSeconds((int) settings.waitTime().toSeconds())
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                .messageAttributeNames("All")
                .build();
        return Mono.fromFuture(() -> client.receiveMessage(request))
                .takeUntilOther(interrupt.asMono())
                .map(ReceiveMessageResponse::messages)
                .defaultIfEmpty(List.of())
                .doOnNext(messages -> {
                    consecutiveErrors = 0;
                    gate.release(permits - messages.size());
                    messages.forEach(this::dispatch);
                })
                .then()
                .onErrorResume(error -> {
                    gate.release(permits);
                    Duration wait = errorWait(++consecutiveErrors);
                    events.receiveFailed(queue, error, wait);
                    return Mono.delay(wait, scheduler).then().takeUntilOther(stopped.asMono());
                });
    }

    private void dispatch(Message message) {
        inFlight.incrementAndGet();
        Mono.defer(() -> handler.apply(message))
                .onErrorResume(error -> Mono.empty())
                .doFinally(signal -> {
                    inFlight.decrementAndGet();
                    gate.completed(1);
                    signal();
                })
                .subscribeOn(scheduler)
                .subscribe();
    }

    private Duration errorWait(int errors) {
        Duration wait = settings.errorInitialWait();
        for (int doubling = 1; doubling < errors && wait.compareTo(settings.errorMaxWait()) < 0; doubling++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(settings.errorMaxWait()) > 0 ? settings.errorMaxWait() : wait;
    }

    private Mono<Void> awaitIdle() {
        Mono<Void> next = nextWakeUp();
        return inFlight.get() == 0 ? Mono.empty() : next.then(Mono.defer(this::awaitIdle));
    }

    private void onGateChange() {
        if (gate.isPaused()) {
            receiveInterrupt.get().tryEmitEmpty();
        }
        signal();
    }

    private Mono<Void> nextWakeUp() {
        return wakeUp.get().asMono();
    }

    private void signal() {
        wakeUp.getAndSet(Sinks.empty()).tryEmitEmpty();
    }
}
