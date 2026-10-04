package com.nequi.ticketing.domain.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.domain.error.InvalidStateTransitionException;
import com.nequi.ticketing.domain.event.InventoryDefinition.TicketSeed;
import com.nequi.ticketing.domain.ticket.Ticket.TicketState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TicketStateMachineTest {

    @Test
    @DisplayName("DS-001..DS-005 and ST-001..ST-005 execute the commercial lifecycle")
    void executesTicketLifecycle() {
        Ticket available = Ticket.provision("event", new TicketSeed("A", "1", 1, false));
        Ticket reserved = available.reserve("order");
        Ticket pending = reserved.startPayment("order");
        Ticket sold = pending.sell("order");

        assertThat(available.ticketId()).isEqualTo("A-1-1");
        assertThat(reserved.state()).isEqualTo(TicketState.RESERVED);
        assertThat(pending.state()).isEqualTo(TicketState.PENDING_CONFIRMATION);
        assertThat(sold.state().terminal()).isTrue();
        assertThatThrownBy(() -> sold.release("order")).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("ST-002/ST-005 release reserved or payment-pending tickets")
    void releasesBothReservableStates() {
        Ticket reserved = available().reserve("order");
        assertThat(reserved.release("order").state()).isEqualTo(TicketState.AVAILABLE);
        assertThat(reserved.startPayment("order").release("order").orderId()).isNull();
    }

    @Test
    @DisplayName("BR-006/BR-007 reject terminal and ownership-invalid transitions")
    void rejectsInvalidTransitions() {
        Ticket complimentary = Ticket.provision("event", new TicketSeed("A", "1", 2, true));
        Ticket reserved = available().reserve("order");

        assertThat(complimentary.state()).isEqualTo(TicketState.COMPLIMENTARY);
        assertThat(complimentary.state().terminal()).isTrue();
        assertThatThrownBy(() -> complimentary.reserve("order")).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> reserved.startPayment("other")).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> reserved.sell("order")).isInstanceOf(InvalidStateTransitionException.class);
    }

    private static Ticket available() {
        return Ticket.provision("event", new TicketSeed("A", "1", 1, false));
    }
}
