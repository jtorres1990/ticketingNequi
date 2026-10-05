package com.nequi.ticketing.infrastructure.adapter.in.sqs;

import static com.nequi.ticketing.infrastructure.adapter.sqs.SqsTestMessages.EVENT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.application.port.in.Delivery;
import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.application.port.in.ProvisionEventCommand;
import com.nequi.ticketing.application.port.in.ProvisionEventUseCase;
import com.nequi.ticketing.infrastructure.adapter.sqs.FakeSqsClient;
import com.nequi.ticketing.infrastructure.adapter.sqs.RecordingSqsEvents;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsEvents;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsTestMessages;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;

class ProvisioningQueueConsumerTest {

    private static final String URL = "http://sqs.local/000000000000/ticketing-event-provisioning";

    private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
    private final FakeSqsClient client = new FakeSqsClient();
    private final RecordingSqsEvents events = new RecordingSqsEvents();
    private final ScriptedUseCase useCase = new ScriptedUseCase();
    private final ProvisioningQueueConsumer consumer = ProvisioningQueueConsumer.create(client,
            ProvisioningConsumerSettings.deployed(URL), useCase, events, scheduler, () -> 0.5);

    @AfterEach
    void stop() {
        consumer.stop().subscribe();
        scheduler.dispose();
    }

    @Test
    @DisplayName("FR-001 MSG-002 receives one message at a time, invokes Provision Event with its eventId and correlationId and deletes on ENABLED")
    void processesOneMessageAtATime() {
        Sinks.One<MessageDisposition> held = Sinks.one();
        useCase.answer(command -> held.asMono());
        consumer.start();

        FakeSqsClient.PendingReceive receive = client.pendingReceive();
        assertThat(receive.request().maxNumberOfMessages()).isEqualTo(1);
        assertThat(receive.request().waitTimeSeconds()).isEqualTo(20);
        receive.complete(SqsTestMessages.provisioning("p-1", 1));
        assertThat(useCase.commands()).singleElement().satisfies(command -> {
            assertThat(command.eventId()).isEqualTo(EVENT_ID);
            assertThat(command.correlationId()).isEqualTo("corr-2");
            assertThat(command.delivery()).isEqualTo(new Delivery(1, false));
        });
        assertThat(client.pendingReceives()).as("concurrency 1: no reception while a message is in flight").isZero();

        held.tryEmitValue(MessageDisposition.delete(DispositionReason.ENABLED));
        assertThat(client.deletes()).singleElement()
                .satisfies(request -> assertThat(request.receiptHandle()).isEqualTo("receipt-p-1"));
        assertThat(client.pendingReceives()).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-029 ADR-039 heartbeat: every 30 s the visibility is extended to 120 s while there was progress within 60 s, then it stops for good")
    void heartbeatFollowsProgress() {
        Sinks.One<MessageDisposition> held = Sinks.one();
        useCase.answer(command -> held.asMono());
        consumer.start();
        client.pendingReceive().complete(SqsTestMessages.provisioning("p-hb", 1));
        ProvisionEventCommand command = useCase.commands().getFirst();

        scheduler.advanceTimeBy(Duration.ofSeconds(30));
        assertThat(heartbeats()).containsExactly(120);
        scheduler.advanceTimeBy(Duration.ofSeconds(10));
        command.progress().batchWritten(1);
        scheduler.advanceTimeBy(Duration.ofSeconds(20));
        assertThat(heartbeats()).containsExactly(120, 120);
        scheduler.advanceTimeBy(Duration.ofSeconds(30));
        assertThat(heartbeats()).as("50 s since the last progress").containsExactly(120, 120, 120);
        scheduler.advanceTimeBy(Duration.ofSeconds(30));
        assertThat(heartbeats()).as("80 s without progress: the heartbeat stops").hasSize(3);
        assertThat(events.recorded()).containsExactly("heartbeatStopped:provisioning:p-hb");

        command.progress().batchWritten(2);
        scheduler.advanceTimeBy(Duration.ofMinutes(5));
        assertThat(heartbeats()).hasSize(3);
        held.tryEmitValue(MessageDisposition.delete(DispositionReason.ENABLED));
        assertThat(client.deletes()).hasSize(1);
    }

    @Test
    @DisplayName("ADR-039 the heartbeat stops when the use case completes")
    void heartbeatStopsWithTheProcessing() {
        Sinks.One<MessageDisposition> held = Sinks.one();
        useCase.answer(command -> held.asMono());
        consumer.start();
        client.pendingReceive().complete(SqsTestMessages.provisioning("p-done", 2));

        scheduler.advanceTimeBy(Duration.ofSeconds(30));
        held.tryEmitValue(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
        scheduler.advanceTimeBy(Duration.ofMinutes(2));

        assertThat(client.visibilityChanges().stream().map(request -> request.visibilityTimeout()).toList())
                .as("one heartbeat, then the backoff of the second reception").containsExactly(120, 60);
    }

    @Test
    @DisplayName("ALT-004 provisioning backoff by visibility is 30/60/120/240 s; an error of the use case is transient")
    void backoffSchedule() {
        useCase.answer(command -> Mono.error(new IllegalStateException("throttled")));
        consumer.start();

        for (int reception = 1; reception <= 5; reception++) {
            client.pendingReceive().complete(SqsTestMessages.provisioning("p-" + reception, reception));
        }

        assertThat(client.visibilityChanges().stream().map(request -> request.visibilityTimeout()).toList())
                .containsExactly(30, 60, 120, 240, 240);
        assertThat(useCase.commands().getLast().delivery()).isEqualTo(new Delivery(5, true));
        assertThat(events.recorded()).hasSize(5).allMatch(text -> text.startsWith("processingFailed:provisioning"));
    }

    @Test
    @DisplayName("ERR-005 an unreadable MSG-002 is passed as unreadable; an empty result is retried; PostponeUntil changes the visibility")
    void unreadableEmptyAndPostpone() {
        useCase.answer(command -> command.readable()
                ? Mono.empty()
                : Mono.just(MessageDisposition.poison(DispositionReason.UNREADABLE_MESSAGE)));
        consumer.start();

        client.pendingReceive().complete(SqsTestMessages.message("p-bad", SqsTestMessages.provisioningBody(EVENT_ID), 1,
                Map.of()));
        client.pendingReceive().complete(SqsTestMessages.provisioning("p-empty", 1));
        useCase.answer(command -> Mono.just(MessageDisposition.postponeUntil(
                java.time.Instant.ofEpochMilli(scheduler.now(java.util.concurrent.TimeUnit.MILLISECONDS)).plusSeconds(42),
                DispositionReason.LEASE_HELD_ELSEWHERE)));
        client.pendingReceive().complete(SqsTestMessages.provisioning("p-leased", 1));

        assertThat(useCase.commands().getFirst().unreadableReason()).isEqualTo("missing correlationId attribute");
        assertThat(events.recorded()).contains("unreadable:provisioning:p-bad:missing correlationId attribute");
        assertThat(client.visibilityChanges().stream().map(request -> request.visibilityTimeout()).toList())
                .containsExactly(10, 30, 42);
    }

    @Test
    @DisplayName("ADR-037 ordered shutdown of the provisioning loop and lifecycle guards")
    void lifecycle() {
        consumer.start();
        assertThat(consumer.running()).isTrue();
        assertThat(consumer.inFlight()).isZero();
        java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();
        consumer.stop().subscribe(null, null, () -> stopped.set(true));
        assertThat(stopped).isTrue();
        assertThat(consumer.running()).isFalse();
        assertThat(ProvisioningQueueConsumer.create(client, ProvisioningConsumerSettings.deployed(URL), useCase,
                SqsEvents.NONE)).isNotNull();
        assertThatThrownBy(() -> ProvisioningQueueConsumer.create(client, null, useCase, SqsEvents.NONE))
                .isInstanceOf(NullPointerException.class);
    }

    private List<Integer> heartbeats() {
        return client.visibilityChanges().stream().map(request -> request.visibilityTimeout()).toList();
    }

    /** Provision Event double answering with a scripted function and recording every command. */
    static final class ScriptedUseCase implements ProvisionEventUseCase {

        private final List<ProvisionEventCommand> commands = new CopyOnWriteArrayList<>();
        private volatile Function<ProvisionEventCommand, Mono<MessageDisposition>> answer =
                command -> Mono.just(MessageDisposition.delete(DispositionReason.ENABLED));

        void answer(Function<ProvisionEventCommand, Mono<MessageDisposition>> answer) {
            this.answer = answer;
        }

        List<ProvisionEventCommand> commands() {
            return List.copyOf(commands);
        }

        @Override
        public Mono<MessageDisposition> provision(ProvisionEventCommand command) {
            commands.add(command);
            return answer.apply(command);
        }
    }
}
