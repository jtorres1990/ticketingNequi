package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import com.nequi.ticketing.application.port.out.FailedItem;
import com.nequi.ticketing.application.port.out.ItemFailure;
import com.nequi.ticketing.application.port.out.TransactionOutcome;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/**
 * Reactive execution over the asynchronous client (ADR-039) for one table: transactions that request the
 * item that failed its condition and return the reason per item, with the bounded retry of the whole
 * transaction on a transactional conflict (ADR-023, ADR-035); single conditional writes; strongly or
 * eventually consistent reads; paginated queries; batch writes and consistent batch reads with retry of
 * unprocessed items. SDK exceptions never leave this class: they become {@link DynamoDbStoreException}.
 */
final class DynamoDbTable {

    static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";
    static final String TRANSACTION_CONFLICT = "TransactionConflict";
    static final String NONE = "None";

    private final DynamoDbAsyncClient client;
    private final DynamoDbAdapterSettings settings;

    DynamoDbTable(DynamoDbAsyncClient client, DynamoDbAdapterSettings settings) {
        this.client = Objects.requireNonNull(client, "client");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    String name() {
        return settings.tableName();
    }

    DynamoDbAdapterSettings settings() {
        return settings;
    }

    // ------------------------------------------------------------------ transactions

    /** Role of one transaction item, used to translate its cancellation reason (ADR-039). */
    record ItemRole(FailedItem item, String eventId, String ticketId) {

        static ItemRole of(FailedItem item) {
            return new ItemRole(item, null, null);
        }

        static ItemRole ticket(String eventId, String ticketId) {
            return new ItemRole(FailedItem.TICKET_STATE, eventId, ticketId);
        }

        /** A Ticket whose old image is absent, or of another Event, is missing; otherwise its state failed. */
        ItemFailure failure(CancellationReason reason) {
            if (ticketId == null) {
                return ItemFailure.of(item);
            }
            Map<String, AttributeValue> old = reason.hasItem() ? reason.item() : Map.of();
            AttributeValue storedEvent = old.get(TicketItems.EVENT_ID);
            boolean missing = old.isEmpty() || storedEvent == null || !eventId.equals(storedEvent.s());
            return ItemFailure.ticket(missing ? FailedItem.TICKET_MISSING : FailedItem.TICKET_STATE, ticketId);
        }
    }

    record TxItem(TransactWriteItem item, ItemRole role) {
    }

    TxItem put(Map<String, AttributeValue> item, Expression condition, ItemRole role) {
        return new TxItem(TransactWriteItem.builder().put(Put.builder()
                .tableName(name())
                .item(item)
                .conditionExpression(condition.conditionExpression())
                .expressionAttributeNames(condition.names())
                .expressionAttributeValues(condition.values())
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                .build()).build(), role);
    }

    /** Insert that requires the item not to exist (audit, idempotency, new Order, new Event, lock). */
    TxItem insert(Map<String, AttributeValue> item, ItemRole role) {
        Expression condition = new Expression();
        condition.condition(condition.notExists(Keys.PK));
        return put(item, condition, role);
    }

    TxItem update(Map<String, AttributeValue> key, Expression expression, ItemRole role) {
        return new TxItem(TransactWriteItem.builder().update(Update.builder()
                .tableName(name())
                .key(key)
                .updateExpression(expression.updateExpression())
                .conditionExpression(expression.conditionExpression())
                .expressionAttributeNames(expression.names())
                .expressionAttributeValues(expression.values())
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                .build()).build(), role);
    }

    TxItem delete(Map<String, AttributeValue> key, Expression condition, ItemRole role) {
        return new TxItem(TransactWriteItem.builder().delete(Delete.builder()
                .tableName(name())
                .key(key)
                .conditionExpression(condition.conditionExpression())
                .expressionAttributeNames(condition.names())
                .expressionAttributeValues(condition.values())
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                .build()).build(), role);
    }

    TxItem conditionCheck(Map<String, AttributeValue> key, Expression condition, ItemRole role) {
        return new TxItem(TransactWriteItem.builder().conditionCheck(ConditionCheck.builder()
                .tableName(name())
                .key(key)
                .conditionExpression(condition.conditionExpression())
                .expressionAttributeNames(condition.names())
                .expressionAttributeValues(condition.values())
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                .build()).build(), role);
    }

    /**
     * Executes the items atomically. Failed conditions give {@link TransactionOutcome.Cancelled} with every
     * failed item; a transactional conflict retries the whole transaction up to the configured maximum
     * with exponential backoff and jitter, then gives {@link TransactionOutcome.Conflict}.
     */
    Mono<TransactionOutcome> transact(String operation, List<TxItem> items) {
        List<TransactWriteItem> requestItems = items.stream().map(TxItem::item).toList();
        return attempt(operation, items, requestItems, 0);
    }

    private Mono<TransactionOutcome> attempt(String operation, List<TxItem> items, List<TransactWriteItem> requestItems,
            int retry) {
        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder().transactItems(requestItems).build();
        return Mono.fromFuture(() -> client.transactWriteItems(request))
                .map(ignored -> TransactionOutcome.applied())
                .onErrorResume(error -> {
                    Throwable cause = unwrap(error);
                    if (!(cause instanceof TransactionCanceledException cancelled)) {
                        return Mono.error(new DynamoDbStoreException(operation, cause));
                    }
                    List<CancellationReason> reasons = cancelled.hasCancellationReasons()
                            ? cancelled.cancellationReasons() : List.of();
                    List<ItemFailure> failures = new ArrayList<>();
                    boolean conflict = false;
                    for (int index = 0; index < reasons.size() && index < items.size(); index++) {
                        String code = reasons.get(index).code();
                        if (CONDITIONAL_CHECK_FAILED.equals(code)) {
                            failures.add(items.get(index).role().failure(reasons.get(index)));
                        } else if (TRANSACTION_CONFLICT.equals(code)) {
                            conflict = true;
                        } else if (code != null && !NONE.equals(code)) {
                            return Mono.error(new DynamoDbStoreException(operation + " cancelled: " + code, cancelled));
                        }
                    }
                    if (!failures.isEmpty()) {
                        return Mono.just(TransactionOutcome.cancelled(failures));
                    }
                    if (!conflict) {
                        return Mono.error(new DynamoDbStoreException(operation + " cancelled without reason", cancelled));
                    }
                    if (retry >= settings.transactionConflictRetries()) {
                        return Mono.just(TransactionOutcome.conflict());
                    }
                    return Mono.delay(conflictBackoff(retry))
                            .then(Mono.defer(() -> attempt(operation, items, requestItems, retry + 1)));
                });
    }

    Duration conflictBackoff(int retry) {
        return jittered(settings.conflictBackoffBase(), settings.conflictBackoffMaximum(), retry, settings.conflictJitter());
    }

    // ------------------------------------------------------------------ single item operations

    /** Single conditional update: {@code true} when applied, {@code false} when its condition failed. */
    Mono<Boolean> conditionalUpdate(String operation, Map<String, AttributeValue> key, Expression expression) {
        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(name())
                .key(key)
                .updateExpression(expression.updateExpression())
                .conditionExpression(expression.conditionExpression())
                .expressionAttributeNames(expression.names())
                .expressionAttributeValues(expression.values())
                .build();
        return Mono.fromFuture(() -> client.updateItem(request))
                .map(ignored -> Boolean.TRUE)
                .onErrorResume(error -> unwrap(error) instanceof ConditionalCheckFailedException
                        ? Mono.just(Boolean.FALSE)
                        : Mono.error(new DynamoDbStoreException(operation, unwrap(error))));
    }

    /** Item by key; empty when it does not exist. */
    Mono<Map<String, AttributeValue>> get(String operation, Map<String, AttributeValue> key, boolean consistent) {
        GetItemRequest request = GetItemRequest.builder().tableName(name()).key(key).consistentRead(consistent).build();
        return call(operation, () -> client.getItem(request))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> response.item());
    }

    // ------------------------------------------------------------------ queries

    /** Every item of a query, following {@code LastEvaluatedKey}. */
    Flux<Map<String, AttributeValue>> queryAll(String operation, QueryRequest request) {
        return query(operation, request)
                .expand(response -> hasMore(response)
                        ? query(operation, request.toBuilder().exclusiveStartKey(response.lastEvaluatedKey()).build())
                        : Mono.empty())
                .concatMapIterable(QueryResponse::items);
    }

    /** Sum of {@code Count} over every page of a count-only query (pagination by read size, SPK-012). */
    Mono<Long> countAll(String operation, QueryRequest request) {
        return query(operation, request)
                .expand(response -> hasMore(response)
                        ? query(operation, request.toBuilder().exclusiveStartKey(response.lastEvaluatedKey()).build())
                        : Mono.empty())
                .map(response -> (long) response.count())
                .reduce(0L, Long::sum);
    }

    /** Items read so far and whether the key range is exhausted after them. */
    record Collected(List<Map<String, AttributeValue>> items, boolean exhausted) {
    }

    /** Up to {@code limit} items of a query starting after {@code startKey}, across pages if needed. */
    Mono<Collected> queryUpTo(String operation, QueryRequest request, int limit, Map<String, AttributeValue> startKey) {
        return step(operation, request, limit, startKey, new ArrayList<>());
    }

    private Mono<Collected> step(String operation, QueryRequest request, int limit, Map<String, AttributeValue> startKey,
            List<Map<String, AttributeValue>> collected) {
        QueryRequest page = request.toBuilder().limit(limit - collected.size()).exclusiveStartKey(startKey).build();
        return query(operation, page).flatMap(response -> {
            collected.addAll(response.items());
            if (!hasMore(response)) {
                return Mono.just(new Collected(collected, true));
            }
            if (collected.size() >= limit) {
                return Mono.just(new Collected(collected, false));
            }
            return step(operation, request, limit, response.lastEvaluatedKey(), collected);
        });
    }

    private Mono<QueryResponse> query(String operation, QueryRequest request) {
        return call(operation, () -> client.query(request));
    }

    private static boolean hasMore(QueryResponse response) {
        return response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty();
    }

    // ------------------------------------------------------------------ batches

    /** Batch write in requests of the configured size, bounded in parallel, retrying unprocessed items. */
    Mono<Void> batchWrite(String operation, List<WriteRequest> writes) {
        return Flux.fromIterable(partition(writes, settings.batchWriteSize()))
                .flatMap(chunk -> writeChunk(operation, chunk, 0), settings.batchWriteParallelism())
                .then();
    }

    private Mono<Void> writeChunk(String operation, List<WriteRequest> chunk, int retry) {
        BatchWriteItemRequest request = BatchWriteItemRequest.builder().requestItems(Map.of(name(), chunk)).build();
        return call(operation, () -> client.batchWriteItem(request))
                .flatMap(response -> {
                    List<WriteRequest> unprocessed = unprocessed(response);
                    if (unprocessed.isEmpty()) {
                        return Mono.<Void>empty();
                    }
                    if (retry >= settings.unprocessedRetries()) {
                        return Mono.error(new DynamoDbStoreException(operation + ": unprocessed items persisted"));
                    }
                    return Mono.delay(unprocessedBackoff(retry))
                            .then(Mono.defer(() -> writeChunk(operation, unprocessed, retry + 1)));
                });
    }

    private List<WriteRequest> unprocessed(BatchWriteItemResponse response) {
        return response.hasUnprocessedItems() ? response.unprocessedItems().getOrDefault(name(), List.of()) : List.of();
    }

    /** Strongly consistent batch read in requests of the configured size, retrying unprocessed keys. */
    Mono<List<Map<String, AttributeValue>>> batchGet(String operation, List<Map<String, AttributeValue>> keys,
            String projection, Map<String, String> names) {
        return Flux.fromIterable(partition(keys, settings.batchReadSize()))
                .flatMap(chunk -> getChunk(operation, chunk, projection, names, 0), settings.batchReadParallelism())
                .concatMapIterable(items -> items)
                .collectList();
    }

    private Mono<List<Map<String, AttributeValue>>> getChunk(String operation, List<Map<String, AttributeValue>> keys,
            String projection, Map<String, String> names, int retry) {
        KeysAndAttributes request = KeysAndAttributes.builder()
                .keys(keys)
                .consistentRead(true)
                .projectionExpression(projection)
                .expressionAttributeNames(names)
                .build();
        BatchGetItemRequest batch = BatchGetItemRequest.builder().requestItems(Map.of(name(), request)).build();
        return call(operation, () -> client.batchGetItem(batch))
                .flatMap(response -> {
                    List<Map<String, AttributeValue>> found = response.hasResponses()
                            ? response.responses().getOrDefault(name(), List.of()) : List.of();
                    List<Map<String, AttributeValue>> pending = unprocessedKeys(response);
                    if (pending.isEmpty()) {
                        return Mono.just(found);
                    }
                    if (retry >= settings.unprocessedRetries()) {
                        return Mono.error(new DynamoDbStoreException(operation + ": unprocessed keys persisted"));
                    }
                    return Mono.delay(unprocessedBackoff(retry))
                            .then(Mono.defer(() -> getChunk(operation, pending, projection, names, retry + 1)))
                            .map(more -> {
                                List<Map<String, AttributeValue>> all = new ArrayList<>(found);
                                all.addAll(more);
                                return all;
                            });
                });
    }

    private List<Map<String, AttributeValue>> unprocessedKeys(BatchGetItemResponse response) {
        if (!response.hasUnprocessedKeys()) {
            return List.of();
        }
        KeysAndAttributes pending = response.unprocessedKeys().get(name());
        return pending == null || !pending.hasKeys() ? List.of() : pending.keys();
    }

    Duration unprocessedBackoff(int retry) {
        return jittered(settings.unprocessedBackoffBase(), settings.unprocessedBackoffMaximum(), retry, 0.5);
    }

    // ------------------------------------------------------------------ support

    private <T> Mono<T> call(String operation, Supplier<CompletableFuture<T>> invocation) {
        return Mono.fromFuture(invocation).onErrorMap(error -> new DynamoDbStoreException(operation, unwrap(error)));
    }

    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static <T> List<List<T>> partition(List<T> values, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int start = 0; start < values.size(); start += size) {
            chunks.add(List.copyOf(values.subList(start, Math.min(start + size, values.size()))));
        }
        return chunks;
    }

    /** Exponential backoff {@code base * 2^retry} capped at {@code maximum}, reduced by up to {@code jitter}. */
    static Duration jittered(Duration base, Duration maximum, int retry, double jitter) {
        long exponential = base.toNanos() << Math.min(retry, 20);
        long capped = Math.min(maximum.toNanos(), exponential);
        double factor = 1.0 - jitter * ThreadLocalRandom.current().nextDouble();
        return Duration.ofNanos((long) (capped * factor));
    }
}
