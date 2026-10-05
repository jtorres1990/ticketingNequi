package com.nequi.ticketing.infrastructure.adapter.sqs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.application.port.in.ProcessOrderCommand;
import com.nequi.ticketing.application.port.in.ProcessOrderUseCase;
import com.nequi.ticketing.application.port.in.ProvisionEventCommand;
import com.nequi.ticketing.application.port.in.ProvisionEventUseCase;
import com.nequi.ticketing.application.port.out.EventProvisioningRequested;
import com.nequi.ticketing.application.port.out.OrderProcessingRequested;
import com.nequi.ticketing.application.port.out.PublishResult;
import com.nequi.ticketing.application.port.out.PublisherAvailability;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.ConsumerLoopSettings;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.OrderConsumerSettings;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.OrderQueueConsumer;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.ProvisioningConsumerSettings;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.ProvisioningQueueConsumer;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.SwitchableConsumptionGate;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitState;
import com.nequi.ticketing.infrastructure.adapter.out.sqs.SqsPublisherSettings;
import com.nequi.ticketing.infrastructure.adapter.out.sqs.SqsQueuePublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * INC-006 adapters against LocalStack 4.14.0 (ADR-038): publication of MSG-001 / MSG-002 by CMP-011 and
 * consumption by CMP-012 / CMP-025 with the production clients, including visibility changes, the receive
 * counter, the redrive to the DLQ created by the fixture, the heartbeat, pause and ordered shutdown. The
 * use cases are doubles: the end-to-end flow with the real use cases is verified in INC-010.
 */
class SqsAdaptersIT {

    private static final Duration SHORT = Duration.ofSeconds(1);

    private static SqsAsyncClient publication;
    private static SqsAsyncClient consumption;
    private final List<Runnable> cleanup = new ArrayList<>();

    @BeforeAll
    static void clients() {
        publication = SqsClientFactory.forPublication(LocalStackSqsSupport.connection(), LocalStackSqsSupport.localCredentials());
        consumption = SqsClientFactory.forConsumption(LocalStackSqsSupport.connection(), LocalStackSqsSupport.localCredentials());
    }

    @AfterEach
    void stopConsumers() {
        cleanup.forEach(Runnable::run);
    }

    @Test
    @DisplayName("FR-005 FR-007 MSG-001 published by CMP-011 is consumed by CMP-012 with its orderId, correlationId, first reception and trace context, then deleted")
    void ordersRoundTrip() {
        LocalStackSqsSupport.Queues queues = LocalStackSqsSupport.createQueues();
        SqsQueuePublisher publisher = publisher(queues);
        RecordingOrders useCase = new RecordingOrders(command -> Mono.just(MessageDisposition.delete(DispositionReason.CONFIRMED)));
        start(orders(queues, OrderConsumerSettings.deployed(queues.orders()), useCase, new SwitchableConsumptionGate()));
        String orderId = UUID.randomUUID().toString();

        PublishResult result = publisher.publish(new OrderProcessingRequested(orderId, UUID.randomUUID().toString(),
                        Instant.parse("2026-10-04T10:00:00Z"), "corr-it", OrderProcessingRequested.Publisher.API))
                .contextWrite(context -> context.put(TraceContext.KEY, "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
                .block(Duration.ofSeconds(5));

        assertThat(result).isEqualTo(PublishResult.PUBLISHED);
        await().atMost(Duration.ofSeconds(30)).until(() -> useCase.commands.size() == 1);
        ProcessOrderCommand command = useCase.commands.getFirst();
        assertThat(command.orderId()).isEqualTo(orderId);
        assertThat(command.correlationId()).isEqualTo("corr-it");
        assertThat(command.delivery().receiveCount()).isEqualTo(1);
        assertThat(command.delivery().lastReception()).isFalse();
        assertThat(useCase.traces).containsExactly("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        awaitEmpty(queues.orders());
    }

    @Test
    @DisplayName("FR-001 MSG-002 published by CMP-011 is consumed by CMP-025 with its eventId and correlationId, then deleted")
    void provisioningRoundTrip() {
        LocalStackSqsSupport.Queues queues = LocalStackSqsSupport.createQueues();
        RecordingProvisioning useCase = new RecordingProvisioning(command -> Mono.just(MessageDisposition.delete(DispositionReason.ENABLED)));
        start(provisioning(queues, ProvisioningConsumerSettings.deployed(queues.provisioning()), useCase));
        String eventId = UUID.randomUUID().toString();

        assertThat(publisher(queues).publish(new EventProvisioningRequested(eventId, "corr-prov")).block(Duration.ofSeconds(5)))
                .isEqualTo(PublishResult.PUBLISHED);

        await().atMost(Duration.ofSeconds(30)).until(() -> useCase.commands.size() == 1);
        assertThat(useCase.commands.getFirst().eventId()).isEqualTo(eventId);
        assertThat(useCase.commands.getFirst().correlationId()).isEqualTo("corr-prov");
        awaitEmpty(queues.provisioning());
    }

    @Test
    @DisplayName("ALT-004 ADR-029 Retry and PostponeUntil change the visibility; ApproximateReceiveCount grows with every reception")
    void retryAndPostponeByVisibility() {
        LocalStackSqsSupport.Queues queues = LocalStackSqsSupport.createQueues();
        AtomicInteger calls = new AtomicInteger();
        RecordingOrders useCase = new RecordingOrders(command -> switch (calls.incrementAndGet()) {
            case 1 -> Mono.just(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE));
            case 2 -> Mono.just(MessageDisposition.postponeUntil(Instant.now().plusSeconds(2), DispositionReason.LEASE_HELD_ELSEWHERE));
            default -> Mono.just(MessageDisposition.delete(DispositionReason.CONFIRMED));
        });
        start(orders(queues, new OrderConsumerSettings(fast(queues.orders(), 1, List.of(Duration.ofSeconds(2))),
                Duration.ofSeconds(30)), useCase, new SwitchableConsumptionGate()));

        publish(queues);

        await().atMost(Duration.ofSeconds(30)).until(() -> useCase.commands.size() == 3);
        assertThat(useCase.commands.stream().map(command -> command.delivery().receiveCount()).toList())
                .containsExactly(1, 2, 3);
        assertThat(Duration.between(useCase.receivedAt.get(0), useCase.receivedAt.get(1)))
                .as("retry backoff of 2 s with 20 % jitter").isGreaterThanOrEqualTo(Duration.ofMillis(1_500));
        assertThat(Duration.between(useCase.receivedAt.get(1), useCase.receivedAt.get(2)))
                .as("postponed until the lease end, 2 s later").isGreaterThanOrEqualTo(Duration.ofMillis(1_500));
        awaitEmpty(queues.orders());
    }

    @Test
    @DisplayName("TC-010 ERR-005 a poison message is received 5 times with short visibility (the 5th as last reception) and then redriven to the DLQ")
    void poisonMessageReachesTheDlq() {
        LocalStackSqsSupport.Queues queues = LocalStackSqsSupport.createQueues();
        RecordingOrders useCase = new RecordingOrders(command -> Mono.just(MessageDisposition.poison(DispositionReason.UNREADABLE_MESSAGE)));
        start(orders(queues, new OrderConsumerSettings(fast(queues.orders(), 1, List.of(SHORT)), Duration.ofSeconds(30)),
                useCase, new SwitchableConsumptionGate()));

        consumption.sendMessage(b -> b.queueUrl(queues.orders()).messageBody("{\"messageType\":\"Unknown\"}")).join();

        await().atMost(Duration.ofSeconds(60)).until(() -> dlqDepth(queues.ordersDlq()) == 1);
        assertThat(useCase.commands).hasSize(5).allMatch(command -> !command.readable());
        assertThat(useCase.commands.stream().map(command -> command.delivery().receiveCount()).toList())
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(useCase.commands.getLast().delivery().lastReception()).isTrue();
        List<Message> parked = consumption.receiveMessage(b -> b.queueUrl(queues.ordersDlq()).waitTimeSeconds(1)).join().messages();
        assertThat(parked).singleElement().satisfies(message -> assertThat(message.body()).contains("Unknown"));
    }

    @Test
    @DisplayName("ADR-029 ADR-039 heartbeat: while provisioning progresses the visibility is extended and the message is not redelivered")
    void heartbeatKeepsTheMessageInvisible() {
        LocalStackSqsSupport.Queues queues = LocalStackSqsSupport.createQueues(Map.of(),
                Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "3"));
        RecordingProvisioning useCase = new RecordingProvisioning(command -> Flux.interval(Duration.ofMillis(500))
                .take(14)
                .doOnNext(tick -> command.progress().batchWritten(tick.intValue() + 1))
                .then(Mono.just(MessageDisposition.delete(DispositionReason.ENABLED))));
        start(provisioning(queues, new ProvisioningConsumerSettings(fastSingle(queues.provisioning()),
                SHORT, Duration.ofSeconds(3), Duration.ofSeconds(2)), useCase));

        publisher(queues).publish(new EventProvisioningRequested(UUID.randomUUID().toString(), "corr-hb")).block(Duration.ofSeconds(5));

        await().atMost(Duration.ofSeconds(30)).until(() -> useCase.commands.size() == 1);
        List<Message> stolen = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        while (System.nanoTime() < deadline) {
            stolen.addAll(consumption.receiveMessage(b -> b.queueUrl(queues.provisioning()).waitTimeSeconds(1)).join().messages());
        }
        assertThat(stolen).as("not visible to another consumer while the heartbeat runs").isEmpty();
        awaitEmpty(queues.provisioning());
        assertThat(useCase.commands).hasSize(1);
    }

    @Test
    @DisplayName("ADR-029 ADR-039 without progress the heartbeat stops and the message becomes visible again after its visibility")
    void heartbeatStopsWithoutProgress() {
        LocalStackSqsSupport.Queues queues = LocalStackSqsSupport.createQueues(Map.of(),
                Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "3"));
        AtomicBoolean release = new AtomicBoolean();
        RecordingProvisioning useCase = new RecordingProvisioning(command -> Flux.interval(Duration.ofMillis(200))
                .takeUntil(tick -> release.get())
                .then(Mono.just(MessageDisposition.delete(DispositionReason.ENABLED))));
        start(provisioning(queues, new ProvisioningConsumerSettings(fastSingle(queues.provisioning()),
                SHORT, Duration.ofSeconds(3), Duration.ofSeconds(2)), useCase));

        publisher(queues).publish(new EventProvisioningRequested(UUID.randomUUID().toString(), "corr-stall")).block(Duration.ofSeconds(5));

        await().atMost(Duration.ofSeconds(30)).until(() -> useCase.commands.size() == 1);
        List<Message> redelivered = new CopyOnWriteArrayList<>();
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            redelivered.addAll(consumption.receiveMessage(b -> b.queueUrl(queues.provisioning()).waitTimeSeconds(1)
                    .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)).join().messages());
            return !redelivered.isEmpty();
        });
        assertThat(redelivered.getFirst().attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)).isEqualTo("2");
        release.set(true);
    }

    @Test
    @DisplayName("NFR-003 concurrency 16: 40 messages are processed with at most 16 in flight and all are deleted")
    void boundedConcurrencyAgainstLocalStack() {
        LocalStackSqsSupport.Queues queues = LocalStackSqsSupport.createQueues();
        AtomicInteger current = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        Set<String> processed = ConcurrentHashMap.newKeySet();
        RecordingOrders useCase = new RecordingOrders(command -> Mono.fromRunnable(() ->
                        maximum.accumulateAndGet(current.incrementAndGet(), Math::max))
                .then(Mono.delay(Duration.ofMillis(300)))
                .doOnNext(tick -> processed.add(command.orderId()))
                .doFinally(signal -> current.decrementAndGet())
                .thenReturn(MessageDisposition.delete(DispositionReason.CONFIRMED)));
        start(orders(queues, OrderConsumerSettings.deployed(queues.orders()), useCase, new SwitchableConsumptionGate()));
        SqsQueuePublisher publisher = publisher(queues);

        Flux.range(0, 40).flatMap(index -> publisher.publish(new OrderProcessingRequested(UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(), Instant.now(), "corr-" + index, OrderProcessingRequested.Publisher.SWEEP)), 8)
                .collectList().block(Duration.ofSeconds(30));

        await().atMost(Duration.ofSeconds(60)).until(() -> processed.size() == 40);
        assertThat(maximum.get()).isBetween(1, 16);
        awaitEmpty(queues.orders());
    }

    @Test
    @DisplayName("ADR-035 a paused gate stops the reception (no reception consumed) and reopening resumes it")
    void pausedGate() {
        LocalStackSqsSupport.Queues queues = LocalStackSqsSupport.createQueues();
        SwitchableConsumptionGate gate = new SwitchableConsumptionGate();
        gate.pause();
        RecordingOrders useCase = new RecordingOrders(command -> Mono.just(MessageDisposition.delete(DispositionReason.CONFIRMED)));
        start(orders(queues, new OrderConsumerSettings(fast(queues.orders(), 2, List.of(SHORT)), Duration.ofSeconds(30)),
                useCase, gate));

        publish(queues);
        await().pollDelay(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(6)).until(() -> true);
        assertThat(useCase.commands).isEmpty();
        assertThat(LocalStackSqsSupport.attributes(queues.orders())).containsEntry("ApproximateNumberOfMessages", "1");

        gate.open();
        await().atMost(Duration.ofSeconds(20)).until(() -> useCase.commands.size() == 1);
        assertThat(useCase.commands.getFirst().delivery().receiveCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-037 ordered shutdown: the message in flight completes before stop returns and nothing more is received")
    void orderedShutdown() {
        LocalStackSqsSupport.Queues queues = LocalStackSqsSupport.createQueues();
        RecordingOrders useCase = new RecordingOrders(command -> Mono.delay(Duration.ofSeconds(2))
                .thenReturn(MessageDisposition.delete(DispositionReason.CONFIRMED)));
        OrderQueueConsumer consumer = orders(queues, OrderConsumerSettings.deployed(queues.orders()), useCase,
                new SwitchableConsumptionGate());
        consumer.start();
        publish(queues);
        await().atMost(Duration.ofSeconds(30)).until(() -> useCase.commands.size() == 1);

        consumer.stop().block(Duration.ofSeconds(10));

        assertThat(consumer.inFlight()).isZero();
        awaitEmpty(queues.orders());
        publish(queues);
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).until(() -> true);
        assertThat(useCase.commands).hasSize(1);
    }

    @Test
    @DisplayName("ADR-026 a publication to a missing queue is FAILED at once and does not open the circuit")
    void missingQueueIsDefinitive() {
        LocalStackSqsSupport.Queues queues = LocalStackSqsSupport.createQueues();
        SqsQueuePublisher publisher = SqsQueuePublisher.create(publication,
                SqsPublisherSettings.deployed(queues.orders().replace("ticketing-orders", "missing"), queues.provisioning()),
                Instant::now, SqsEvents.NONE);

        for (int attempt = 0; attempt < 12; attempt++) {
            assertThat(publisher.publish(new OrderProcessingRequested(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    Instant.now(), "c", OrderProcessingRequested.Publisher.API)).block(Duration.ofSeconds(5)))
                    .isEqualTo(PublishResult.FAILED);
        }
        assertThat(publisher.circuit().state()).isEqualTo(CircuitState.CLOSED);
        assertThat(publisher.availability()).isEqualTo(PublisherAvailability.available());
    }

    // ------------------------------------------------------------------ helpers

    private static ConsumerLoopSettings fast(String queueUrl, int waitSeconds, List<Duration> backoff) {
        return new ConsumerLoopSettings(queueUrl, Duration.ofSeconds(waitSeconds), 10, 16, 5, backoff, 0.2, SHORT, SHORT,
                Duration.ofSeconds(2));
    }

    /** Provisioning-like loop (1 message, concurrency 1) with short waits. */
    private static ConsumerLoopSettings fastSingle(String queueUrl) {
        return new ConsumerLoopSettings(queueUrl, SHORT, 1, 1, 5, List.of(SHORT), 0.2, SHORT, SHORT, Duration.ofSeconds(2));
    }

    private SqsQueuePublisher publisher(LocalStackSqsSupport.Queues queues) {
        return SqsQueuePublisher.create(publication, SqsPublisherSettings.deployed(queues.orders(), queues.provisioning()),
                Instant::now, SqsEvents.NONE);
    }

    private void publish(LocalStackSqsSupport.Queues queues) {
        assertThat(publisher(queues).publish(new OrderProcessingRequested(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), Instant.now(), "corr", OrderProcessingRequested.Publisher.API))
                .block(Duration.ofSeconds(5))).isEqualTo(PublishResult.PUBLISHED);
    }

    private OrderQueueConsumer orders(LocalStackSqsSupport.Queues queues, OrderConsumerSettings settings,
            ProcessOrderUseCase useCase, SwitchableConsumptionGate gate) {
        OrderQueueConsumer consumer = OrderQueueConsumer.create(consumption, settings, useCase, gate, SqsEvents.NONE);
        cleanup.add(() -> consumer.stop().block(Duration.ofSeconds(30)));
        return consumer;
    }

    private ProvisioningQueueConsumer provisioning(LocalStackSqsSupport.Queues queues,
            ProvisioningConsumerSettings settings, ProvisionEventUseCase useCase) {
        ProvisioningQueueConsumer consumer = ProvisioningQueueConsumer.create(consumption, settings, useCase, SqsEvents.NONE);
        cleanup.add(() -> consumer.stop().block(Duration.ofSeconds(30)));
        return consumer;
    }

    private static void start(OrderQueueConsumer consumer) {
        consumer.start();
    }

    private static void start(ProvisioningQueueConsumer consumer) {
        consumer.start();
    }

    private static void awaitEmpty(String queueUrl) {
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            Map<String, String> attributes = LocalStackSqsSupport.attributes(queueUrl);
            return "0".equals(attributes.get("ApproximateNumberOfMessages"))
                    && "0".equals(attributes.get("ApproximateNumberOfMessagesNotVisible"));
        });
    }

    private static int dlqDepth(String dlqUrl) {
        return Integer.parseInt(LocalStackSqsSupport.attributes(dlqUrl).get("ApproximateNumberOfMessages"));
    }

    /** Process Order double recording commands, reception instants and trace context. */
    static final class RecordingOrders implements ProcessOrderUseCase {

        final List<ProcessOrderCommand> commands = new CopyOnWriteArrayList<>();
        final List<Instant> receivedAt = new CopyOnWriteArrayList<>();
        final List<String> traces = new CopyOnWriteArrayList<>();
        private final Function<ProcessOrderCommand, Mono<MessageDisposition>> answer;

        RecordingOrders(Function<ProcessOrderCommand, Mono<MessageDisposition>> answer) {
            this.answer = answer;
        }

        @Override
        public Mono<MessageDisposition> process(ProcessOrderCommand command) {
            return Mono.deferContextual(context -> {
                commands.add(command);
                receivedAt.add(Instant.now());
                TraceContext.from(context).ifPresent(traces::add);
                return answer.apply(command);
            }).subscribeOn(Schedulers.parallel());
        }
    }

    /** Provision Event double recording commands. */
    static final class RecordingProvisioning implements ProvisionEventUseCase {

        final List<ProvisionEventCommand> commands = new CopyOnWriteArrayList<>();
        private final Function<ProvisionEventCommand, Mono<MessageDisposition>> answer;

        RecordingProvisioning(Function<ProvisionEventCommand, Mono<MessageDisposition>> answer) {
            this.answer = answer;
        }

        @Override
        public Mono<MessageDisposition> provision(ProvisionEventCommand command) {
            return Mono.defer(() -> {
                commands.add(command);
                return answer.apply(command);
            });
        }
    }
}
