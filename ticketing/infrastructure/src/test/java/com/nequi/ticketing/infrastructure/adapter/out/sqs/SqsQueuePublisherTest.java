package com.nequi.ticketing.infrastructure.adapter.out.sqs;

import static com.nequi.ticketing.infrastructure.adapter.sqs.SqsTestMessages.EVENT_ID;
import static com.nequi.ticketing.infrastructure.adapter.sqs.SqsTestMessages.ORDER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.application.port.out.EventProvisioningRequested;
import com.nequi.ticketing.application.port.out.OrderProcessingRequested;
import com.nequi.ticketing.application.port.out.PublishResult;
import com.nequi.ticketing.application.port.out.PublisherAvailability;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitBreakerSettings;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitState;
import com.nequi.ticketing.infrastructure.adapter.sqs.FakeSqsClient;
import com.nequi.ticketing.infrastructure.adapter.sqs.RecordingSqsEvents;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsEvents;
import com.nequi.ticketing.infrastructure.adapter.sqs.TraceContext;
import com.nequi.ticketing.infrastructure.adapter.sqs.VirtualClock;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

class SqsQueuePublisherTest {

    private static final String ORDERS_URL = "http://sqs.local/000000000000/ticketing-orders";
    private static final String PROVISIONING_URL = "http://sqs.local/000000000000/ticketing-event-provisioning";

    private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
    private final FakeSqsClient client = new FakeSqsClient();
    private final RecordingSqsEvents events = new RecordingSqsEvents();
    private SqsQueuePublisher publisher = publisher(SqsPublisherSettings.deployed(ORDERS_URL, PROVISIONING_URL));

    @AfterEach
    void disposeScheduler() {
        scheduler.dispose();
    }

    @Test
    @DisplayName("MSG-001 FR-005 is sent to the Orders queue with its body and attributes, including the trace context")
    void publishesOrderProcessingRequested() {
        PublishResult result = run(publisher.publish(orderMessage())
                .contextWrite(context -> context.put(TraceContext.KEY, "00-trace-span-01")));

        assertThat(result).isEqualTo(PublishResult.PUBLISHED);
        SendMessageRequest request = client.sends().getFirst();
        assertThat(request.queueUrl()).isEqualTo(ORDERS_URL);
        assertThat(request.messageBody()).contains("\"orderId\":\"" + ORDER_ID + "\"")
                .contains("\"messageType\":\"OrderProcessingRequested\"").contains("\"schemaVersion\":1");
        assertThat(request.messageAttributes().get("messageType").stringValue()).isEqualTo("OrderProcessingRequested");
        assertThat(request.messageAttributes().get("schemaVersion").dataType()).isEqualTo("Number");
        assertThat(request.messageAttributes().get("publisher").stringValue()).isEqualTo("api");
        assertThat(request.messageAttributes().get("traceparent").stringValue()).isEqualTo("00-trace-span-01");
        assertThat(events.recorded()).isEmpty();
    }

    @Test
    @DisplayName("MSG-002 FR-001 is sent to the provisioning queue with the correlationId attribute")
    void publishesEventProvisioningRequested() {
        PublishResult result = run(publisher.publish(new EventProvisioningRequested(EVENT_ID, "corr-2")));

        assertThat(result).isEqualTo(PublishResult.PUBLISHED);
        SendMessageRequest request = client.sends().getFirst();
        assertThat(request.queueUrl()).isEqualTo(PROVISIONING_URL);
        assertThat(request.messageAttributes().get("correlationId").stringValue()).isEqualTo("corr-2");
        assertThat(request.messageAttributes()).doesNotContainKey("traceparent");
    }

    @Test
    @DisplayName("AC-022 ADR-026 every attempt timing out at 500 ms gives 3 attempts within the 2 s budget and a FAILED result, not an error")
    void attemptsTimeOut() {
        List<CompletableFuture<SendMessageResponse>> pending = new ArrayList<>();
        client.onSend(request -> {
            CompletableFuture<SendMessageResponse> future = new CompletableFuture<>();
            pending.add(future);
            return future;
        });
        AtomicReference<PublishResult> result = new AtomicReference<>();

        publisher.publish(orderMessage()).subscribe(result::set);
        scheduler.advanceTimeBy(Duration.ofMillis(1_400));
        assertThat(result.get()).isNull();
        scheduler.advanceTimeBy(Duration.ofMillis(600));

        assertThat(result.get()).isEqualTo(PublishResult.FAILED);
        assertThat(client.sends()).hasSize(3);
        assertThat(pending).allMatch(CompletableFuture::isCancelled);
        assertThat(events.recorded()).containsExactly("publicationFailed:orders");
    }

    @Test
    @DisplayName("TC-009 a transient error is retried with backoff and the second attempt publishes")
    void transientErrorIsRetried() {
        AtomicInteger attempts = new AtomicInteger();
        client.onSend(request -> attempts.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(SdkClientException.create("connection reset"))
                : CompletableFuture.completedFuture(SendMessageResponse.builder().build()));
        AtomicReference<PublishResult> result = new AtomicReference<>();

        publisher.publish(orderMessage()).subscribe(result::set);
        scheduler.advanceTimeBy(Duration.ofMillis(49));
        assertThat(result.get()).isNull();
        scheduler.advanceTimeBy(Duration.ofMillis(200));

        assertThat(result.get()).isEqualTo(PublishResult.PUBLISHED);
        assertThat(client.sends()).hasSize(2);
    }

    @Test
    @DisplayName("ADR-026 a non-retryable error (missing queue) is definitive at once: one attempt, FAILED")
    void nonRetryableErrorIsDefinitive() {
        client.onSend(request -> CompletableFuture.failedFuture(missingQueue()));

        assertThat(run(publisher.publish(orderMessage()))).isEqualTo(PublishResult.FAILED);
        assertThat(client.sends()).hasSize(1);
        assertThat(events.causes().getFirst()).isInstanceOf(QueueDoesNotExistException.class);
    }

    @Test
    @DisplayName("ADR-026 the 2 s budget bounds the whole publication even when attempts would still be left")
    void budgetBoundsThePublication() {
        publisher = publisher(new SqsPublisherSettings(ORDERS_URL, PROVISIONING_URL, Duration.ofMillis(500), 3,
                Duration.ofMillis(700), Duration.ofMillis(100), 0.5, CircuitBreakerSettings.sqsPublication()));
        client.onSend(request -> new CompletableFuture<>());
        AtomicReference<PublishResult> result = new AtomicReference<>();

        publisher.publish(orderMessage()).subscribe(result::set);
        scheduler.advanceTimeBy(Duration.ofMillis(700));

        assertThat(result.get()).isEqualTo(PublishResult.FAILED);
        assertThat(client.sends()).hasSize(2);
        assertThat(events.causes().getFirst()).isInstanceOf(TimeoutException.class);
    }

    @Test
    @DisplayName("AC-046 ADR-038/mechanism circuit breaker: 10 failed attempts open the publication circuit; retries stop and Unavailable carries the remaining open time")
    void circuitOpensAndRejects() {
        client.onSend(request -> CompletableFuture.failedFuture(serverError()));

        for (int publication = 0; publication < 4; publication++) {
            assertThat(run(publisher.publish(orderMessage()))).isEqualTo(PublishResult.FAILED);
        }

        assertThat(client.sends()).hasSize(10);
        assertThat(publisher.circuit().state()).isEqualTo(CircuitState.OPEN);
        Duration remaining = retryAfter();
        assertThat(remaining).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(10));
        assertThat(events.causes().getLast()).isInstanceOf(CallNotPermittedException.class);

        scheduler.advanceTimeBy(Duration.ofSeconds(1));
        assertThat(retryAfter()).isEqualTo(remaining.minusSeconds(1));
        AtomicReference<PublishResult> rejected = new AtomicReference<>();
        publisher.publish(new EventProvisioningRequested(EVENT_ID, "c")).subscribe(rejected::set);
        assertThat(rejected.get()).isEqualTo(PublishResult.FAILED);
        assertThat(client.sends()).hasSize(10);
    }

    @Test
    @DisplayName("ADR-038/mechanism circuit breaker: after 10 s the circuit is available, 2 successful probes close it and publication resumes")
    void circuitRecovers() {
        openCircuit();
        scheduler.advanceTimeBy(Duration.ofSeconds(10));
        assertThat(publisher.availability()).isEqualTo(PublisherAvailability.available());
        assertThat(publisher.circuit().state()).isEqualTo(CircuitState.OPEN);
        client.onSend(request -> CompletableFuture.completedFuture(SendMessageResponse.builder().build()));

        assertThat(run(publisher.publish(orderMessage()))).isEqualTo(PublishResult.PUBLISHED);
        assertThat(publisher.circuit().state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(publisher.availability()).isEqualTo(PublisherAvailability.available());
        assertThat(run(publisher.publish(orderMessage()))).isEqualTo(PublishResult.PUBLISHED);
        assertThat(publisher.circuit().state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("ADR-038/mechanism circuit breaker: a failed probe in half-open reopens the circuit")
    void failedProbeReopens() {
        openCircuit();
        scheduler.advanceTimeBy(Duration.ofSeconds(10));

        assertThat(run(publisher.publish(orderMessage()))).isEqualTo(PublishResult.FAILED);

        assertThat(publisher.circuit().state()).isEqualTo(CircuitState.OPEN);
        assertThat(publisher.availability()).isInstanceOf(PublisherAvailability.Unavailable.class);
    }

    @Test
    @DisplayName("ADR-035 non-retryable errors never open the publication circuit")
    void nonRetryableErrorsDoNotOpenTheCircuit() {
        client.onSend(request -> CompletableFuture.failedFuture(missingQueue()));

        for (int publication = 0; publication < 20; publication++) {
            run(publisher.publish(orderMessage()));
        }

        assertThat(client.sends()).hasSize(20);
        assertThat(publisher.circuit().state()).isEqualTo(CircuitState.CLOSED);
        assertThat(publisher.availability()).isEqualTo(PublisherAvailability.available());
    }

    @Test
    @DisplayName("ADR-026 ADR-035 the deployed publication values are 500 ms, 3 attempts, 2 s, 100 ms x2 with 50 % jitter and the 20/10/50 %/10 s/2 circuit")
    void settings() {
        assertThat(SqsPublisherSettings.deployed(ORDERS_URL, PROVISIONING_URL)).isEqualTo(new SqsPublisherSettings(
                ORDERS_URL, PROVISIONING_URL, Duration.ofMillis(500), 3, Duration.ofSeconds(2), Duration.ofMillis(100),
                0.5, CircuitBreakerSettings.sqsPublication()));
        CircuitBreakerSettings circuit = CircuitBreakerSettings.sqsPublication();
        assertThatThrownBy(() -> new SqsPublisherSettings(" ", PROVISIONING_URL, Duration.ofMillis(1), 1,
                Duration.ofSeconds(1), Duration.ofMillis(1), 0.5, circuit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SqsPublisherSettings(ORDERS_URL, null, Duration.ofMillis(1), 1,
                Duration.ofSeconds(1), Duration.ofMillis(1), 0.5, circuit)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SqsPublisherSettings(ORDERS_URL, PROVISIONING_URL, Duration.ZERO, 1,
                Duration.ofSeconds(1), Duration.ofMillis(1), 0.5, circuit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SqsPublisherSettings(ORDERS_URL, PROVISIONING_URL, Duration.ofMillis(1), 0,
                Duration.ofSeconds(1), Duration.ofMillis(1), 0.5, circuit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SqsPublisherSettings(ORDERS_URL, PROVISIONING_URL, Duration.ofMillis(1), 1,
                Duration.ofSeconds(1), Duration.ofMillis(1), 1.5, circuit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SqsPublisherSettings(ORDERS_URL, PROVISIONING_URL, Duration.ofMillis(1), 1,
                Duration.ofSeconds(1), Duration.ofMillis(1), -0.1, circuit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SqsPublisherSettings(ORDERS_URL, PROVISIONING_URL, Duration.ofMillis(1), 1,
                Duration.ofSeconds(1), Duration.ofMillis(1), 0.5, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SqsQueuePublisher.create(null, SqsPublisherSettings.deployed(ORDERS_URL, PROVISIONING_URL),
                Instant::now, SqsEvents.NONE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> publisher.publish((OrderProcessingRequested) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> publisher.publish((EventProvisioningRequested) null)).isInstanceOf(NullPointerException.class);
        assertThat(SqsQueuePublisher.create(client, SqsPublisherSettings.deployed(ORDERS_URL, PROVISIONING_URL),
                Instant::now, SqsEvents.NONE).availability()).isEqualTo(PublisherAvailability.available());
    }

    private Duration retryAfter() {
        assertThat(publisher.availability()).isInstanceOf(PublisherAvailability.Unavailable.class);
        return ((PublisherAvailability.Unavailable) publisher.availability()).retryAfter();
    }

    private void openCircuit() {
        client.onSend(request -> CompletableFuture.failedFuture(serverError()));
        for (int publication = 0; publication < 4; publication++) {
            run(publisher.publish(orderMessage()));
        }
        assertThat(publisher.circuit().state()).isEqualTo(CircuitState.OPEN);
    }

    private SqsQueuePublisher publisher(SqsPublisherSettings settings) {
        return SqsQueuePublisher.create(client, settings, new VirtualClock(scheduler), events, scheduler);
    }

    private PublishResult run(Mono<PublishResult> publication) {
        AtomicReference<PublishResult> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        publication.subscribe(result::set, error::set);
        scheduler.advanceTimeBy(Duration.ofSeconds(3));
        assertThat(error.get()).as("the publication never signals an error").isNull();
        return result.get();
    }

    private static OrderProcessingRequested orderMessage() {
        return new OrderProcessingRequested(ORDER_ID, EVENT_ID, Instant.parse("2026-10-04T10:00:00Z"), "corr-1",
                OrderProcessingRequested.Publisher.API);
    }

    private static QueueDoesNotExistException missingQueue() {
        return (QueueDoesNotExistException) QueueDoesNotExistException.builder().statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AWS.SimpleQueueService.NonExistentQueue").build())
                .build();
    }

    private static SqsException serverError() {
        return (SqsException) SqsException.builder().statusCode(500)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("InternalError").build()).build();
    }
}
