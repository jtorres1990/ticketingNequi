package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import static com.nequi.ticketing.infrastructure.adapter.out.dynamodb.Attributes.s;

import com.nequi.ticketing.domain.audit.AuditRecord;
import java.util.Objects;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * AP-017: operational read of the audit trail of an Order or an Event (ADR-031), strongly consistent and
 * in time order. There is no API operation for it (ADR-031); it serves operations and verification.
 */
public final class DynamoDbAuditTrail {

    private final DynamoDbTable table;

    DynamoDbAuditTrail(DynamoDbTable table) {
        this.table = Objects.requireNonNull(table, "table");
    }

    public Flux<AuditRecord> orderAudit(String orderId) {
        return Flux.defer(() -> audit(Keys.order(orderId)));
    }

    public Flux<AuditRecord> eventAudit(String eventId) {
        return Flux.defer(() -> audit(Keys.event(eventId)));
    }

    private Flux<AuditRecord> audit(String partition) {
        Expression keys = new Expression();
        QueryRequest request = QueryRequest.builder()
                .tableName(table.name())
                .consistentRead(true)
                .keyConditionExpression(keys.eq(Keys.PK, s(partition)) + " AND begins_with("
                        + keys.name(Keys.SK) + ", " + keys.value(s(Keys.AUDIT_PREFIX)) + ")")
                .expressionAttributeNames(keys.names())
                .expressionAttributeValues(keys.values())
                .build();
        return table.queryAll("AP-017 audit trail", request).map(AuditItems::record);
    }
}
