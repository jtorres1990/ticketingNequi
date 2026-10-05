package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import com.nequi.ticketing.application.testdouble.InMemoryTicketingStore;
import com.nequi.ticketing.domain.audit.AuditRecord;
import com.nequi.ticketing.domain.ticket.Ticket;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ADR-038: the port contract battery against the in-memory double published by {@code application}, so
 * that the unit tests of the use cases and the DynamoDB adapter are held to the same behaviour.
 */
class InMemoryPersistenceContractTest extends PersistencePortContract {

    private InMemoryTicketingStore store;

    @Override
    protected PersistencePorts ports() {
        store = new InMemoryTicketingStore();
        return PersistencePorts.of(store);
    }

    @Override
    protected Optional<TicketState> ticketState(String eventId, String ticketId) {
        return Optional.ofNullable(store.ticket(eventId, ticketId)).map(Ticket::state);
    }

    @Override
    protected Optional<String> activeLock(String customerId, String eventId) {
        return store.activeLock(customerId, eventId);
    }

    @Override
    protected List<AuditRecord> orderAudits(String orderId) {
        return store.audits().stream().filter(audit -> orderId.equals(audit.orderId())).toList();
    }

    @Override
    protected List<AuditRecord> eventAudits(String eventId) {
        return store.audits().stream()
                .filter(audit -> audit.orderId() == null && Objects.equals(eventId, audit.eventId()))
                .toList();
    }

    @Override
    protected void forceTicket(String eventId, String ticketId, TicketState state, String orderId) {
        store.setTicket(eventId, ticketId, state, orderId);
    }
}
