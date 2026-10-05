package com.nequi.ticketing.infrastructure.adapter.in.sqs;

import com.nequi.ticketing.application.port.in.Delivery;
import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.application.port.in.ProvisionEventCommand;
import com.nequi.ticketing.application.port.in.ProvisionEventUseCase;
import com.nequi.ticketing.infrastructure.adapter.sqs.MessageCodec;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsEvents;
import com.nequi.ticketing.infrastructure.adapter.sqs.TraceContext;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * CMP-025 SQS provisioning consumer adapter (TC-005, TC-010, FR-001; ADR-024, ADR-029, ADR-039): reactive
 * loop on {@code ticketing-event-provisioning} (1 message, concurrency 1) that reads MSG-002, invokes
 * Provision Event (CMP-022) and translates the typed result into SQS operations.
 *
 * <p>Visibility heartbeat: while the use case runs, every 30 s the visibility is extended to 120 s if the
 * use case reported progress (a written batch, {@code ProvisioningProgressListener}) within the last 60 s;
 * the start of the processing counts as progress. Once the window passes without progress the heartbeat
 * stops for good and the message becomes visible again when its visibility ends (the Event lease protects
 * the work, ADR-024). An error of the use case is a transient failure retried by visibility backoff.
 */
public final class ProvisioningQueueConsumer {

    static final String QUEUE = "provisioning";

    private final ProvisioningConsumerSettings settings;
    private final ProvisionEventUseCase useCase;
    private final Scheduler scheduler;
    private final SqsEvents events;
    private final MessageActions actions;
    private final ConsumerLoop loop;

    private ProvisioningQueueConsumer(SqsAsyncClient client, ProvisioningConsumerSettings settings,
            ProvisionEventUseCase useCase, SqsEvents events, Scheduler scheduler, DoubleSupplier random) {
        this.settings = settings;
        this.useCase = useCase;
        this.scheduler = scheduler;
        this.events = events;
        this.actions = new MessageActions(QUEUE, client, settings.loop(), scheduler, random, events);
        this.loop = new ConsumerLoop(QUEUE, client, settings.loop(), ConsumptionGate.alwaysOpen(), scheduler, events,
                this::handle);
    }

    public static ProvisioningQueueConsumer create(SqsAsyncClient client, ProvisioningConsumerSettings settings,
            ProvisionEventUseCase useCase, SqsEvents events) {
        return create(client, settings, useCase, events, Schedulers.parallel(),
                () -> ThreadLocalRandom.current().nextDouble());
    }

    /** Variant with an explicit scheduler (virtual time in tests) and jitter source. */
    public static ProvisioningQueueConsumer create(SqsAsyncClient client, ProvisioningConsumerSettings settings,
            ProvisionEventUseCase useCase, SqsEvents events, Scheduler scheduler, DoubleSupplier random) {
        return new ProvisioningQueueConsumer(
                Objects.requireNonNull(client, "client"),
                Objects.requireNonNull(settings, "settings"),
                Objects.requireNonNull(useCase, "useCase"),
                Objects.requireNonNull(events, "events"),
                Objects.requireNonNull(scheduler, "scheduler"),
                Objects.requireNonNull(random, "random"));
    }

    public void start() {
        loop.start();
    }

    /** Ordered shutdown: stops receiving and completes when the message in flight is done (ADR-037). */
    public Mono<Void> stop() {
        return loop.stop();
    }

    public boolean running() {
        return loop.running();
    }

    public int inFlight() {
        return loop.inFlight();
    }

    Mono<Void> handle(Message message) {
        Delivery delivery = actions.delivery(message);
        Map<String, String> attributes = MessageActions.attributes(message);
        AtomicLong lastProgress = new AtomicLong(scheduler.now(TimeUnit.MILLISECONDS));
        ProvisionEventCommand command = switch (MessageCodec.decodeProvisioning(message.body(), attributes)) {
            case MessageCodec.Decoded.Readable<MessageCodec.ProvisioningRequest> readable ->
                    ProvisionEventCommand.readable(readable.content().eventId(), readable.content().correlationId(),
                            delivery, batches -> lastProgress.set(scheduler.now(TimeUnit.MILLISECONDS)));
            case MessageCodec.Decoded.Unreadable<MessageCodec.ProvisioningRequest> unreadable -> {
                events.unreadableMessage(QUEUE, message.messageId(), unreadable.reason());
                yield ProvisionEventCommand.unreadable(unreadable.reason(), delivery);
            }
        };
        return Mono.defer(() -> {
            Disposable heartbeat = heartbeat(message, lastProgress);
            return Mono.defer(() -> useCase.provision(command))
                    .switchIfEmpty(Mono.fromSupplier(() -> MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE)))
                    .onErrorResume(error -> {
                        events.processingFailed(QUEUE, message.messageId(), error);
                        return Mono.just(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
                    })
                    .doFinally(signal -> heartbeat.dispose())
                    .contextWrite(context -> TraceContext.with(context, attributes.get(TraceContext.KEY)))
                    .flatMap(disposition -> actions.apply(message, delivery, disposition));
        });
    }

    private Disposable heartbeat(Message message, AtomicLong lastProgress) {
        int visibility = (int) settings.heartbeatVisibility().toSeconds();
        long window = settings.noProgressWindow().toMillis();
        return Flux.interval(settings.heartbeatInterval(), settings.heartbeatInterval(), scheduler)
                .takeWhile(tick -> {
                    boolean progressing = scheduler.now(TimeUnit.MILLISECONDS) - lastProgress.get() < window;
                    if (!progressing) {
                        events.heartbeatStopped(QUEUE, message.messageId());
                    }
                    return progressing;
                })
                .concatMap(tick -> actions.changeVisibility(message, visibility))
                .subscribe();
    }
}
