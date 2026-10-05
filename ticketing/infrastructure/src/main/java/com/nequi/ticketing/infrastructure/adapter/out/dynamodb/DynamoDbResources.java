package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import com.nequi.ticketing.application.port.out.Clock;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * The DynamoDB client and the persistence adapter built on it, as one resource that the composition (CMP-021)
 * opens at startup and closes at the end of the ordered shutdown (ADR-037), without seeing SDK types (ADR-034).
 */
public final class DynamoDbResources implements AutoCloseable {

    private final DynamoDbAsyncClient client;
    private final DynamoDbPersistence persistence;

    private DynamoDbResources(DynamoDbAsyncClient client, DynamoDbPersistence persistence) {
        this.client = client;
        this.persistence = persistence;
    }

    /** Opens the client (with the observation hook) and composes the five ports of ADR-034 on one table. */
    public static DynamoDbResources open(DynamoDbConnectionSettings connection, DynamoDbAdapterSettings settings,
            Clock clock, DynamoDbEvents events) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(clock, "clock");
        DynamoDbAsyncClient client = DynamoDbClientFactory.create(connection, events);
        return new DynamoDbResources(client, DynamoDbPersistence.create(client, settings, clock));
    }

    public DynamoDbPersistence persistence() {
        return persistence;
    }

    @Override
    public void close() {
        client.close();
    }
}
