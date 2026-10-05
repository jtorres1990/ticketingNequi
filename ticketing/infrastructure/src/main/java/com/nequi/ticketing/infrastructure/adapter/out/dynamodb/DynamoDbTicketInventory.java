package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.s;

import com.nequi.ticketing.application.port.out.AvailableTicket;
import com.nequi.ticketing.application.port.out.AvailableTicketPage;
import com.nequi.ticketing.application.port.out.Clock;
import com.nequi.ticketing.application.port.out.InventoryVerification;
import com.nequi.ticketing.application.port.out.TicketInventory;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.ticket.Ticket;
import com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Cursors.AvailabilityPosition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.Select;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/**
 * CMP-010 {@code TicketInventory} over DynamoDB (ADR-022, ADR-024, ADR-039, ADR-040): availability reads
 * over the sharded sparse index {@code GSI2} (AP-005 probe, AP-020 page, AP-021 count, all eventually
 * consistent and without cache: the 1 s cache of the count lives in CMP-004), and the provisioning batch
 * operations AP-002 (write), AP-025 (consistent verification) and AP-027 (purge).
 */
public final class DynamoDbTicketInventory implements TicketInventory {

    private static final String GSI2 = "GSI2";

    private final DynamoDbTable table;
    private final Clock clock;

    DynamoDbTicketInventory(DynamoDbTable table, Clock clock) {
        this.table = Objects.requireNonNull(table, "table");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** AP-005: limit-1 query per shard, shards in random order in waves, stopping at the first result. */
    @Override
    public Mono<Boolean> hasAvailable(Event event) {
        return Mono.defer(() -> {
            List<Integer> shards = new ArrayList<>(IntStream.range(0, event.availabilityShards()).boxed().toList());
            Collections.shuffle(shards);
            return Flux.fromIterable(DynamoDbTable.partition(shards, table.settings().probeWaveSize()))
                    .concatMap(wave -> Flux.fromIterable(wave)
                            .flatMap(shard -> probe(event, shard))
                            .any(Boolean::booleanValue))
                    .any(Boolean::booleanValue);
        });
    }

    private Mono<Boolean> probe(Event event, int shard) {
        return table.queryUpTo("AP-005 sold-out probe", shardQuery(event, shard, null), 1, null)
                .map(collected -> !collected.items().isEmpty());
    }

    /** AP-021: count-only query per shard, all shards in parallel (bounded), pages summed. */
    @Override
    public Mono<Long> countAvailable(Event event) {
        return Mono.defer(() -> {
            int shards = event.availabilityShards();
            int concurrency = table.settings().countConcurrency() == 0 ? shards
                    : Math.min(shards, table.settings().countConcurrency());
            return Flux.range(0, shards)
                    .flatMap(shard -> table.countAll("AP-021 count available",
                            shardQuery(event, shard, null).toBuilder().select(Select.COUNT).build()), concurrency)
                    .reduce(0L, Long::sum);
        });
    }

    /**
     * AP-020: shards in order 0..S-1, inside each shard by section, row and seat; optionally restricted to a
     * section. One item beyond the page is read so that a cursor is returned only when more Tickets exist.
     */
    @Override
    public Mono<AvailableTicketPage> findAvailablePage(Event event, String section, int pageSize, String cursor) {
        return Mono.defer(() -> {
            AvailabilityPosition position = cursor == null ? null : Cursors.availabilityPosition(cursor, event, section);
            int firstShard = position == null ? 0 : position.shard();
            Map<String, AttributeValue> start = position == null ? null : Cursors.availabilityStartKey(event, position);
            return collect(event, section, pageSize + 1, firstShard, start, new ArrayList<>())
                    .map(found -> page(event, section, pageSize, found));
        });
    }

    private record Found(int shard, Map<String, AttributeValue> item) {
    }

    private Mono<List<Found>> collect(Event event, String section, int wanted, int shard, Map<String, AttributeValue> start,
            List<Found> found) {
        if (found.size() >= wanted || shard >= event.availabilityShards()) {
            return Mono.just(found);
        }
        return table.queryUpTo("AP-020 availability page", shardQuery(event, shard, section), wanted - found.size(), start)
                .flatMap(collected -> {
                    collected.items().forEach(item -> found.add(new Found(shard, item)));
                    return collect(event, section, wanted, shard + 1, null, found);
                });
    }

    private static AvailableTicketPage page(Event event, String section, int pageSize, List<Found> found) {
        List<Found> visible = found.subList(0, Math.min(pageSize, found.size()));
        List<AvailableTicket> tickets = visible.stream().map(entry -> new AvailableTicket(
                Attributes.string(entry.item(), TicketItems.TICKET_ID),
                Attributes.string(entry.item(), TicketItems.SECTION),
                Attributes.string(entry.item(), TicketItems.ROW),
                Attributes.integer(entry.item(), TicketItems.SEAT))).toList();
        String next = null;
        if (found.size() > pageSize) {
            Found last = visible.getLast();
            next = Cursors.availability(event, section, last.shard(), Attributes.string(last.item(), TicketItems.TICKET_ID));
        }
        return new AvailableTicketPage(tickets, next);
    }

    private QueryRequest shardQuery(Event event, int shard, String section) {
        Expression keys = new Expression();
        String condition = keys.eq(TicketItems.GSI2PK, s(Keys.availability(event.eventId(), shard)));
        if (section != null) {
            condition += " AND begins_with(" + keys.name(TicketItems.GSI2SK) + ", " + keys.value(s(section + "#")) + ")";
        }
        return QueryRequest.builder()
                .tableName(table.name())
                .indexName(GSI2)
                .keyConditionExpression(condition)
                .expressionAttributeNames(keys.names())
                .expressionAttributeValues(keys.values())
                .build();
    }

    /** AP-002: Tickets in their initial state, deterministic keys, no condition (idempotent per key). */
    @Override
    public Mono<Void> writeBatch(Event event, List<Ticket> tickets) {
        return Mono.defer(() -> {
            Instant now = clock.now();
            List<WriteRequest> writes = tickets.stream()
                    .map(ticket -> WriteRequest.builder().putRequest(PutRequest.builder()
                            .item(TicketItems.provisioned(ticket, event.availabilityShards(), now)).build()).build())
                    .toList();
            return table.batchWrite("AP-002 write Ticket batch", writes);
        });
    }

    /**
     * AP-025: strongly consistent batch read of every expected key; a Ticket is valid when it exists with
     * the initial state of the definition and without Order.
     */
    @Override
    public Mono<InventoryVerification> verify(Event event, List<Ticket> expected) {
        return Mono.defer(() -> {
            List<Map<String, AttributeValue>> keys = expected.stream()
                    .map(ticket -> TicketItems.key(event.eventId(), ticket.ticketId()))
                    .toList();
            Map<String, String> names = Map.of("#t", TicketItems.TICKET_ID, "#s", TicketItems.STATE,
                    "#o", TicketItems.ORDER_ID);
            return table.batchGet("AP-025 verify inventory", keys, "#t, #s, #o", names).map(items -> {
                Map<String, Map<String, AttributeValue>> byTicket = new HashMap<>();
                items.forEach(item -> byTicket.put(Attributes.string(item, TicketItems.TICKET_ID), item));
                List<String> invalid = new ArrayList<>();
                int verified = 0;
                for (Ticket wanted : expected) {
                    Map<String, AttributeValue> stored = byTicket.get(wanted.ticketId());
                    if (stored != null && wanted.state().name().equals(Attributes.optionalString(stored, TicketItems.STATE))
                            && !Attributes.present(stored, TicketItems.ORDER_ID)) {
                        verified++;
                    } else {
                        invalid.add(wanted.ticketId());
                    }
                }
                return new InventoryVerification(verified, invalid);
            });
        });
    }

    /** AP-027: batch delete of the generated keys of a {@code FAILED} Event. */
    @Override
    public Mono<Void> purge(Event event, List<String> ticketIds) {
        return Mono.defer(() -> table.batchWrite("AP-027 purge Tickets", ticketIds.stream()
                .map(ticketId -> WriteRequest.builder().deleteRequest(DeleteRequest.builder()
                        .key(TicketItems.key(event.eventId(), ticketId)).build()).build())
                .toList()));
    }
}
