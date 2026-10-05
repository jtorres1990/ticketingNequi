package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.api.RetryStrategy;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.Select;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Spikes SPK-006 to SPK-014 of INC-005 (ticketing.architecture.v2.md §13 items 1-5, 9-11, 19, 24),
 * executed against the pinned emulators. The results are recorded in the INC-005 report; the tests stay
 * in the build as regression evidence of the emulator and SDK behaviour the adapter relies on.
 */
class ToolchainSpikeIT {

    private static final DockerImageName LOCALSTACK = DockerImageName.parse("localstack/localstack:4.14.0");

    private static DynamoDbAsyncClient dynamo;
    private static String table;

    @BeforeAll
    static void startEmulator() {
        dynamo = DynamoDbLocalSupport.client();
        table = DynamoDbLocalSupport.createTable();
    }

    // ------------------------------------------------------------------ SPK-006 / SPK-007

    @Test
    @DisplayName("SPK-006 SPK-007 Testcontainers starts DynamoDB Local 3.3.1 and LocalStack 4.14.0 and the async SDK clients operate on both")
    void containersAndAsyncClientsOperate() {
        assertThat(DockerClientFactory.instance().isDockerAvailable()).isTrue();
        System.out.println("SPK-006 docker server version: " + DockerClientFactory.instance().getInfo().getServerVersion()
                + "; ryuk enabled: " + !Boolean.parseBoolean(System.getenv().getOrDefault("TESTCONTAINERS_RYUK_DISABLED", "false")));

        assertThat(DynamoDbLocalSupport.container().getDockerImageName()).isEqualTo("amazon/dynamodb-local:3.3.1");
        StepVerifier.create(Mono.fromFuture(() -> dynamo.listTables()).subscribeOn(Schedulers.parallel()))
                .assertNext(response -> assertThat(response.tableNames()).contains(table))
                .verifyComplete();

        try (GenericContainer<?> localstack = new GenericContainer<>(LOCALSTACK)
                .withExposedPorts(4566)
                .withEnv("SERVICES", "sqs")
                .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)))) {
            localstack.start();
            URI endpoint = URI.create("http://" + localstack.getHost() + ":" + localstack.getMappedPort(4566));
            try (SqsAsyncClient sqs = SqsAsyncClient.builder()
                    .endpointOverride(endpoint)
                    .region(Region.US_EAST_1)
                    .credentialsProvider(DynamoDbLocalSupport.localCredentials())
                    .httpClientBuilder(NettyNioAsyncHttpClient.builder())
                    .build()) {
                Mono<String> roundTrip = Mono.fromFuture(() -> sqs.createQueue(b -> b.queueName("spike-queue")))
                        .flatMap(created -> Mono.fromFuture(() -> sqs.sendMessage(b -> b.queueUrl(created.queueUrl())
                                        .messageBody("spike")))
                                .then(Mono.fromFuture(() -> sqs.receiveMessage(b -> b.queueUrl(created.queueUrl())
                                        .waitTimeSeconds(5).maxNumberOfMessages(1)))))
                        .map(received -> received.messages().getFirst().body())
                        .subscribeOn(Schedulers.parallel());
                StepVerifier.create(roundTrip).expectNext("spike").verifyComplete();
            }
        }
    }

    @Test
    @DisplayName("SPK-007 the DynamoDB client uses the SDK standard retry mode; its effective maximum attempts are documented")
    void retryPolicyIsTheStandardMode() {
        RetryStrategy strategy = dynamo.serviceClientConfiguration().overrideConfiguration().retryStrategy().orElseThrow();
        System.out.println("SPK-007 DynamoDB retry strategy: " + strategy.getClass().getName()
                + " maxAttempts=" + strategy.maxAttempts()
                + "; SDK version " + software.amazon.awssdk.core.util.VersionInfo.SDK_VERSION
                + "; netty " + io.netty.util.Version.identify().get("netty-common").artifactVersion());
        assertThat(strategy.maxAttempts()).isEqualTo(3);
        DynamoDbAsyncClient defaultClient = DynamoDbAsyncClient.builder().region(Region.US_EAST_1)
                .credentialsProvider(DynamoDbLocalSupport.localCredentials())
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()).build();
        try (defaultClient) {
            RetryStrategy implicit = defaultClient.serviceClientConfiguration().overrideConfiguration()
                    .retryStrategy().orElseThrow();
            System.out.println("SPK-007 retry strategy without explicit mode: " + implicit.getClass().getName()
                    + " maxAttempts=" + implicit.maxAttempts() + " (RetryMode.defaultRetryMode=" + RetryMode.defaultRetryMode() + ")");
        }
    }

    // ------------------------------------------------------------------ SPK-008

    @Test
    @DisplayName("SPK-008 a 14-item TransactWriteItems exposes positional cancellation reasons and the ALL_OLD item of the failed condition")
    void transactionCancellationReasonsArePositional() {
        String prefix = "SPK008#" + UUID.randomUUID() + "#";
        for (int index = 0; index < 14; index++) {
            put(item(prefix + index, "state", "AVAILABLE"));
        }
        put(item(prefix + 7, "state", "SOLD"));
        List<TransactWriteItem> items = new ArrayList<>();
        for (int index = 0; index < 13; index++) {
            items.add(conditionalUpdate(prefix + index));
        }
        items.add(TransactWriteItem.builder().put(Put.builder().tableName(table)
                .item(item(prefix + "missing-then-created", "state", "X"))
                .conditionExpression("attribute_exists(PK)")
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD).build()).build());

        assertThatThrownBy(() -> dynamo.transactWriteItems(b -> b.transactItems(items)).join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOfSatisfying(TransactionCanceledException.class, cancelled -> {
                    List<CancellationReason> reasons = cancelled.cancellationReasons();
                    System.out.println("SPK-008 reasons: " + reasons.stream().map(CancellationReason::code).toList());
                    assertThat(reasons).hasSize(14);
                    assertThat(reasons.get(7).code()).isEqualTo("ConditionalCheckFailed");
                    assertThat(reasons.get(7).item()).containsEntry("state", AttributeValue.fromS("SOLD"));
                    assertThat(reasons.get(13).code()).isEqualTo("ConditionalCheckFailed");
                    assertThat(reasons.get(13).hasItem() && !reasons.get(13).item().isEmpty()).isFalse();
                    assertThat(reasons.get(0).code()).isEqualTo("None");
                });
        assertThat(get(prefix + 0).get("state").s()).isEqualTo("AVAILABLE");
        assertThat(get(prefix + "missing-then-created")).isEmpty();
    }

    // ------------------------------------------------------------------ SPK-009

    @RepeatedTest(10)
    @DisplayName("SPK-009 concurrent multi-partition transactions over the same Ticket have exactly one winner and no partial writes")
    void concurrentTransactionsHaveOneWinner() {
        String ticket = "SPK009#" + UUID.randomUUID();
        put(item(ticket, "state", "AVAILABLE"));
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger conditionFailures = new AtomicInteger();
        List<CompletableFuture<Boolean>> attempts = new ArrayList<>();
        for (int contender = 0; contender < 8; contender++) {
            String order = ticket + "#order-" + contender;
            attempts.add(dynamo.transactWriteItems(b -> b.transactItems(
                            TransactWriteItem.builder().update(Update.builder().tableName(table).key(key(ticket))
                                    .updateExpression("SET #state = :reserved, orderId = :order")
                                    .conditionExpression("#state = :available")
                                    .expressionAttributeNames(Map.of("#state", "state"))
                                    .expressionAttributeValues(Map.of(":reserved", AttributeValue.fromS("RESERVED"),
                                            ":available", AttributeValue.fromS("AVAILABLE"),
                                            ":order", AttributeValue.fromS(order)))
                                    .build()).build(),
                            TransactWriteItem.builder().put(Put.builder().tableName(table)
                                    .item(item(order, "status", "CREATED"))
                                    .conditionExpression("attribute_not_exists(PK)").build()).build()))
                    .handle((ok, error) -> {
                        if (error == null) {
                            return true;
                        }
                        Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                        if (cause instanceof TransactionCanceledException cancelled) {
                            List<String> codes = cancelled.cancellationReasons().stream().map(CancellationReason::code).toList();
                            (codes.contains("TransactionConflict") ? conflicts : conditionFailures).incrementAndGet();
                            return false;
                        }
                        throw new CompletionException(cause);
                    }));
        }
        long winners = attempts.stream().map(CompletableFuture::join).filter(Boolean::booleanValue).count();
        System.out.println("SPK-009 winners=" + winners + " conditionFailures=" + conditionFailures + " conflicts=" + conflicts);
        assertThat(winners).isEqualTo(1);
        String owner = get(ticket).get("orderId").s();
        for (int contender = 0; contender < 8; contender++) {
            String order = ticket + "#order-" + contender;
            assertThat(get(order).isEmpty()).isEqualTo(!order.equals(owner));
        }
    }

    @RepeatedTest(10)
    @DisplayName("SPK-009 a transaction and a simple conditional write on the same item are mutually exclusive")
    void transactionAndConditionalWriteAreExclusive() {
        String target = "SPK009-simple#" + UUID.randomUUID();
        put(item(target, "status", "CREATED"));
        CompletableFuture<Boolean> transaction = dynamo.transactWriteItems(b -> b.transactItems(
                        TransactWriteItem.builder().update(Update.builder().tableName(table).key(key(target))
                                .updateExpression("SET #status = :expired")
                                .conditionExpression("#status = :created")
                                .expressionAttributeNames(Map.of("#status", "status"))
                                .expressionAttributeValues(Map.of(":expired", AttributeValue.fromS("EXPIRED"),
                                        ":created", AttributeValue.fromS("CREATED")))
                                .build()).build(),
                        TransactWriteItem.builder().put(Put.builder().tableName(table)
                                .item(item(target + "#audit", "status", "x"))
                                .conditionExpression("attribute_not_exists(PK)").build()).build()))
                .handle((ok, error) -> error == null);
        CompletableFuture<Boolean> simple = dynamo.updateItem(b -> b.tableName(table).key(key(target))
                        .updateExpression("SET #status = :confirmed")
                        .conditionExpression("#status = :created")
                        .expressionAttributeNames(Map.of("#status", "status"))
                        .expressionAttributeValues(Map.of(":confirmed", AttributeValue.fromS("CONFIRMED"),
                                ":created", AttributeValue.fromS("CREATED"))))
                .handle((ok, error) -> {
                    if (error == null) {
                        return true;
                    }
                    Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                    assertThat(cause).isInstanceOfAny(ConditionalCheckFailedException.class, DynamoDbException.class);
                    return false;
                });
        boolean transactionWon = transaction.join();
        boolean simpleWon = simple.join();
        assertThat(transactionWon ^ simpleWon).as("exactly one writer wins").isTrue();
        assertThat(get(target).get("status").s()).isEqualTo(transactionWon ? "EXPIRED" : "CONFIRMED");
        assertThat(get(target + "#audit").isEmpty()).isEqualTo(!transactionWon);
    }

    // ------------------------------------------------------------------ SPK-010 / SPK-011

    @Test
    @DisplayName("SPK-010 SPK-011 batch writes of 25 items and consistent batch reads of 100 keys; larger requests are rejected")
    void batchLimits() {
        String prefix = "SPK010#" + UUID.randomUUID() + "#";
        List<CompletableFuture<BatchWriteItemResponse>> requests = new ArrayList<>();
        for (int request = 0; request < 4; request++) {
            List<WriteRequest> writes = new ArrayList<>();
            for (int index = 0; index < 25; index++) {
                writes.add(WriteRequest.builder().putRequest(PutRequest.builder()
                        .item(item(prefix + (request * 25 + index), "state", "AVAILABLE")).build()).build());
            }
            requests.add(dynamo.batchWriteItem(b -> b.requestItems(Map.of(table, writes))));
        }
        int unprocessed = requests.stream().map(CompletableFuture::join)
                .mapToInt(response -> response.unprocessedItems().getOrDefault(table, List.of()).size()).sum();
        System.out.println("SPK-010 unprocessed items after 4 parallel requests of 25: " + unprocessed);
        assertThat(unprocessed).isZero();

        List<WriteRequest> tooMany = new ArrayList<>();
        for (int index = 0; index < 26; index++) {
            tooMany.add(WriteRequest.builder().putRequest(PutRequest.builder()
                    .item(item(prefix + "extra-" + index, "state", "AVAILABLE")).build()).build());
        }
        assertThatThrownBy(() -> dynamo.batchWriteItem(b -> b.requestItems(Map.of(table, tooMany))).join())
                .cause().isInstanceOf(DynamoDbException.class);

        List<Map<String, AttributeValue>> keys = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            keys.add(key(prefix + index));
        }
        BatchGetItemResponse read = dynamo.batchGetItem(b -> b.requestItems(Map.of(table,
                KeysAndAttributes.builder().keys(keys).consistentRead(true).build()))).join();
        System.out.println("SPK-011 consistent batch read returned " + read.responses().get(table).size()
                + " items, unprocessed keys " + read.unprocessedKeys().size());
        assertThat(read.responses().get(table)).hasSize(100);

        List<Map<String, AttributeValue>> tooManyKeys = new ArrayList<>(keys);
        tooManyKeys.add(key(prefix + "extra"));
        assertThatThrownBy(() -> dynamo.batchGetItem(b -> b.requestItems(Map.of(table,
                KeysAndAttributes.builder().keys(tooManyKeys).consistentRead(true).build()))).join())
                .cause().isInstanceOf(DynamoDbException.class);
    }

    // ------------------------------------------------------------------ SPK-012

    @Test
    @DisplayName("SPK-012 a count-only query paginates by read size and the aggregated count is exact")
    void countOnlyQueryPaginates() {
        String shard = "AVAIL#spk012-" + UUID.randomUUID() + "#0";
        int total = 15_000;
        List<CompletableFuture<?>> writes = new ArrayList<>();
        for (int start = 0; start < total; start += 25) {
            List<WriteRequest> batch = new ArrayList<>();
            for (int index = start; index < start + 25; index++) {
                Map<String, AttributeValue> ticket = new HashMap<>(item(shard + "#t" + index, "state", "AVAILABLE"));
                ticket.put("GSI2PK", AttributeValue.fromS(shard));
                ticket.put("GSI2SK", AttributeValue.fromS("SECTION1#ROW001#%04d".formatted(index % 1000) + "#" + index));
                ticket.put("ticketId", AttributeValue.fromS("SECTION1-ROW001-" + index));
                ticket.put("section", AttributeValue.fromS("SECTION1"));
                ticket.put("row", AttributeValue.fromS("ROW001"));
                ticket.put("seat", AttributeValue.fromN(Integer.toString(index % 1000 + 1)));
                batch.add(WriteRequest.builder().putRequest(PutRequest.builder().item(ticket).build()).build());
            }
            writes.add(dynamo.batchWriteItem(b -> b.requestItems(Map.of(table, batch))));
            if (writes.size() == 8) {
                CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).join();
                writes.clear();
            }
        }
        CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).join();

        int pages = 0;
        long counted = 0;
        Map<String, AttributeValue> start = null;
        do {
            Map<String, AttributeValue> exclusiveStart = start;
            QueryResponse page = dynamo.query(b -> b.tableName(table).indexName("GSI2").select(Select.COUNT)
                    .keyConditionExpression("GSI2PK = :pk")
                    .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(shard)))
                    .exclusiveStartKey(exclusiveStart)).join();
            pages++;
            counted += page.count();
            start = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
        } while (start != null);
        System.out.println("SPK-012 count-only query over " + total + " index items: " + pages + " page(s), count " + counted);
        assertThat(counted).isEqualTo(total);
    }

    // ------------------------------------------------------------------ SPK-013 / SPK-014

    @Test
    @DisplayName("SPK-013 TTL is enabled on the ttl attribute and expired idempotency records are accepted; deletion is not relied upon")
    void timeToLive() {
        TimeToLiveStatus status = dynamo.describeTimeToLive(b -> b.tableName(table)).join()
                .timeToLiveDescription().timeToLiveStatus();
        String idempotency = "IDEM#spk013#" + UUID.randomUUID();
        Map<String, AttributeValue> record = new HashMap<>(item(idempotency, "orderId", "o-1"));
        record.put("ttl", AttributeValue.fromN("1"));
        put(record);
        boolean presentAfterWrite = !get(idempotency).isEmpty();
        System.out.println("SPK-013 TTL status=" + status + "; expired record readable after write=" + presentAfterWrite);
        assertThat(status).isEqualTo(TimeToLiveStatus.ENABLED);
        assertThat(presentAfterWrite).isTrue();
    }

    @Test
    @DisplayName("SPK-014 removing the key attributes of a sparse GSI removes the item from the index")
    void sparseIndexRemoval() {
        String order = "ORDER#spk014-" + UUID.randomUUID();
        String gsi3 = "RESV#spk014-" + UUID.randomUUID();
        String gsi4 = "PENDQ#spk014-" + UUID.randomUUID();
        Map<String, AttributeValue> indexed = new HashMap<>(item(order, "status", "CREATED"));
        indexed.put("GSI3PK", AttributeValue.fromS(gsi3));
        indexed.put("GSI3SK", AttributeValue.fromS("0000000000001#o"));
        indexed.put("GSI4PK", AttributeValue.fromS(gsi4));
        indexed.put("GSI4SK", AttributeValue.fromS("0000000000001#o"));
        put(indexed);
        assertThat(indexCount("GSI3", gsi3)).isEqualTo(1);
        assertThat(indexCount("GSI4", gsi4)).isEqualTo(1);

        dynamo.updateItem(b -> b.tableName(table).key(key(order)).updateExpression("REMOVE GSI3PK, GSI3SK, GSI4PK, GSI4SK"))
                .join();
        System.out.println("SPK-014 after REMOVE: GSI3=" + indexCount("GSI3", gsi3) + " GSI4=" + indexCount("GSI4", gsi4));
        assertThat(indexCount("GSI3", gsi3)).isZero();
        assertThat(indexCount("GSI4", gsi4)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private int indexCount(String index, String partition) {
        return dynamo.query(b -> b.tableName(table).indexName(index).keyConditionExpression(index + "PK = :pk")
                .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(partition)))).join().count();
    }

    private TransactWriteItem conditionalUpdate(String pk) {
        return TransactWriteItem.builder().update(Update.builder().tableName(table).key(key(pk))
                .updateExpression("SET #state = :reserved")
                .conditionExpression("#state = :available")
                .expressionAttributeNames(Map.of("#state", "state"))
                .expressionAttributeValues(Map.of(":reserved", AttributeValue.fromS("RESERVED"),
                        ":available", AttributeValue.fromS("AVAILABLE")))
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                .build()).build();
    }

    private void put(Map<String, AttributeValue> item) {
        dynamo.putItem(b -> b.tableName(table).item(item)).join();
    }

    private Map<String, AttributeValue> get(String pk) {
        return dynamo.getItem(b -> b.tableName(table).key(key(pk)).consistentRead(true)).join().item();
    }

    private static Map<String, AttributeValue> key(String pk) {
        return Map.of("PK", AttributeValue.fromS(pk), "SK", AttributeValue.fromS("#META"));
    }

    private static Map<String, AttributeValue> item(String pk, String attribute, String value) {
        Map<String, AttributeValue> item = new HashMap<>(key(pk));
        item.put(attribute, AttributeValue.fromS(value));
        return item;
    }
}
