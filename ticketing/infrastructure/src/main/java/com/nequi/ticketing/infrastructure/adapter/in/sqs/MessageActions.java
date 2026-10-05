package com.nequi.ticketing.infrastructure.adapter.in.sqs;

import com.nequi.ticketing.application.port.in.Delivery;
import com.nequi.ticketing.application.port.in.MessageDisposition;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsEvents;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/**
 * Translation of the typed result of the use cases into SQS operations (ADR-029, INC-004
 * {@link MessageDisposition}): {@code Delete} deletes; {@code PostponeUntil} changes the visibility until
 * that instant; {@code Retry} applies the visibility backoff of the reception with jitter; {@code Poison}
 * applies the short visibility. A failed operation is reported and ignored: SQS redelivers the message
 * when its visibility ends and the consumer is idempotent (ADR-027).
 */
final class MessageActions {

    /** SQS maximum visibility timeout (12 hours). */
    static final int MAX_VISIBILITY_SECONDS = 43_200;

    private final String queue;
    private final SqsAsyncClient client;
    private final ConsumerLoopSettings settings;
    private final Scheduler scheduler;
    private final DoubleSupplier random;
    private final SqsEvents events;

    MessageActions(String queue, SqsAsyncClient client, ConsumerLoopSettings settings, Scheduler scheduler,
            DoubleSupplier random, SqsEvents events) {
        this.queue = queue;
        this.client = client;
        this.settings = settings;
        this.scheduler = scheduler;
        this.random = random;
        this.events = events;
    }

    /** {@code ApproximateReceiveCount} and whether it reaches {@code maxReceiveCount} (last reception). */
    Delivery delivery(Message message) {
        int receiveCount = 1;
        String count = message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
        if (count != null) {
            try {
                receiveCount = Math.max(1, Integer.parseInt(count));
            } catch (NumberFormatException unreadable) {
                receiveCount = 1;
            }
        }
        return new Delivery(receiveCount, receiveCount >= settings.maxReceiveCount());
    }

    /** String values of the message attributes. */
    static Map<String, String> attributes(Message message) {
        Map<String, String> values = new HashMap<>();
        message.messageAttributes().forEach((name, value) -> {
            if (value.stringValue() != null) {
                values.put(name, value.stringValue());
            }
        });
        return values;
    }

    Mono<Void> apply(Message message, Delivery delivery, MessageDisposition disposition) {
        return switch (disposition) {
            case MessageDisposition.Delete delete -> delete(message);
            case MessageDisposition.PostponeUntil postpone -> changeVisibility(message, secondsUntil(postpone.until()));
            case MessageDisposition.Retry retry -> changeVisibility(message, backoffSeconds(delivery.receiveCount()));
            case MessageDisposition.Poison poison -> changeVisibility(message, seconds(settings.poisonVisibility()));
        };
    }

    Mono<Void> changeVisibility(Message message, int seconds) {
        ChangeMessageVisibilityRequest request = ChangeMessageVisibilityRequest.builder()
                .queueUrl(settings.queueUrl())
                .receiptHandle(message.receiptHandle())
                .visibilityTimeout(clamp(seconds))
                .build();
        return Mono.fromFuture(() -> client.changeMessageVisibility(request))
                .then()
                .onErrorResume(error -> {
                    events.messageActionFailed(queue, message.messageId(), error);
                    return Mono.empty();
                });
    }

    /** Visibility backoff after reception {@code receiveCount}: schedule entry with symmetric jitter. */
    int backoffSeconds(int receiveCount) {
        int index = Math.min(Math.max(receiveCount, 1), settings.retryBackoff().size()) - 1;
        double base = settings.retryBackoff().get(index).toMillis();
        double factor = 1 + settings.retryJitter() * (2 * random.getAsDouble() - 1);
        return (int) Math.round(base * factor / 1000.0);
    }

    private Mono<Void> delete(Message message) {
        DeleteMessageRequest request = DeleteMessageRequest.builder()
                .queueUrl(settings.queueUrl())
                .receiptHandle(message.receiptHandle())
                .build();
        return Mono.fromFuture(() -> client.deleteMessage(request))
                .then()
                .onErrorResume(error -> {
                    events.messageActionFailed(queue, message.messageId(), error);
                    return Mono.empty();
                });
    }

    private int secondsUntil(Instant until) {
        long remainingMillis = until.toEpochMilli() - scheduler.now(TimeUnit.MILLISECONDS);
        return remainingMillis <= 0 ? 0 : (int) Math.min(MAX_VISIBILITY_SECONDS, (remainingMillis + 999) / 1000);
    }

    private static int seconds(Duration duration) {
        return (int) Math.min(MAX_VISIBILITY_SECONDS, duration.toSeconds());
    }

    private static int clamp(int seconds) {
        return Math.max(0, Math.min(MAX_VISIBILITY_SECONDS, seconds));
    }
}
