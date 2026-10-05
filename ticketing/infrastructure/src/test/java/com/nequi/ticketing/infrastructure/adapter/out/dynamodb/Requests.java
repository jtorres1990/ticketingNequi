package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Unit-test support for the DynamoDB adapter: a mocked asynchronous client and a renderer that inlines the
 * expression placeholders, so tests assert the exact items, keys, updates and conditions sent (ADR-038).
 */
final class Requests {

    private static final Pattern PLACEHOLDER = Pattern.compile("#n\\d+|:v\\d+");

    private Requests() {
    }

    static DynamoDbAsyncClient client() {
        return mock(DynamoDbAsyncClient.class);
    }

    static DynamoDbPersistence persistence(DynamoDbAsyncClient client) {
        return DynamoDbPersistence.create(client, settings(), () -> PersistencePortContract.NOW);
    }

    static DynamoDbAdapterSettings settings() {
        return DynamoDbAdapterSettings.deployed("ticketing-test");
    }

    static <T> CompletableFuture<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }

    static <T> CompletableFuture<T> failed(Throwable error) {
        return CompletableFuture.failedFuture(error);
    }

    static void transactionsApply(DynamoDbAsyncClient client) {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(done(TransactWriteItemsResponse.builder().build()));
    }

    static TransactionCanceledException cancelled(CancellationReason... reasons) {
        return TransactionCanceledException.builder().message("cancelled").cancellationReasons(reasons).build();
    }

    static CancellationReason none() {
        return CancellationReason.builder().code("None").build();
    }

    static CancellationReason conditionFailed(Map<String, AttributeValue> oldItem) {
        CancellationReason.Builder reason = CancellationReason.builder().code("ConditionalCheckFailed");
        if (oldItem != null) {
            reason.item(oldItem);
        }
        return reason.build();
    }

    static CancellationReason code(String code) {
        return CancellationReason.builder().code(code).build();
    }

    // ------------------------------------------------------------------ rendering

    /** One line per item: {@code <operation> <PK> <SK> | <update> | IF <condition>}. */
    static List<String> render(TransactWriteItemsRequest request) {
        return request.transactItems().stream().map(Requests::render).toList();
    }

    static String render(TransactWriteItem item) {
        if (item.update() != null) {
            Update update = item.update();
            return "Update " + key(update.key()) + " | " + inline(update.updateExpression(), update.expressionAttributeNames(),
                    update.expressionAttributeValues()) + " | IF " + inline(update.conditionExpression(),
                    update.expressionAttributeNames(), update.expressionAttributeValues());
        }
        if (item.put() != null) {
            Put put = item.put();
            return "Put " + key(put.item()) + " | IF " + inline(put.conditionExpression(), put.expressionAttributeNames(),
                    put.expressionAttributeValues());
        }
        if (item.delete() != null) {
            Delete delete = item.delete();
            return "Delete " + key(delete.key()) + " | IF " + inline(delete.conditionExpression(),
                    delete.expressionAttributeNames(), delete.expressionAttributeValues());
        }
        ConditionCheck check = item.conditionCheck();
        return "Check " + key(check.key()) + " | IF " + inline(check.conditionExpression(), check.expressionAttributeNames(),
                check.expressionAttributeValues());
    }

    static String render(UpdateItemRequest request) {
        return "Update " + key(request.key()) + " | " + inline(request.updateExpression(), request.expressionAttributeNames(),
                request.expressionAttributeValues()) + " | IF " + inline(request.conditionExpression(),
                request.expressionAttributeNames(), request.expressionAttributeValues());
    }

    static String render(QueryRequest request) {
        return (request.indexName() == null ? "table" : request.indexName()) + " | " + inline(request.keyConditionExpression(),
                request.expressionAttributeNames(), request.expressionAttributeValues())
                + (request.filterExpression() == null ? "" : " | FILTER " + inline(request.filterExpression(),
                request.expressionAttributeNames(), request.expressionAttributeValues()));
    }

    static String inline(String expression, Map<String, String> names, Map<String, AttributeValue> values) {
        if (expression == null) {
            return "-";
        }
        Matcher matcher = PLACEHOLDER.matcher(expression);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String placeholder = matcher.group();
            String replacement = placeholder.startsWith("#") ? names.get(placeholder) : literal(values.get(placeholder));
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    static String literal(AttributeValue value) {
        if (value.s() != null) {
            return "'" + value.s() + "'";
        }
        if (value.n() != null) {
            return value.n();
        }
        if (value.bool() != null) {
            return value.bool().toString();
        }
        if (value.hasL()) {
            return value.l().stream().map(Requests::literal).collect(Collectors.joining(", ", "[", "]"));
        }
        return value.m().entrySet().stream().map(entry -> entry.getKey() + ": " + literal(entry.getValue()))
                .sorted().collect(Collectors.joining(", ", "{", "}"));
    }

    private static String key(Map<String, AttributeValue> item) {
        return item.get("PK").s() + " " + item.get("SK").s();
    }
}
