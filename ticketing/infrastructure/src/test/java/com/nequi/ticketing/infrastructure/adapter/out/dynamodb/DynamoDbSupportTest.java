package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.PersistencePortContract.DEFINITION;
import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.PersistencePortContract.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.domain.error.ValidationException;
import com.nequi.ticketing.domain.event.Event;
import com.nequi.ticketing.domain.event.InventoryLimits;
import com.nequi.ticketing.domain.event.ShardingPolicy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Unit tests of the adapter support: cursors, keys, expressions, settings, client factory and backoff (ADR-038). */
class DynamoDbSupportTest {

    private static final Event EVENT = Event.create("e-1", "Concert", "Arena", NOW.plus(Duration.ofDays(10)), 25, DEFINITION,
            NOW, InventoryLimits.DEPLOYED).enable(25);

    @Test
    @DisplayName("ADR-040 AC-048 availability cursors are opaque and rejected when malformed, of another Event, filter or shard")
    void availabilityCursors() {
        String cursor = Cursors.availability(EVENT, "A", 0, "A-1-3");
        assertThat(cursor).doesNotContain("A-1-3").hasSizeLessThan(1024);
        assertThat(Cursors.availabilityPosition(cursor, EVENT, "A")).isEqualTo(new Cursors.AvailabilityPosition(0, "A-1-3"));

        Event other = new Event("e-2", "Concert", "Arena", EVENT.startsAt(), 25, DEFINITION, 3, EVENT.provisioningStatus());
        String wrongShard = Cursors.availability(other, null, (ShardingPolicy.shard("A-1-3", 3) + 1) % 3, "A-1-3");
        List<String> invalid = List.of(
                "", "%%%", "x".repeat(1025),
                encode("2|A|e-1|A|0|A-1-3"),
                encode("1|E|e-1|A|0|A-1-3"),
                encode("1|A|e-9|A|0|A-1-3"),
                encode("1|A|e-1|A|0"),
                encode("1|A|e-1|A|x|A-1-3"),
                encode("1|A|e-1|A|7|A-1-3"),
                encode("1|A|e-1|A|-1|A-1-3"),
                encode("1|A|e-1|A|0|bad id"),
                encode("1|A|e-1|A|0|B-1-3"),
                encode("1|A|e-1|B|0|A-1-3"),
                Cursors.availability(EVENT, null, 0, "A-1-3"));
        for (String candidate : invalid) {
            assertThatThrownBy(() -> Cursors.availabilityPosition(candidate, EVENT, "A"))
                    .as(candidate).isInstanceOf(ValidationException.class);
        }
        assertThatThrownBy(() -> Cursors.availabilityPosition(wrongShard, other, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Cursors.availabilityPosition(encode("1|A|e-1|B|0|A-1-3"), EVENT, "B"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("ADR-040 AC-048 listing cursors carry the last GSI1 sort key and are validated strictly")
    void eventCursors() {
        String sortKey = Keys.eventLifecycleSort(NOW, "e-1");
        assertThat(Cursors.eventsPosition(Cursors.events(sortKey))).isEqualTo(sortKey);
        assertThat(Cursors.enabledEventsStartKey(sortKey)).containsEntry("PK", AttributeValue.fromS("EVENT#e-1"))
                .containsEntry("GSI1PK", AttributeValue.fromS("EVENTS#ENABLED"));
        for (String candidate : List.of(encode("1|A|" + sortKey), encode("1|E|nohash"), encode("1|E|2026-11-01#e-1"),
                encode("1|E|" + Keys.sortable(NOW) + "#bad|id"), encode("1|E|" + Keys.sortable(NOW) + "#"))) {
            assertThatThrownBy(() -> Cursors.eventsPosition(candidate)).as(candidate).isInstanceOf(ValidationException.class);
        }
    }

    @Test
    @DisplayName("ADR-022 keys, shards and sortable instants follow the data model formats")
    void keys() {
        assertThat(Keys.sortable(Instant.parse("2026-11-01T12:00:00Z"))).isEqualTo("2026-11-01T12:00:00.000000000Z");
        assertThat(Keys.parseSortable("2026-11-01T12:00:00.000000001Z")).isEqualTo(Instant.parse("2026-11-01T12:00:00.000000001Z"));
        assertThat(Keys.millis13(42)).isEqualTo("0000000000042");
        assertThatThrownBy(() -> Keys.millis13(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Keys.availabilitySort("VIP", "R1", 7)).isEqualTo("VIP#R1#0007");
        assertThat(TicketItems.availabilitySort("VIP-R1-12")).isEqualTo("VIP#R1#0012");
        assertThatThrownBy(() -> TicketItems.availabilitySort("VIP-R1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Keys.reservations("o-1")).isEqualTo("RESV#" + ShardingPolicy.shard("o-1", 8));
        assertThat(Keys.reversals("o-1")).isEqualTo("REVERSAL#" + ShardingPolicy.shard("o-1", 4));
        assertThat(Keys.pendingEnqueue("o-1")).isEqualTo("PENDQ#" + ShardingPolicy.shard("o-1", 8));
        assertThat(Keys.purchaseIdempotency("c", "k")).isEqualTo("IDEM#c#k");
        assertThat(Keys.eventCreationIdempotency("a", "k")).isEqualTo("IDEMEVT#a#k");
        assertThat(Keys.activeOrder("c", "e")).isEqualTo("ACTIVE#c#e");
        assertThat(IdempotencyItems.ttl(Instant.ofEpochSecond(10))).isEqualTo(10);
        assertThat(IdempotencyItems.ttl(Instant.ofEpochSecond(10, 1))).isEqualTo(11);
    }

    @Test
    @DisplayName("ADR-039 expressions alias every name and value and reject empty updates")
    void expressions() {
        Expression expression = new Expression().set("row", Attributes.s("1")).setIfPresent("state", null).remove("GSI2PK");
        expression.condition(expression.exists("PK"));
        assertThat(expression.updateExpression()).isEqualTo("SET #n0 = :v0 REMOVE #n1");
        assertThat(expression.conditionExpression()).isEqualTo("attribute_exists(#n2)");
        assertThat(expression.names()).containsEntry("#n0", "row").containsEntry("#n2", "PK");
        assertThat(new Expression().names()).isNull();
        assertThat(new Expression().values()).isNull();
        assertThat(new Expression().conditionExpression()).isNull();
        assertThatThrownBy(() -> new Expression().updateExpression()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("ADR-039 attribute readers fail on missing mandatory attributes and tolerate optional ones")
    void attributes() {
        Map<String, AttributeValue> item = Map.of("n", Attributes.n(3), "b", Attributes.bool(true), "l", Attributes.strings(List.of("x")));
        assertThat(Attributes.integer(item, "n")).isEqualTo(3);
        assertThat(Attributes.flag(item, "b")).isTrue();
        assertThat(Attributes.flag(item, "missing")).isFalse();
        assertThat(Attributes.stringList(item, "l")).containsExactly("x");
        assertThat(Attributes.stringList(item, "missing")).isEmpty();
        assertThat(Attributes.optionalInteger(item, "missing")).isNull();
        assertThat(Attributes.optionalMillis(item, "missing")).isNull();
        assertThatThrownBy(() -> Attributes.string(item, "missing")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Attributes.number(item, "missing")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("ADR-023 ADR-035 IV-004 the adapter settings hold the approved defaults and reject invalid values")
    void settings() {
        DynamoDbAdapterSettings deployed = DynamoDbAdapterSettings.deployed("ticketing");
        assertThat(deployed.transactionConflictRetries()).isEqualTo(2);
        assertThat(deployed.conflictBackoffBase()).isEqualTo(Duration.ofMillis(25));
        assertThat(deployed.conflictBackoffMaximum()).isEqualTo(Duration.ofMillis(200));
        assertThat(deployed.conflictJitter()).isEqualTo(0.5);
        assertThat(deployed.batchWriteSize()).isEqualTo(25);
        assertThat(deployed.batchWriteParallelism()).isEqualTo(4);
        assertThat(deployed.batchReadSize()).isEqualTo(100);
        assertThat(deployed.probeWaveSize()).isEqualTo(4);
        assertThat(deployed.countConcurrency()).isZero();
        assertThat(deployed.enabledEventCacheSize()).isEqualTo(10_000);
        assertThatThrownBy(() -> DynamoDbAdapterSettings.deployed(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(-1, 0.5, 25, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(2, 1.5, 25, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(2, 0.5, 26, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(2, 0.5, 25, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamoDbAdapterSettings("t", 2, Duration.ofMillis(300), Duration.ofMillis(200), 0.5,
                25, 4, 100, 4, 5, Duration.ofMillis(50), Duration.ofSeconds(1), 4, 0, 10_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamoDbConnectionSettings(null, " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SPK-007 IV-004 the client factory builds the asynchronous client with the endpoint and the standard retry mode")
    void clientFactory() {
        try (DynamoDbAsyncClient client = DynamoDbClientFactory.create(
                new DynamoDbConnectionSettings(URI.create("http://localhost:8000"), "us-east-1"),
                StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))) {
            assertThat(client.serviceClientConfiguration().endpointOverride()).contains(URI.create("http://localhost:8000"));
            assertThat(client.serviceClientConfiguration().overrideConfiguration().retryStrategy().orElseThrow().maxAttempts())
                    .isEqualTo(3);
        }
        try (DynamoDbAsyncClient client = DynamoDbClientFactory.create(new DynamoDbConnectionSettings(null, "us-east-1"))) {
            assertThat(client.serviceClientConfiguration().endpointOverride()).isEmpty();
        }
    }

    @Test
    @DisplayName("ADR-023 ADR-035 backoff grows exponentially, is capped and reduced by the jitter; batches are partitioned in order")
    void backoffAndPartition() {
        for (int retry = 0; retry < 5; retry++) {
            Duration delay = DynamoDbTable.jittered(Duration.ofMillis(25), Duration.ofMillis(200), retry, 0.5);
            long ceiling = Math.min(200, 25L << retry);
            assertThat(delay).isBetween(Duration.ofMillis(ceiling / 2), Duration.ofMillis(ceiling));
        }
        assertThat(DynamoDbTable.jittered(Duration.ofMillis(25), Duration.ofMillis(200), 40, 0)).isEqualTo(Duration.ofMillis(200));
        assertThat(DynamoDbTable.partition(List.of(1, 2, 3, 4, 5), 2)).containsExactly(List.of(1, 2), List.of(3, 4), List.of(5));
        RuntimeException cause = new RuntimeException("x");
        assertThat(DynamoDbTable.unwrap(new CompletionException(new CompletionException(cause)))).isSameAs(cause);
        assertThat(new DynamoDbStoreException("plain").getMessage()).isEqualTo("plain");
    }

    private static DynamoDbAdapterSettings settings(int retries, double jitter, int batchWrite, int parallelism) {
        return new DynamoDbAdapterSettings("t", retries, Duration.ofMillis(25), Duration.ofMillis(200), jitter, batchWrite,
                parallelism, 100, 4, 5, Duration.ofMillis(50), Duration.ofSeconds(1), 4, 0, 10_000);
    }

    private static String encode(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }
}
