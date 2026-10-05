package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.s;

import com.nequi.ticketing.application.port.out.OrderReader;
import com.nequi.ticketing.application.port.out.OrderRecord;
import java.time.Instant;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * CMP-010 {@code OrderReader}: strongly consistent read of the Order item alone (AP-010, ADR-025) and the
 * eventually consistent work-index queries AP-016 ({@code GSI3} {@code RESV#}), AP-028 ({@code GSI4}) and
 * AP-029 ({@code GSI3} {@code REVERSAL#}); the guards of the subsequent writes give correctness (ADR-028).
 */
public final class DynamoDbOrderReader implements OrderReader {

    private static final String ORDER_PREFIX = "ORDER#";

    private final DynamoDbTable table;

    DynamoDbOrderReader(DynamoDbTable table) {
        this.table = Objects.requireNonNull(table, "table");
    }

    @Override
    public Mono<OrderRecord> findById(String orderId) {
        return Mono.defer(() -> table.get("AP-010 find Order", Keys.meta(Keys.order(orderId)), true)
                .map(OrderItems::record));
    }

    /** AP-016: {@code GSI3PK = RESV#<shard> AND GSI3SK <= <nowMs>~}. */
    @Override
    public Flux<String> findDueReservations(int shard, Instant now) {
        return Flux.defer(() -> orderIds("AP-016 due reservations", "GSI3", OrderItems.GSI3PK, Keys.reservationsShard(shard),
                OrderItems.GSI3SK, "<=", Keys.millis13(now.toEpochMilli()) + "~"));
    }

    /** AP-028: {@code GSI4PK = PENDQ#<shard> AND GSI4SK < <createdBeforeMs>}. */
    @Override
    public Flux<String> findPendingEnqueue(int shard, Instant createdBefore) {
        return Flux.defer(() -> orderIds("AP-028 pending enqueue", "GSI4", OrderItems.GSI4PK,
                Keys.pendingEnqueueShard(shard), OrderItems.GSI4SK, "<", Keys.millis13(createdBefore.toEpochMilli())));
    }

    /** AP-029: {@code GSI3PK = REVERSAL#<shard> AND GSI3SK <= <nowMs>~}. */
    @Override
    public Flux<String> findDueReversals(int shard, Instant now) {
        return Flux.defer(() -> orderIds("AP-029 due reversals", "GSI3", OrderItems.GSI3PK, Keys.reversalsShard(shard),
                OrderItems.GSI3SK, "<=", Keys.millis13(now.toEpochMilli()) + "~"));
    }

    private Flux<String> orderIds(String operation, String index, String partitionAttribute, String partition,
            String sortAttribute, String operator, String bound) {
        Expression keys = new Expression();
        QueryRequest request = QueryRequest.builder()
                .tableName(table.name())
                .indexName(index)
                .keyConditionExpression(keys.eq(partitionAttribute, s(partition)) + " AND "
                        + keys.compare(sortAttribute, operator, s(bound)))
                .expressionAttributeNames(keys.names())
                .expressionAttributeValues(keys.values())
                .build();
        return table.queryAll(operation, request)
                .map(item -> Attributes.string(item, Keys.PK).substring(ORDER_PREFIX.length()));
    }
}
