package com.nequi.ticketing.infrastructure.adapter.in.sqs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.application.port.in.Delivery;
import com.nequi.ticketing.application.port.in.DispositionReason;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.infrastructure.adapter.sqs.FakeSqsClient;
import com.nequi.ticketing.infrastructure.adapter.sqs.RecordingSqsEvents;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsTestMessages;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.model.Message;

/** Gate, settings and message actions of the consumer loops (ADR-029, ADR-035, ADR-039). */
class ConsumerSupportTest {

    private static final String URL = "http://sqs.local/000000000000/ticketing-orders";

    @Test
    @DisplayName("ADR-035 the switchable gate is open, paused (0 permits) or probing (limited permits, returned when unused)")
    void switchableGate() {
        SwitchableConsumptionGate gate = new SwitchableConsumptionGate();
        AtomicInteger changes = new AtomicInteger();
        gate.addListener(changes::incrementAndGet);

        assertThat(gate.acquire(10)).isEqualTo(10);
        assertThat(gate.acquire(0)).isZero();
        assertThat(gate.isPaused()).isFalse();

        gate.pause();
        assertThat(gate.isPaused()).isTrue();
        assertThat(gate.acquire(10)).isZero();

        gate.probe(3);
        assertThat(gate.isPaused()).isFalse();
        assertThat(gate.acquire(2)).isEqualTo(2);
        assertThat(gate.acquire(10)).isEqualTo(1);
        assertThat(gate.acquire(10)).isZero();
        gate.release(2);
        assertThat(gate.acquire(10)).isEqualTo(2);

        gate.open();
        gate.release(5);
        assertThat(gate.acquire(7)).isEqualTo(7);
        assertThat(changes.get()).isEqualTo(3);
        assertThatThrownBy(() -> gate.probe(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gate.addListener(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("ADR-029 the provisioning gate is always open")
    void alwaysOpenGate() {
        ConsumptionGate gate = ConsumptionGate.alwaysOpen();
        gate.addListener(() -> {
            throw new AssertionError("never notified");
        });
        gate.release(3);
        assertThat(gate.acquire(1)).isEqualTo(1);
        assertThat(gate.acquire(-1)).isZero();
        assertThat(gate.isPaused()).isFalse();
    }

    @Test
    @DisplayName("ADR-029 deployed loop values: Orders 20 s / 10 / 16 / 5 / 5-15-30-60 s, provisioning 20 s / 1 / 1 / 5 / 30-60-120-240 s; poison 10 s")
    void deployedSettings() {
        ConsumerLoopSettings orders = OrderConsumerSettings.deployed(URL).loop();
        assertThat(orders.waitTime()).isEqualTo(Duration.ofSeconds(20));
        assertThat(orders.maxMessages()).isEqualTo(10);
        assertThat(orders.concurrency()).isEqualTo(16);
        assertThat(orders.maxReceiveCount()).isEqualTo(5);
        assertThat(orders.retryBackoff()).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(15),
                Duration.ofSeconds(30), Duration.ofSeconds(60));
        assertThat(orders.poisonVisibility()).isEqualTo(Duration.ofSeconds(10));
        assertThat(orders.errorInitialWait()).isEqualTo(Duration.ofSeconds(1));
        assertThat(orders.errorMaxWait()).isEqualTo(Duration.ofSeconds(30));
        assertThat(OrderConsumerSettings.deployed(URL).processingCap()).isEqualTo(Duration.ofSeconds(30));

        ProvisioningConsumerSettings provisioning = ProvisioningConsumerSettings.deployed(URL);
        assertThat(provisioning.loop().maxMessages()).isEqualTo(1);
        assertThat(provisioning.loop().concurrency()).isEqualTo(1);
        assertThat(provisioning.loop().maxReceiveCount()).isEqualTo(5);
        assertThat(provisioning.loop().retryBackoff()).containsExactly(Duration.ofSeconds(30), Duration.ofSeconds(60),
                Duration.ofSeconds(120), Duration.ofSeconds(240));
        assertThat(provisioning.heartbeatInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(provisioning.heartbeatVisibility()).isEqualTo(Duration.ofSeconds(120));
        assertThat(provisioning.noProgressWindow()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("Loop settings reject values outside the SQS limits or inconsistent heartbeats")
    void settingsValidation() {
        List<Duration> backoff = List.of(Duration.ofSeconds(1));
        Duration one = Duration.ofSeconds(1);
        assertThatThrownBy(() -> loop(" ", one, 1, 1, 1, backoff, 0.2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loop(null, one, 1, 1, 1, backoff, 0.2)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> loop(URL, Duration.ofSeconds(21), 1, 1, 1, backoff, 0.2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loop(URL, Duration.ofSeconds(-1), 1, 1, 1, backoff, 0.2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loop(URL, one, 11, 1, 1, backoff, 0.2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loop(URL, one, 0, 1, 1, backoff, 0.2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loop(URL, one, 1, 0, 1, backoff, 0.2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loop(URL, one, 1, 1, 0, backoff, 0.2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loop(URL, one, 1, 1, 1, List.of(), 0.2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loop(URL, one, 1, 1, 1, List.of(Duration.ofSeconds(-1)), 0.2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loop(URL, one, 1, 1, 1, backoff, 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loop(URL, one, 1, 1, 1, backoff, -0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConsumerLoopSettings(URL, one, 1, 1, 1, backoff, 0.2, Duration.ZERO, one, one))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConsumerLoopSettings(URL, one, 1, 1, 1, backoff, 0.2, one, null, one))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderConsumerSettings(null, one)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderConsumerSettings(loop(URL, one, 1, 1, 1, backoff, 0.2), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        ConsumerLoopSettings valid = loop(URL, one, 1, 1, 1, backoff, 0.2);
        assertThatThrownBy(() -> new ProvisioningConsumerSettings(valid, Duration.ofSeconds(120), Duration.ofSeconds(120), one))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProvisioningConsumerSettings(null, one, Duration.ofSeconds(2), one))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("ERR-005 the delivery takes ApproximateReceiveCount; the 5th reception is the last one; a missing count is the first")
    void delivery() {
        MessageActions actions = actions(new FakeSqsClient(), VirtualTimeScheduler.create(), () -> 0.5, new RecordingSqsEvents());

        assertThat(actions.delivery(SqsTestMessages.order("a", 1))).isEqualTo(new Delivery(1, false));
        assertThat(actions.delivery(SqsTestMessages.order("a", 4))).isEqualTo(new Delivery(4, false));
        assertThat(actions.delivery(SqsTestMessages.order("a", 5))).isEqualTo(new Delivery(5, true));
        assertThat(actions.delivery(SqsTestMessages.order("a", 6))).isEqualTo(new Delivery(6, true));
        assertThat(actions.delivery(Message.builder().messageId("x").body("{}").build())).isEqualTo(new Delivery(1, false));
        assertThat(actions.delivery(Message.builder().messageId("x").body("{}")
                .attributesWithStrings(Map.of("ApproximateReceiveCount", "n/a")).build())).isEqualTo(new Delivery(1, false));
    }

    @Test
    @DisplayName("ALT-004 ERR-005 retry backoff by visibility 5/15/30/60 s with symmetric jitter of 20 % (IV-019)")
    void retryBackoff() {
        MessageActions centred = actions(new FakeSqsClient(), VirtualTimeScheduler.create(), () -> 0.5, new RecordingSqsEvents());
        assertThat(List.of(1, 2, 3, 4, 5, 9).stream().map(centred::backoffSeconds).toList())
                .containsExactly(5, 15, 30, 60, 60, 60);
        assertThat(centred.backoffSeconds(0)).isEqualTo(5);
        MessageActions low = actions(new FakeSqsClient(), VirtualTimeScheduler.create(), () -> 0.0, new RecordingSqsEvents());
        assertThat(low.backoffSeconds(1)).isEqualTo(4);
        assertThat(low.backoffSeconds(4)).isEqualTo(48);
        MessageActions high = actions(new FakeSqsClient(), VirtualTimeScheduler.create(), () -> 0.9999, new RecordingSqsEvents());
        assertThat(high.backoffSeconds(1)).isEqualTo(6);
        assertThat(high.backoffSeconds(4)).isEqualTo(72);
    }

    @Test
    @DisplayName("ADR-029 Delete deletes; PostponeUntil changes the visibility until the lease end; Retry applies the backoff; Poison 10 s")
    void dispositionsBecomeSqsOperations() {
        FakeSqsClient client = new FakeSqsClient();
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
        scheduler.advanceTimeTo(Instant.parse("2026-10-04T10:00:00Z"));
        MessageActions actions = actions(client, scheduler, () -> 0.5, new RecordingSqsEvents());
        Message message = SqsTestMessages.order("m-1", 2);
        Delivery delivery = actions.delivery(message);

        StepVerifier.create(actions.apply(message, delivery, MessageDisposition.delete(DispositionReason.CONFIRMED))).verifyComplete();
        StepVerifier.create(actions.apply(message, delivery, MessageDisposition.postponeUntil(
                Instant.parse("2026-10-04T10:00:44.200Z"), DispositionReason.LEASE_HELD_ELSEWHERE))).verifyComplete();
        StepVerifier.create(actions.apply(message, delivery, MessageDisposition.postponeUntil(
                Instant.parse("2026-10-04T09:00:00Z"), DispositionReason.LEASE_HELD_ELSEWHERE))).verifyComplete();
        StepVerifier.create(actions.apply(message, delivery, MessageDisposition.postponeUntil(
                Instant.parse("2026-10-06T10:00:00Z"), DispositionReason.LEASE_HELD_ELSEWHERE))).verifyComplete();
        StepVerifier.create(actions.apply(message, delivery, MessageDisposition.retry(DispositionReason.TRANSIENT_FAILURE)))
                .verifyComplete();
        StepVerifier.create(actions.apply(message, delivery, MessageDisposition.poison(DispositionReason.ENTITY_NOT_FOUND)))
                .verifyComplete();

        assertThat(client.deletes()).singleElement()
                .satisfies(request -> assertThat(request.receiptHandle()).isEqualTo("receipt-m-1"));
        assertThat(client.visibilityChanges().stream().map(request -> request.visibilityTimeout()).toList())
                .containsExactly(45, 0, MessageActions.MAX_VISIBILITY_SECONDS, 15, 10);
        assertThat(client.visibilityChanges()).allSatisfy(request -> {
            assertThat(request.queueUrl()).isEqualTo(URL);
            assertThat(request.receiptHandle()).isEqualTo("receipt-m-1");
        });
    }

    @Test
    @DisplayName("TC-010 a failed delete or visibility change is reported and ignored: SQS redelivers the message")
    void failedActionsAreReported() {
        FakeSqsClient client = new FakeSqsClient();
        client.onDelete(request -> CompletableFuture.failedFuture(SdkClientException.create("down")));
        client.onChangeVisibility(request -> CompletableFuture.failedFuture(SdkClientException.create("down")));
        RecordingSqsEvents events = new RecordingSqsEvents();
        MessageActions actions = actions(client, VirtualTimeScheduler.create(), () -> 0.5, events);
        Message message = SqsTestMessages.order("m-2", 1);

        StepVerifier.create(actions.apply(message, actions.delivery(message), MessageDisposition.delete(DispositionReason.REJECTED)))
                .verifyComplete();
        StepVerifier.create(actions.changeVisibility(message, -5)).verifyComplete();

        assertThat(events.recorded()).containsExactly("actionFailed:orders:m-2", "actionFailed:orders:m-2");
        assertThat(client.visibilityChanges().getFirst().visibilityTimeout()).isZero();
    }

    @Test
    @DisplayName("Only message attributes with a string value are passed to the decoder")
    void attributes() {
        Message message = Message.builder().messageAttributes(Map.of(
                "text", software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder().dataType("String")
                        .stringValue("v").build(),
                "binary", software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder().dataType("Binary")
                        .binaryValue(software.amazon.awssdk.core.SdkBytes.fromUtf8String("b")).build())).build();
        assertThat(MessageActions.attributes(message)).containsExactly(Map.entry("text", "v"));
    }

    private static MessageActions actions(FakeSqsClient client, VirtualTimeScheduler scheduler,
            java.util.function.DoubleSupplier random, RecordingSqsEvents events) {
        return new MessageActions("orders", client, ConsumerLoopSettings.orders(URL), scheduler, random, events);
    }

    private static ConsumerLoopSettings loop(String url, Duration wait, int messages, int concurrency, int receives,
            List<Duration> backoff, double jitter) {
        Duration one = Duration.ofSeconds(1);
        return new ConsumerLoopSettings(url, wait, messages, concurrency, receives, backoff, jitter, one, one, one);
    }
}
