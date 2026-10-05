package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.PersistencePortContract.NOW;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Requests.done;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Requests.render;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.application.port.out.AvailableTicket;
import com.nequi.ticketing.application.port.out.AvailableTicketPage;
import com.nequi.ticketing.application.port.out.InventoryVerification;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.InventoryDefinition;
import com.nequi.ticketing.domain.event.InventoryDefinition.Row;
import com.nequi.ticketing.domain.event.InventoryDefinition.Section;
import com.nequi.ticketing.domain.event.InventoryLimits;
import com.nequi.ticketing.domain.event.ShardingPolicy;
import com.nequi.ticketing.domain.ticket.Ticket;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.Select;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/** Unit tests of the Ticket inventory of the DynamoDB adapter with a simulated client (ADR-024, ADR-038, ADR-040). */
class DynamoDbTicketInventoryTest {

    private static final String TABLE = "ticketing-test";
    /** 50,000 seats in 100 rows of 500: 25 availability shards (ADR-022). */
    private static final Event LARGE = Event.create("e-large", "Concert", "Arena", NOW.plus(Duration.ofDays(10)), 50_000,
            new InventoryDefinition(List.of(new Section("A", IntStream.rangeClosed(1, 100)
                    .mapToObj(row -> new Row(Integer.toString(row), 500)).toList())), List.of()),
            NOW, InventoryLimits.DEPLOYED).enable(50_000);
    private static final Event SMALL = Event.create("e-small", "Concert", "Arena", NOW.plus(Duration.ofDays(10)), 25,
            PersistencePortContract.DEFINITION, NOW, InventoryLimits.DEPLOYED).enable(25);

    private DynamoDbAsyncClient client;
    private DynamoDbTicketInventory inventory;

    @BeforeEach
    void setUp() {
        client = Requests.client();
        inventory = Requests.persistence(client).ticketInventory();
    }

    @Test
    @DisplayName("AP-005 AV-001 ADR-040 the sold-out probe queries one item per shard in waves of 4 and stops at the first result")
    void probeStopsAtFirstWave() {
        assertThat(LARGE.availabilityShards()).isEqualTo(25);
        when(client.query(any(QueryRequest.class))).thenReturn(done(QueryResponse.builder()
                .items(ticketProjection("A-1-1", 0)).count(1).build()));

        assertThat(inventory.hasAvailable(LARGE).block()).isTrue();
        ArgumentCaptor<QueryRequest> probes = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client, atMost(4)).query(probes.capture());
        assertThat(probes.getValue().limit()).isEqualTo(1);
        assertThat(render(probes.getValue())).startsWith("GSI2 | GSI2PK = 'AVAIL#e-large#");
    }

    @Test
    @DisplayName("AP-005 BR-012 RISK-023 a sold-out Event is probed on every shard exactly once")
    void soldOutProbesEveryShard() {
        when(client.query(any(QueryRequest.class))).thenReturn(done(QueryResponse.builder().items(List.of()).count(0).build()));

        assertThat(inventory.hasAvailable(LARGE).block()).isFalse();
        ArgumentCaptor<QueryRequest> probes = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client, times(25)).query(probes.capture());
        assertThat(probes.getAllValues().stream().map(Requests::render).distinct()).hasSize(25);
    }

    @Test
    @DisplayName("AP-021 SPK-012 the count is a count-only query per shard, all shards, with every page summed")
    void countSumsShardsAndPages() {
        when(client.query(any(QueryRequest.class))).thenAnswer(invocation -> {
            QueryRequest request = invocation.getArgument(0);
            boolean firstPage = !request.hasExclusiveStartKey() || request.exclusiveStartKey().isEmpty();
            return done(firstPage
                    ? QueryResponse.builder().count(1_000).lastEvaluatedKey(Map.of("PK", AttributeValue.fromS("k"))).build()
                    : QueryResponse.builder().count(999).build());
        });

        assertThat(inventory.countAvailable(LARGE).block()).isEqualTo(25L * 1_999);
        ArgumentCaptor<QueryRequest> queries = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client, times(50)).query(queries.capture());
        assertThat(queries.getAllValues()).allMatch(query -> query.select() == Select.COUNT && query.limit() == null);
    }

    @Test
    @DisplayName("AP-020 AC-010 ADR-040 a page reads one item beyond its size across shards and returns a cursor positioned on its last Ticket")
    void pageAcrossShards() {
        int shards = LARGE.availabilityShards();
        when(client.query(any(QueryRequest.class))).thenAnswer(invocation -> {
            QueryRequest request = invocation.getArgument(0);
            String partition = request.expressionAttributeValues().values().iterator().next().s();
            int shard = Integer.parseInt(partition.substring(partition.lastIndexOf('#') + 1));
            if (shard == 0) {
                return done(QueryResponse.builder().items(ticketProjection("A-1-1", 0), ticketProjection("A-1-2", 0)).count(2)
                        .build());
            }
            return done(QueryResponse.builder().items(ticketProjection("A-2-1", shard)).count(1)
                    .lastEvaluatedKey(Map.of("PK", AttributeValue.fromS("k"))).build());
        });

        AvailableTicketPage page = inventory.findAvailablePage(LARGE, null, 2, null).block();
        assertThat(page.tickets()).extracting(AvailableTicket::ticketId).containsExactly("A-1-1", "A-1-2");
        assertThat(page.nextCursor()).isEqualTo(Cursors.availability(LARGE, null, 0, "A-1-2"));
        ArgumentCaptor<QueryRequest> queries = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client, times(2)).query(queries.capture());
        assertThat(queries.getAllValues().getFirst().limit()).isEqualTo(3);
        assertThat(queries.getAllValues().get(1).limit()).isEqualTo(1);
        assertThat(shards).isGreaterThan(1);
    }

    @Test
    @DisplayName("AP-020 ADR-040 a section filter restricts the sort key and a cursor resumes after its last key in its shard")
    void sectionFilterAndResume() {
        String ticketId = firstTicketOfShard(SMALL, 0);
        when(client.query(any(QueryRequest.class))).thenReturn(done(QueryResponse.builder().items(List.of()).count(0).build()));

        AvailableTicketPage page = inventory.findAvailablePage(SMALL, "A",
                10, Cursors.availability(SMALL, "A", 0, ticketId)).block();
        assertThat(page.tickets()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        ArgumentCaptor<QueryRequest> query = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client).query(query.capture());
        assertThat(render(query.getValue())).isEqualTo("GSI2 | GSI2PK = 'AVAIL#e-small#0' AND begins_with(GSI2SK, 'A#')");
        assertThat(query.getValue().exclusiveStartKey())
                .containsEntry("GSI2SK", AttributeValue.fromS(TicketItems.availabilitySort(ticketId)))
                .containsEntry("PK", AttributeValue.fromS("TICKET#e-small#" + ticketId));
    }

    @Test
    @DisplayName("AP-002 SPK-010 ADR-024 a batch of 100 Tickets is written in 4 requests of 25 with unprocessed items retried")
    void batchWrite() {
        List<Ticket> tickets = PersistencePortContract.initialTickets(LARGE).subList(0, 100);
        List<WriteRequest> leftover = new ArrayList<>();
        when(client.batchWriteItem(any(BatchWriteItemRequest.class))).thenAnswer(invocation -> {
            BatchWriteItemRequest request = invocation.getArgument(0);
            List<WriteRequest> writes = request.requestItems().get(TABLE);
            synchronized (leftover) {
                if (leftover.isEmpty() && writes.size() == 25) {
                    leftover.add(writes.getLast());
                    return done(BatchWriteItemResponse.builder().unprocessedItems(Map.of(TABLE, List.of(writes.getLast())))
                            .build());
                }
            }
            return done(BatchWriteItemResponse.builder().build());
        });

        inventory.writeBatch(LARGE, tickets).block();
        ArgumentCaptor<BatchWriteItemRequest> requests = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        verify(client, times(5)).batchWriteItem(requests.capture());
        List<Integer> sizes = requests.getAllValues().stream().map(request -> request.requestItems().get(TABLE).size()).toList();
        assertThat(sizes).containsExactlyInAnyOrder(25, 25, 25, 25, 1);
        Map<String, AttributeValue> written = requests.getAllValues().getFirst().requestItems().get(TABLE).getFirst()
                .putRequest().item();
        assertThat(written.get("state").s()).isEqualTo("AVAILABLE");
        assertThat(written.get("GSI2PK").s()).startsWith("AVAIL#e-large#");
        assertThat(written.get("updatedAt").s()).isEqualTo(NOW.toString());
    }

    @Test
    @DisplayName("ADR-035 ADR-039 unprocessed batch items that persist after the bounded retries are a technical error")
    void unprocessedPersisting() {
        when(client.batchWriteItem(any(BatchWriteItemRequest.class))).thenAnswer(invocation -> {
            BatchWriteItemRequest request = invocation.getArgument(0);
            return done(BatchWriteItemResponse.builder().unprocessedItems(request.requestItems()).build());
        });

        StepVerifier.create(inventory.purge(SMALL, List.of("A-1-1"))).expectError(DynamoDbStoreException.class).verify();
        verify(client, times(1 + Requests.settings().unprocessedRetries())).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    @DisplayName("AP-025 SPK-011 VAL-009 verification is a consistent batch read of 100 keys per request with unprocessed keys retried")
    void verification() {
        List<Ticket> expected = PersistencePortContract.initialTickets(SMALL);
        List<String> invalid = List.of("A-1-3", "B-1-5");
        when(client.batchGetItem(any(BatchGetItemRequest.class)))
                .thenAnswer(invocation -> {
                    BatchGetItemRequest request = invocation.getArgument(0);
                    KeysAndAttributes keys = request.requestItems().get(TABLE);
                    List<Map<String, AttributeValue>> found = new ArrayList<>();
                    List<Map<String, AttributeValue>> pending = new ArrayList<>();
                    for (Map<String, AttributeValue> key : keys.keys()) {
                        String ticketId = key.get("PK").s().substring("TICKET#e-small#".length());
                        if (ticketId.equals("A-2-10") && keys.keys().size() > 1) {
                            pending.add(key);
                        } else if (!ticketId.equals("A-1-3")) {
                            Map<String, AttributeValue> item = new HashMap<>();
                            item.put("ticketId", AttributeValue.fromS(ticketId));
                            item.put("state", AttributeValue.fromS(ticketId.equals("B-1-5") ? "AVAILABLE" : "AVAILABLE"));
                            if (ticketId.equals("B-1-5")) {
                                item.put("orderId", AttributeValue.fromS("o-1"));
                            }
                            found.add(item);
                        }
                    }
                    BatchGetItemResponse.Builder response = BatchGetItemResponse.builder().responses(Map.of(TABLE, found));
                    if (!pending.isEmpty()) {
                        response.unprocessedKeys(Map.of(TABLE, keys.toBuilder().keys(pending).build()));
                    }
                    return done(response.build());
                });

        InventoryVerification verification = inventory.verify(SMALL, expected).block();
        assertThat(verification.invalidTicketIds()).containsExactlyInAnyOrderElementsOf(invalid);
        assertThat(verification.verifiedCount()).isEqualTo(23);
        ArgumentCaptor<BatchGetItemRequest> reads = ArgumentCaptor.forClass(BatchGetItemRequest.class);
        verify(client, times(2)).batchGetItem(reads.capture());
        KeysAndAttributes first = reads.getAllValues().getFirst().requestItems().get(TABLE);
        assertThat(first.consistentRead()).isTrue();
        assertThat(first.keys()).hasSize(25);
        assertThat(reads.getAllValues().get(1).requestItems().get(TABLE).keys()).hasSize(1);
    }

    @Test
    @DisplayName("AP-020 SPK-012 a shard page cut by read size is continued in the same shard before moving on")
    void pageContinuesAfterReadSizeLimit() {
        when(client.query(any(QueryRequest.class)))
                .thenReturn(done(QueryResponse.builder().items(ticketProjection("A-1-1", 0)).count(1)
                        .lastEvaluatedKey(Map.of("PK", AttributeValue.fromS("TICKET#e-small#A-1-1"))).build()))
                .thenReturn(done(QueryResponse.builder().items(ticketProjection("A-1-2", 0)).count(1).build()));

        AvailableTicketPage page = inventory.findAvailablePage(SMALL, null, 5, null).block();
        assertThat(page.tickets()).extracting(AvailableTicket::ticketId).containsExactly("A-1-1", "A-1-2");
        assertThat(page.nextCursor()).isNull();
        ArgumentCaptor<QueryRequest> queries = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client, times(2)).query(queries.capture());
        assertThat(queries.getAllValues().get(1).limit()).isEqualTo(5);
        assertThat(queries.getAllValues().get(1).exclusiveStartKey())
                .containsEntry("PK", AttributeValue.fromS("TICKET#e-small#A-1-1"));
    }

    @Test
    @DisplayName("ADR-035 ADR-039 unprocessed keys of a consistent batch read that persist are a technical error")
    void unprocessedKeysPersisting() {
        when(client.batchGetItem(any(BatchGetItemRequest.class))).thenAnswer(invocation -> {
            BatchGetItemRequest request = invocation.getArgument(0);
            return done(BatchGetItemResponse.builder().unprocessedKeys(request.requestItems()).build());
        });

        StepVerifier.create(inventory.verify(SMALL, PersistencePortContract.initialTickets(SMALL)))
                .expectError(DynamoDbStoreException.class).verify();
    }

    @Test
    @DisplayName("AP-027 purging deletes every generated key in batches of 25")
    void purge() {
        when(client.batchWriteItem(any(BatchWriteItemRequest.class))).thenReturn(done(BatchWriteItemResponse.builder().build()));
        List<String> ids = PersistencePortContract.initialTickets(SMALL).stream().map(Ticket::ticketId).toList();

        inventory.purge(SMALL, ids).block();
        ArgumentCaptor<BatchWriteItemRequest> requests = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        verify(client, atLeastOnce()).batchWriteItem(requests.capture());
        assertThat(requests.getValue().requestItems().get(TABLE)).hasSize(25)
                .allSatisfy(write -> assertThat(write.deleteRequest().key().get("PK").s()).startsWith("TICKET#e-small#"));
    }

    private static String firstTicketOfShard(Event event, int shard) {
        return PersistencePortContract.initialTickets(event).stream()
                .filter(ticket -> ticket.state() == TicketState.AVAILABLE && ticket.section().equals("A"))
                .map(Ticket::ticketId)
                .filter(id -> ShardingPolicy.shard(id, event.availabilityShards()) == shard)
                .findFirst().orElseThrow();
    }

    private static Map<String, AttributeValue> ticketProjection(String ticketId, int shard) {
        String[] parts = ticketId.split("-");
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("ticketId", AttributeValue.fromS(ticketId));
        item.put("section", AttributeValue.fromS(parts[0]));
        item.put("row", AttributeValue.fromS(parts[1]));
        item.put("seat", AttributeValue.fromN(parts[2]));
        item.put("GSI2PK", AttributeValue.fromS("AVAIL#e-large#" + shard));
        return item;
    }
}
