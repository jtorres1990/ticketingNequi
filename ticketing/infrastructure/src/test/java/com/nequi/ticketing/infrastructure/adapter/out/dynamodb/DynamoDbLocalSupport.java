package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;

/**
 * Integration test support: one DynamoDB Local container per test JVM with the image pinned in
 * {@code implementation/ticketing.local-environment.v1.md} (removed by Testcontainers' reaper), and the
 * table fixture of {@code ticketing.data-model.v2.md} §2. The application never creates tables (data
 * model §7); only this fixture does, mirroring what {@code infra-init} creates locally (ADR-036).
 */
public final class DynamoDbLocalSupport {

    static final DockerImageName IMAGE = DockerImageName.parse("amazon/dynamodb-local:3.3.1");

    private static final GenericContainer<?> CONTAINER = new GenericContainer<>(IMAGE)
            .withExposedPorts(8000)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

    private static DynamoDbAsyncClient client;

    private DynamoDbLocalSupport() {
    }

    static synchronized GenericContainer<?> container() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return CONTAINER;
    }

    public static URI endpoint() {
        GenericContainer<?> running = container();
        return URI.create("http://" + running.getHost() + ":" + running.getMappedPort(8000));
    }

    /** Shared asynchronous client built by the production factory against the local endpoint. */
    static synchronized DynamoDbAsyncClient client() {
        if (client == null) {
            client = DynamoDbClientFactory.create(new DynamoDbConnectionSettings(endpoint(), "us-east-1"),
                    localCredentials());
        }
        return client;
    }

    /** DynamoDB Local and LocalStack accept any credentials; these are placeholders, not secrets. */
    public static StaticCredentialsProvider localCredentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local"));
    }

    /** Creates a fresh table with the keys, sparse GSIs and TTL of the data model; returns its name. */
    public static String createTable() {
        String tableName = "ticketing-it-" + UUID.randomUUID();
        createTable(client(), tableName);
        return tableName;
    }

    static void createTable(DynamoDbAsyncClient dynamo, String tableName) {
        dynamo.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        string("PK"), string("SK"),
                        string("GSI1PK"), string("GSI1SK"),
                        string("GSI2PK"), string("GSI2SK"),
                        string("GSI3PK"), string("GSI3SK"),
                        string("GSI4PK"), string("GSI4SK"))
                .keySchema(hash("PK"), range("SK"))
                .globalSecondaryIndexes(
                        index("GSI1", Projection.builder().projectionType(ProjectionType.INCLUDE)
                                .nonKeyAttributes(List.of("entityType", "eventId", "name", "venue", "startsAt",
                                        "startsAtMs", "capacity", "availabilityShards", "provisioningStatus",
                                        "createdAt", "lastProgressAtMs", "provisioningRepublishCount"))
                                .build()),
                        index("GSI2", Projection.builder().projectionType(ProjectionType.INCLUDE)
                                .nonKeyAttributes(List.of("ticketId", "section", "row", "seat")).build()),
                        index("GSI3", Projection.builder().projectionType(ProjectionType.KEYS_ONLY).build()),
                        index("GSI4", Projection.builder().projectionType(ProjectionType.KEYS_ONLY).build()))
                .build()).join();
        dynamo.updateTimeToLive(builder -> builder.tableName(tableName)
                .timeToLiveSpecification(TimeToLiveSpecification.builder().attributeName("ttl").enabled(true).build()))
                .join();
    }

    private static GlobalSecondaryIndex index(String name, Projection projection) {
        return GlobalSecondaryIndex.builder()
                .indexName(name)
                .keySchema(hash(name + "PK"), range(name + "SK"))
                .projection(projection)
                .build();
    }

    private static AttributeDefinition string(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }

    private static KeySchemaElement hash(String name) {
        return KeySchemaElement.builder().attributeName(name).keyType(KeyType.HASH).build();
    }

    private static KeySchemaElement range(String name) {
        return KeySchemaElement.builder().attributeName(name).keyType(KeyType.RANGE).build();
    }
}
