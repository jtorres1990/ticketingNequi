package com.nequi.ticketing.infrastructure.adapter.in.sqs;

import static com.nequi.ticketing.infrastructure.adapter.sqs.SqsTestMessages.ORDER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.application.port.in.Delivery;
import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.application.port.in.ProcessOrderCommand;
import com.nequi.ticketing.application.port.in.ProcessOrderUseCase;
import com.nequi.ticketing.infrastructure.adapter.sqs.FakeSqsClient;
import com.nequi.ticketing.infrastructure.adapter.sqs.RecordingSqsEvents;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsEvents;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsTestMessages;
import com.nequi.ticketing.infrastructure.adapter.sqs.TraceContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

class OrderQueueConsumerTest {

    private static final String URL = "http://sqs.local/000000000000/ticketing-orders";

    private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
    private final FakeSqsClient client = new FakeSqsClient();
    private final RecordingSqsEvents events = new RecordingSqsEvents();
    private final SwitchableConsumptionGate gate = new SwitchableConsumptionGate();
    private final ScriptedUseCase useCase = new ScriptedUseCase();
    private final OrderQueueConsumer consumer = OrderQueueConsumer.create(client, OrderConsumerSettings.deployed(URL),
            useCase, gate, events, scheduler, () -> 0.5);

    @AfterEach
    void stop() {
        consumer.stop().subscribe();
        scheduler.dispose();
    }

    @Test
    @DisplayName("FR-007 TC-005 a received MSG-001 invokes Process Order with its orderId and delivery; Delete deletes the message")
    void processesAndDeletes() {
        useCase.answer(command -> Mono.just(MessageDisposition.delete(DispositionReason.CONFIRMED)));
        consumer.start();

        FakeSqsClient.PendingReceive receive = client.pendingReceive();
        assertThat(receive.request().queueUrl()).isEqualTo(URL);
        assertThat(receive.request().maxNumberOfMessages()).isEqualTo(10);
        assertThat(receive.request().waitTimeSeconds()).isEqualTo(20);
        assertThat(receive.request().messageSystemAttributeNames())
                .containsExactly(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
        assertThat(receive.request().messageAttributeNames()).containsExactly("All");
        receive.complete(SqsTestMessages.order("m-1", 2));

        assertThat(useCase.commands()).singleElement().satisfies(command -> {
            assertThat(command.orderId()).isEqualTo(ORDER_ID);
            assertThat(command.correlationId()).isEqualTo("corr-1");
            assertThat(command.delivery()).isEqualTo(new Delivery(2, false));
        });
        assertThat(client.deletes()).singleElement()
                .satisfies(request -> assertThat(request.receiptHandle()).isEqualTo("receipt-m-1"));
        assertThat(client.pendingReceives()).isEqualTo(1);
        assertThat(consumer.running()).isTrue();
    }

    @Test
    @DisplayName("ERR-005 ADR-029 the 5th reception is delivered as the last one and the trace context reaches the use case")
    void lastReceptionAndTraceContext() {
        useCase.answer(command -> Mono.deferContextual(context -> {
            useCase.traces.add(TraceContext.from(context).orElse("none"));
            return Mono.just(MessageDisposition.retry(DispositionReason.EXHAUSTED));
        }));
        consumer.start();

        client.pendingReceive().complete(SqsTestMessages.message("m-5", SqsTestMessages.orderBody(ORDER_ID), 5,
                Map.of("traceparent", "00-trace-span-01")));

        assertThat(useCase.commands().getFirst().delivery()).isEqualTo(new Delivery(5, true));
        assertThat(useCase.traces).containsExactly("00-trace-span-01");
        assertThat(client.visibilityChanges().getFirst().visibilityTimeout()).isEqualTo(60);
    }

    @Test
    @DisplayName("ERR-005 an unreadable message is passed to the use case as unreadable (rule 1) and reported")
    void unreadableMessage() {
        useCase.answer(command -> Mono.just(MessageDisposition.poison(DispositionReason.UNREADABLE_MESSAGE)));
        consumer.start();

        client.pendingReceive().complete(SqsTestMessages.message("m-bad", "{\"messageType\":\"Other\"}", 1, Map.of()));

        assertThat(useCase.commands()).singleElement().satisfies(command -> {
            assertThat(command.readable()).isFalse();
            assertThat(command.unreadableReason()).isEqualTo("unknown message type");
        });
        assertThat(events.recorded()).containsExactly("unreadable:orders:m-bad:unknown message type");
        assertThat(client.visibilityChanges().getFirst().visibilityTimeout()).isEqualTo(10);
    }

    @Test
    @DisplayName("ALT-004 TC-009 an error of the use case is a transient failure retried by visibility backoff")
    void useCaseErrorIsRetried() {
        useCase.answer(command -> Mono.error(new IllegalStateException("dynamodb down")));
        consumer.start();

        client.pendingReceive().complete(SqsTestMessages.order("m-err", 3));

        assertThat(client.visibilityChanges()).singleElement()
                .satisfies(request -> assertThat(request.visibilityTimeout()).isEqualTo(30));
        assertThat(events.recorded()).containsExactly("processingFailed:orders:m-err");
        assertThat(client.deletes()).isEmpty();
    }

    @Test
    @DisplayName("ADR-029 the processing cap of 30 s per message turns a hanging use case into a transient failure")
    void processingCap() {
        useCase.answer(command -> Mono.never());
        consumer.start();
        client.pendingReceive().complete(SqsTestMessages.order("m-slow", 1));

        scheduler.advanceTimeBy(Duration.ofSeconds(29));
        assertThat(client.visibilityChanges()).isEmpty();
        scheduler.advanceTimeBy(Duration.ofSeconds(1));

        assertThat(client.visibilityChanges()).singleElement()
                .satisfies(request -> assertThat(request.visibilityTimeout()).isEqualTo(5));
        assertThat(events.causes()).singleElement().isInstanceOf(TimeoutException.class);
        assertThat(consumer.inFlight()).isZero();
    }

    @Test
    @DisplayName("TC-009 an empty result of the use case is treated as a transient failure")
    void emptyResultIsRetried() {
        useCase.answer(command -> Mono.empty());
        consumer.start();

        client.pendingReceive().complete(SqsTestMessages.order("m-empty", 4));

        assertThat(client.visibilityChanges().getFirst().visibilityTimeout()).isEqualTo(60);
    }

    @Test
    @DisplayName("NFR-003 concurrency 16: receptions ask only for free slots and stop while 16 messages are in flight")
    void boundedConcurrency() {
        List<Sinks.One<MessageDisposition>> held = new CopyOnWriteArrayList<>();
        useCase.answer(command -> {
            Sinks.One<MessageDisposition> sink = Sinks.one();
            held.add(sink);
            return sink.asMono();
        });
        consumer.start();

        client.pendingReceive().complete(messages(0, 10));
        assertThat(consumer.inFlight()).isEqualTo(10);
        assertThat(client.pendingReceive().request().maxNumberOfMessages()).isEqualTo(6);
        client.pendingReceive().complete(messages(10, 6));
        assertThat(consumer.inFlight()).isEqualTo(16);
        assertThat(client.pendingReceives()).isZero();

        held.getFirst().tryEmitValue(MessageDisposition.delete(DispositionReason.CONFIRMED));
        assertThat(consumer.inFlight()).isEqualTo(15);
        assertThat(client.pendingReceive().request().maxNumberOfMessages()).isEqualTo(1);
        assertThat(client.receives()).hasSize(3);
    }

    @Test
    @DisplayName("ADR-035 ADR-039 while the gate is paused the loop does not receive; an in-flight long poll is abandoned; reopening resumes")
    void pauseAndResume() {
        gate.pause();
        consumer.start();
        assertThat(client.receives()).isEmpty();

        gate.open();
        FakeSqsClient.PendingReceive first = client.pendingReceive();
        assertThat(first).isNotNull();

        gate.pause();
        assertThat(first.future().isCancelled()).isTrue();
        assertThat(client.pendingReceives()).isZero();
        scheduler.advanceTimeBy(Duration.ofMinutes(5));
        assertThat(client.receives()).hasSize(1);

        gate.open();
        assertThat(client.pendingReceives()).isEqualTo(1);
        assertThat(client.receives()).hasSize(2);
    }

    @Test
    @DisplayName("ADR-035 half-open: the loop receives only the permitted probe messages until the gate changes")
    void probing() {
        useCase.answer(command -> Mono.just(MessageDisposition.delete(DispositionReason.CONFIRMED)));
        gate.probe(3);
        consumer.start();

        assertThat(client.pendingReceive().request().maxNumberOfMessages()).isEqualTo(3);
        client.pendingReceive().complete(SqsTestMessages.order("p-1", 1));
        assertThat(client.pendingReceive().request().maxNumberOfMessages()).isEqualTo(2);
        client.pendingReceive().complete(SqsTestMessages.order("p-2", 1), SqsTestMessages.order("p-3", 1));
        assertThat(client.pendingReceives()).isZero();
        assertThat(useCase.commands()).hasSize(3);

        gate.open();
        assertThat(client.pendingReceive().request().maxNumberOfMessages()).isEqualTo(10);
    }

    @Test
    @DisplayName("ADR-035 IV-004 after a receive error the loop continues after 1 s, doubling up to 30 s, and resets after a success")
    void receiveErrorsBackOff() {
        useCase.answer(command -> Mono.just(MessageDisposition.delete(DispositionReason.CONFIRMED)));
        consumer.start();

        client.pendingReceive().fail(SdkClientException.create("connection refused"));
        assertThat(client.pendingReceives()).isZero();
        scheduler.advanceTimeBy(Duration.ofMillis(999));
        assertThat(client.pendingReceives()).isZero();
        scheduler.advanceTimeBy(Duration.ofMillis(1));
        client.pendingReceive().fail(SdkClientException.create("connection refused"));
        scheduler.advanceTimeBy(Duration.ofSeconds(2));
        for (int failure = 3; failure <= 7; failure++) {
            client.pendingReceive().fail(SdkClientException.create("connection refused"));
            scheduler.advanceTimeBy(Duration.ofSeconds(30));
        }
        client.pendingReceive().complete();
        client.pendingReceive().fail(SdkClientException.create("connection refused"));

        assertThat(events.waits()).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4),
                Duration.ofSeconds(8), Duration.ofSeconds(16), Duration.ofSeconds(30), Duration.ofSeconds(30),
                Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("ADR-037 ordered shutdown: stop abandons the long poll, receives nothing more and completes after the messages in flight")
    void orderedShutdown() {
        Sinks.One<MessageDisposition> held = Sinks.one();
        useCase.answer(command -> held.asMono());
        consumer.start();
        client.pendingReceive().complete(SqsTestMessages.order("m-1", 1));
        FakeSqsClient.PendingReceive polling = client.pendingReceive();
        AtomicBoolean stopped = new AtomicBoolean();

        consumer.stop().subscribe(null, null, () -> stopped.set(true));

        assertThat(polling.future().isCancelled()).isTrue();
        assertThat(consumer.running()).isFalse();
        assertThat(stopped).isFalse();
        held.tryEmitValue(MessageDisposition.delete(DispositionReason.CONFIRMED));
        assertThat(stopped).isTrue();
        assertThat(client.deletes()).hasSize(1);
        assertThat(client.receives()).hasSize(2);
    }

    @Test
    @DisplayName("ADR-037 stopping a consumer that never started completes at once; starting twice runs one loop")
    void lifecycleGuards() {
        AtomicBoolean stopped = new AtomicBoolean();
        OrderQueueConsumer idle = OrderQueueConsumer.create(client, OrderConsumerSettings.deployed(URL), useCase, gate,
                SqsEvents.NONE);
        idle.stop().subscribe(null, null, () -> stopped.set(true));
        assertThat(stopped).isTrue();

        consumer.start();
        consumer.start();
        assertThat(client.receives()).hasSize(1);
        assertThatThrownBy(() -> OrderQueueConsumer.create(null, OrderConsumerSettings.deployed(URL), useCase, gate,
                SqsEvents.NONE)).isInstanceOf(NullPointerException.class);
    }

    private static Message[] messages(int from, int count) {
        return IntStream.range(from, from + count).mapToObj(index -> SqsTestMessages.order("m-" + index, 1))
                .toArray(Message[]::new);
    }

    /** Process Order double answering with a scripted function and recording every command. */
    static final class ScriptedUseCase implements ProcessOrderUseCase {

        private final List<ProcessOrderCommand> commands = new CopyOnWriteArrayList<>();
        final List<String> traces = new CopyOnWriteArrayList<>();
        private volatile Function<ProcessOrderCommand, Mono<MessageDisposition>> answer =
                command -> Mono.just(MessageDisposition.delete(DispositionReason.CONFIRMED));

        void answer(Function<ProcessOrderCommand, Mono<MessageDisposition>> answer) {
            this.answer = answer;
        }

        List<ProcessOrderCommand> commands() {
            return List.copyOf(commands);
        }

        @Override
        public Mono<MessageDisposition> process(ProcessOrderCommand command) {
            commands.add(command);
            return answer.apply(command);
        }
    }
}
