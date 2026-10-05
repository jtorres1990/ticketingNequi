package com.nequi.ticketing.infrastructure.adapter.in.sqs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitBreakerSettings;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitState;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.ManagedCircuitBreaker;
import com.nequi.ticketing.infrastructure.adapter.sqs.FakeSqsClient;
import com.nequi.ticketing.infrastructure.adapter.sqs.RecordingSqsEvents;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsTestMessages;
import com.nequi.ticketing.infrastructure.adapter.sqs.VirtualClock;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Pause and resume of the Orders loop (CMP-012) and of the reversal process gate (CMP-024) driven by the
 * Payment Mock circuit breaker (CMP-026; ADR-035, ADR-039), with the approved circuit values and virtual time.
 */
class CircuitGateBindingTest {

    private static final String URL = "http://sqs.local/000000000000/ticketing-orders";

    private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
    private final ManagedCircuitBreaker circuit = ManagedCircuitBreaker.create("payment-mock",
            CircuitBreakerSettings.paymentMock(), new VirtualClock(scheduler), IOException.class::isInstance);
    private final SwitchableConsumptionGate orders = new SwitchableConsumptionGate();
    private final SwitchableConsumptionGate reversals = new SwitchableConsumptionGate();
    private final FakeSqsClient client = new FakeSqsClient();
    private final OrderQueueConsumerTest.ScriptedUseCase useCase = new OrderQueueConsumerTest.ScriptedUseCase();
    private final AtomicReference<Supplier<Mono<String>>> payment =
            new AtomicReference<>(() -> Mono.error(new IOException("payment mock down")));
    private OrderQueueConsumer consumer;

    @AfterEach
    void stop() {
        if (consumer != null) {
            consumer.stop().subscribe();
        }
        scheduler.dispose();
    }

    @Test
    @DisplayName("ADR-038/mechanism circuit breaker: open pauses the Orders and reversal gates; after 15 s 3 probes in flight; closed resumes")
    void gatesFollowTheCircuit() {
        CircuitGateBinding.bind(circuit, scheduler, orders, reversals);
        assertThat(acquire(orders)).isEqualTo(10);
        assertThat(acquire(reversals)).isEqualTo(10);

        failCalls(10);
        assertThat(circuit.state()).isEqualTo(CircuitState.OPEN);
        assertThat(orders.isPaused()).isTrue();
        assertThat(reversals.isPaused()).isTrue();

        scheduler.advanceTimeBy(Duration.ofSeconds(15));
        assertThat(orders.isPaused()).isTrue();
        scheduler.advanceTimeBy(Duration.ofMillis(1));
        // Resilience4j stays OPEN until the next call: the binding lets the probes through on its own timer
        assertThat(circuit.state()).isEqualTo(CircuitState.OPEN);
        assertThat(orders.isPaused()).isFalse();
        assertThat(reversals.isPaused()).isFalse();
        assertThat(acquire(orders)).isEqualTo(3);
        assertThat(acquire(reversals)).isEqualTo(3);

        succeedCalls(1);
        assertThat(circuit.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(acquire(orders)).isEqualTo(3);
        succeedCalls(2);
        assertThat(circuit.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(acquire(orders)).isEqualTo(10);
        assertThat(acquire(reversals)).isEqualTo(10);
    }

    @Test
    @DisplayName("ADR-035 failed probes pause the gates again for another 15 s")
    void failedProbesPauseAgain() {
        CircuitGateBinding.bind(circuit, scheduler, orders);
        failCalls(10);
        scheduler.advanceTimeBy(Duration.ofSeconds(15).plusMillis(1));
        assertThat(orders.isPaused()).isFalse();

        failCalls(3);
        assertThat(circuit.state()).isEqualTo(CircuitState.OPEN);
        assertThat(orders.isPaused()).isTrue();
        scheduler.advanceTimeBy(Duration.ofSeconds(15));
        assertThat(orders.isPaused()).isTrue();
        scheduler.advanceTimeBy(Duration.ofMillis(1));
        assertThat(orders.isPaused()).isFalse();
        assertThat(acquire(orders)).isEqualTo(3);
    }

    @Test
    @DisplayName("ADR-035 binding to a circuit that is already open pauses at once; dispose stops driving the gates")
    void bindWhileOpenAndDispose() {
        failCalls(10);
        CircuitGateBinding binding = CircuitGateBinding.bind(circuit, scheduler, orders);
        assertThat(orders.isPaused()).isTrue();

        binding.dispose();
        assertThat(binding.isDisposed()).isTrue();
        scheduler.advanceTimeBy(Duration.ofSeconds(30));
        assertThat(orders.isPaused()).isTrue();
        assertThatThrownBy(() -> CircuitGateBinding.bind(circuit, scheduler))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("NFR-003 reconciliation is serialized: a state change caused while applying a mode is applied afterwards")
    void reentrantStateChangesAreSerialized() {
        failCalls(10);
        CircuitGateBinding.bind(circuit, scheduler, orders);
        orders.addListener(() -> {
            if (!orders.isPaused() && circuit.state() == CircuitState.OPEN) {
                succeedCalls(3);
            }
        });

        scheduler.advanceTimeBy(Duration.ofSeconds(15).plusMillis(1));

        assertThat(circuit.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(acquire(orders)).isEqualTo(10);
    }

    @Test
    @DisplayName("ADR-035 the default binding uses the parallel scheduler and applies the closed state at once")
    void defaultScheduler() {
        CircuitGateBinding binding = CircuitGateBinding.bind(circuit, orders);

        assertThat(acquire(orders)).isEqualTo(10);
        binding.dispose();
    }

    @Test
    @DisplayName("ADR-035 ADR-039 Orders loop: open circuit stops reception (no maxReceiveCount consumed); half-open receives up to 3 in flight; closed resumes 10")
    void ordersLoopPausesAndResumes() {
        startConsumer();
        FakeSqsClient.PendingReceive first = client.pendingReceive();
        assertThat(first.request().maxNumberOfMessages()).isEqualTo(10);

        first.complete(messages("f", 10));
        assertThat(circuit.state()).isEqualTo(CircuitState.OPEN);
        assertThat(useCase.commands()).hasSize(10);
        assertThat(client.pendingReceives()).isZero();
        scheduler.advanceTimeBy(Duration.ofSeconds(15));
        assertThat(client.pendingReceives()).isZero();
        int receptionsWhileOpen = client.receives().size();

        scheduler.advanceTimeBy(Duration.ofMillis(1));
        assertThat(client.receives()).hasSize(receptionsWhileOpen + 1);
        assertThat(client.pendingReceive().request().maxNumberOfMessages()).isEqualTo(3);

        payment.set(() -> Mono.just("approved"));
        client.pendingReceive().complete(messages("p", 1));
        assertThat(circuit.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(client.pendingReceive().request().maxNumberOfMessages()).isEqualTo(3);
        client.pendingReceive().complete(messages("q", 2));

        assertThat(circuit.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(client.pendingReceive().request().maxNumberOfMessages()).isEqualTo(10);
    }

    @Test
    @DisplayName("ADR-035 half-open: probe messages that end without calling the Payment Mock do not stall the loop")
    void probesWithoutPaymentCallsDoNotStall() {
        startConsumer();
        client.pendingReceive().complete(messages("f", 10));
        scheduler.advanceTimeBy(Duration.ofSeconds(15).plusMillis(1));

        useCase.answer(command -> Mono.just(MessageDisposition.delete(DispositionReason.CONFIRMED)));
        client.pendingReceive().complete(messages("d", 3));

        assertThat(circuit.state()).isEqualTo(CircuitState.OPEN);
        assertThat(client.pendingReceive().request().maxNumberOfMessages()).isEqualTo(3);
    }

    @Test
    @DisplayName("ADR-035 half-open: at most 3 messages in flight while the probes are being processed")
    void halfOpenBoundsMessagesInFlight() {
        startConsumer();
        client.pendingReceive().complete(messages("f", 10));
        scheduler.advanceTimeBy(Duration.ofSeconds(15).plusMillis(1));

        useCase.answer(command -> Mono.never());
        client.pendingReceive().complete(messages("s", 3));

        assertThat(consumer.inFlight()).isEqualTo(3);
        assertThat(client.pendingReceives()).isZero();
    }

    private void startConsumer() {
        useCase.answer(command -> Mono.defer(() -> payment.get().get())
                .transform(circuit.operator())
                .thenReturn(MessageDisposition.delete(DispositionReason.CONFIRMED))
                .onErrorResume(error -> Mono.just(MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE))));
        CircuitGateBinding.bind(circuit, scheduler, orders, reversals);
        consumer = OrderQueueConsumer.create(client, OrderConsumerSettings.deployed(URL), useCase, orders,
                new RecordingSqsEvents(), scheduler, () -> 0.5);
        consumer.start();
    }

    private static Message[] messages(String prefix, int count) {
        Message[] messages = new Message[count];
        for (int index = 0; index < count; index++) {
            messages[index] = SqsTestMessages.order(prefix + "-" + index, 1);
        }
        return messages;
    }

    private static int acquire(SwitchableConsumptionGate gate) {
        int granted = gate.acquire(10);
        gate.release(granted);
        return granted;
    }

    private void failCalls(int calls) {
        for (int call = 0; call < calls; call++) {
            Mono.error(new IOException("down")).transform(circuit.operator()).subscribe(value -> { }, error -> { });
        }
    }

    private void succeedCalls(int calls) {
        for (int call = 0; call < calls; call++) {
            Mono.just("ok").transform(circuit.operator()).subscribe();
        }
    }
}
