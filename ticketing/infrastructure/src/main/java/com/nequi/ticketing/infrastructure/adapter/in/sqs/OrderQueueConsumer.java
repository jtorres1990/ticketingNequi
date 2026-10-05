package com.nequi.ticketing.infrastructure.adapter.in.sqs;

import com.nequi.ticketing.application.port.in.Delivery;
import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.application.port.in.ProcessOrderCommand;
import com.nequi.ticketing.application.port.in.ProcessOrderUseCase;
import com.nequi.ticketing.infrastructure.adapter.sqs.MessageCodec;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsEvents;
import com.nequi.ticketing.infrastructure.adapter.sqs.TraceContext;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * CMP-012 SQS Orders consumer adapter (TC-005, TC-010, FR-007; ADR-029, ADR-039): reactive loop on
 * {@code ticketing-orders} that reads MSG-001, invokes Process Order (CMP-007) with the delivery
 * ({@code ApproximateReceiveCount}, last reception at {@code maxReceiveCount}) and translates the typed
 * result into SQS operations. The use case is bounded by the processing cap of 30 s; an error or the cap
 * is a transient failure retried by visibility backoff. An unreadable message (unknown type or version,
 * missing identifier) is passed to the use case as unreadable, which applies rule 1 (poison).
 *
 * <p>Reception is governed by a {@link ConsumptionGate}; INC-007 connects it to the Payment Mock circuit
 * breaker (paused while open, limited to the probe messages while half-open, ADR-035).
 */
public final class OrderQueueConsumer {

    static final String QUEUE = "orders";

    private final OrderConsumerSettings settings;
    private final ProcessOrderUseCase useCase;
    private final Scheduler scheduler;
    private final SqsEvents events;
    private final MessageActions actions;
    private final ConsumerLoop loop;

    private OrderQueueConsumer(SqsAsyncClient client, OrderConsumerSettings settings, ProcessOrderUseCase useCase,
            ConsumptionGate gate, SqsEvents events, Scheduler scheduler, DoubleSupplier random) {
        this.settings = settings;
        this.useCase = useCase;
        this.scheduler = scheduler;
        this.events = events;
        this.actions = new MessageActions(QUEUE, client, settings.loop(), scheduler, random, events);
        this.loop = new ConsumerLoop(QUEUE, client, settings.loop(), gate, scheduler, events, this::handle);
    }

    public static OrderQueueConsumer create(SqsAsyncClient client, OrderConsumerSettings settings,
            ProcessOrderUseCase useCase, ConsumptionGate gate, SqsEvents events) {
        return create(client, settings, useCase, gate, events, Schedulers.parallel(),
                () -> ThreadLocalRandom.current().nextDouble());
    }

    /** Variant with an explicit scheduler (virtual time in tests) and jitter source. */
    public static OrderQueueConsumer create(SqsAsyncClient client, OrderConsumerSettings settings,
            ProcessOrderUseCase useCase, ConsumptionGate gate, SqsEvents events, Scheduler scheduler,
            DoubleSupplier random) {
        return new OrderQueueConsumer(
                Objects.requireNonNull(client, "client"),
                Objects.requireNonNull(settings, "settings"),
                Objects.requireNonNull(useCase, "useCase"),
                Objects.requireNonNull(gate, "gate"),
                Objects.requireNonNull(events, "events"),
                Objects.requireNonNull(scheduler, "scheduler"),
                Objects.requireNonNull(random, "random"));
    }

    public void start() {
        loop.start();
    }

    /** Ordered shutdown: stops receiving and completes when the messages in flight are done (ADR-037). */
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
        ProcessOrderCommand command = switch (MessageCodec.decodeOrder(message.body(), attributes)) {
            case MessageCodec.Decoded.Readable<MessageCodec.OrderRequest> readable ->
                    ProcessOrderCommand.readable(readable.content().orderId(), readable.content().correlationId(), delivery);
            case MessageCodec.Decoded.Unreadable<MessageCodec.OrderRequest> unreadable -> {
                events.unreadableMessage(QUEUE, message.messageId(), unreadable.reason());
                yield ProcessOrderCommand.unreadable(unreadable.reason(), delivery);
            }
        };
        return Mono.defer(() -> useCase.process(command))
                .timeout(settings.processingCap(), scheduler)
                .switchIfEmpty(Mono.fromSupplier(() -> MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE)))
                .onErrorResume(error -> {
                    events.processingFailed(QUEUE, message.messageId(), error);
                    return Mono.just(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
                })
                .contextWrite(context -> TraceContext.with(context, attributes.get(TraceContext.KEY)))
                .flatMap(disposition -> actions.apply(message, delivery, disposition));
    }
}
