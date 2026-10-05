package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static org.awaitility.Awaitility.await;

import com.nequi.ticketing.application.testdouble.MutableClock;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * ADR-038 / INC-005: the port contract battery against the DynamoDB adapter on DynamoDB Local 3.3.1, so
 * every operation of {@code ticketing.data-model.v2.md} §5 is exercised end to end against the emulator
 * with the same expectations as the in-memory double.
 */
class DynamoDbPersistenceContractIT extends PersistencePortContract {

    private static DynamoDbAsyncClient client;
    private static String table;
    private static DynamoDbPersistence persistence;

    @BeforeAll
    static void createTable() {
        client = DynamoDbLocalSupport.client();
        table = DynamoDbLocalSupport.createTable();
        persistence = DynamoDbPersistence.create(client, DynamoDbAdapterSettings.deployed(table), new MutableClock(NOW));
    }

    @Override
    protected PersistencePorts ports() {
        return PersistencePorts.of(persistence);
    }

    @Override
    protected Optional<TicketState> ticketState(String eventId, String ticketId) {
        return Optional.ofNullable(item(Keys.ticket(eventId, ticketId)).get(TicketItems.STATE))
                .map(value -> TicketState.valueOf(value.s()));
    }

    @Override
    protected Optional<String> activeLock(String customerId, String eventId) {
        return Optional.ofNullable(item(Keys.activeOrder(customerId, eventId)).get("orderId")).map(AttributeValue::s);
    }

    @Override
    protected List<AuditRecord> orderAudits(String orderId) {
        return collect(persistence.auditTrail().orderAudit(orderId));
    }

    @Override
    protected List<AuditRecord> eventAudits(String eventId) {
        return collect(persistence.auditTrail().eventAudit(eventId));
    }

    @Override
    protected void forceTicket(String eventId, String ticketId, TicketState state, String orderId) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":state", AttributeValue.fromS(state.name()));
        values.put(":order", AttributeValue.fromS(orderId));
        client.updateItem(builder -> builder.tableName(table)
                .key(Keys.meta(Keys.ticket(eventId, ticketId)))
                .updateExpression("SET #state = :state, orderId = :order REMOVE GSI2PK, GSI2SK")
                .expressionAttributeNames(Map.of("#state", "state"))
                .expressionAttributeValues(values)).join();
    }

    @Override
    protected void eventually(Runnable assertion) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(assertion::run);
    }

    private static Map<String, AttributeValue> item(String partitionKey) {
        return client.getItem(builder -> builder.tableName(table).key(Keys.meta(partitionKey)).consistentRead(true))
                .join().item();
    }
}
