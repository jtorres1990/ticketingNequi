package com.nequi.ticketing.infrastructure.adapter.sqs;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/**
 * Hand-written double of the asynchronous SQS client for unit tests: receptions stay pending until the test
 * completes them (like a long poll), and every send, delete and visibility change is recorded. Sends,
 * deletes and visibility changes answer with a scripted function (success by default). Thread-safe.
 */
public final class FakeSqsClient implements SqsAsyncClient {

    private final ConcurrentLinkedDeque<PendingReceive> receives = new ConcurrentLinkedDeque<>();
    private final List<SendMessageRequest> sends = new CopyOnWriteArrayList<>();
    private final List<DeleteMessageRequest> deletes = new CopyOnWriteArrayList<>();
    private final List<ChangeMessageVisibilityRequest> visibilityChanges = new CopyOnWriteArrayList<>();
    private volatile Function<SendMessageRequest, CompletableFuture<SendMessageResponse>> sendBehaviour =
            request -> CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("m").build());
    private volatile Function<DeleteMessageRequest, CompletableFuture<DeleteMessageResponse>> deleteBehaviour =
            request -> CompletableFuture.completedFuture(DeleteMessageResponse.builder().build());
    private volatile Function<ChangeMessageVisibilityRequest, CompletableFuture<ChangeMessageVisibilityResponse>>
            visibilityBehaviour = request -> CompletableFuture.completedFuture(ChangeMessageVisibilityResponse.builder().build());

    @Override
    public String serviceName() {
        return "sqs";
    }

    @Override
    public void close() {
        // nothing to release
    }

    @Override
    public CompletableFuture<ReceiveMessageResponse> receiveMessage(ReceiveMessageRequest request) {
        PendingReceive pending = new PendingReceive(request, new CompletableFuture<>());
        receives.add(pending);
        return pending.future();
    }

    @Override
    public CompletableFuture<SendMessageResponse> sendMessage(SendMessageRequest request) {
        sends.add(request);
        return sendBehaviour.apply(request);
    }

    @Override
    public CompletableFuture<DeleteMessageResponse> deleteMessage(DeleteMessageRequest request) {
        deletes.add(request);
        return deleteBehaviour.apply(request);
    }

    @Override
    public CompletableFuture<ChangeMessageVisibilityResponse> changeMessageVisibility(ChangeMessageVisibilityRequest request) {
        visibilityChanges.add(request);
        return visibilityBehaviour.apply(request);
    }

    public void onSend(Function<SendMessageRequest, CompletableFuture<SendMessageResponse>> behaviour) {
        this.sendBehaviour = behaviour;
    }

    public void onDelete(Function<DeleteMessageRequest, CompletableFuture<DeleteMessageResponse>> behaviour) {
        this.deleteBehaviour = behaviour;
    }

    public void onChangeVisibility(
            Function<ChangeMessageVisibilityRequest, CompletableFuture<ChangeMessageVisibilityResponse>> behaviour) {
        this.visibilityBehaviour = behaviour;
    }

    /** All receptions requested so far, oldest first. */
    public List<PendingReceive> receives() {
        return new ArrayList<>(receives);
    }

    /** The most recent reception that is still pending, or {@code null}. */
    public PendingReceive pendingReceive() {
        return receives.stream().filter(pending -> !pending.future().isDone()).reduce((first, second) -> second)
                .orElse(null);
    }

    public long pendingReceives() {
        return receives.stream().filter(pending -> !pending.future().isDone()).count();
    }

    public List<SendMessageRequest> sends() {
        return List.copyOf(sends);
    }

    public List<DeleteMessageRequest> deletes() {
        return List.copyOf(deletes);
    }

    public List<ChangeMessageVisibilityRequest> visibilityChanges() {
        return List.copyOf(visibilityChanges);
    }

    /** A reception waiting for the test. */
    public record PendingReceive(ReceiveMessageRequest request, CompletableFuture<ReceiveMessageResponse> future) {

        public void complete(Message... messages) {
            future.complete(ReceiveMessageResponse.builder().messages(messages).build());
        }

        public void fail(Throwable error) {
            future.completeExceptionally(error);
        }
    }
}
