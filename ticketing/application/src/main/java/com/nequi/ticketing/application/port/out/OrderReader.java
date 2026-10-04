package com.nequi.ticketing.application.port.out;

import java.time.Instant;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Outbound port: Order reads (ADR-034). */
public interface OrderReader {

    /** AP-010: strongly consistent read of the Order item alone; empty when it does not exist. */
    Mono<OrderRecord> findById(String orderId);

    /**
     * AP-016: identifiers of the Orders of one {@code RESV#<shard>} range of {@code GSI3} whose
     * {@code expiresAt <= now}. Eventually consistent: the guards of AP-015 give correctness (ADR-028).
     */
    Flux<String> findDueReservations(int shard, Instant now);

    /**
     * AP-028: identifiers of the Orders of one {@code PENDQ#<shard>} range of {@code GSI4} created before
     * {@code createdBefore} (CREATED, without {@code enqueuedAt}, without quarantine). Eventually consistent.
     */
    Flux<String> findPendingEnqueue(int shard, Instant createdBefore);

    /**
     * AP-029: identifiers of the Orders of one {@code REVERSAL#<shard>} range of {@code GSI3} whose next
     * reversal attempt is due at {@code now}. Eventually consistent.
     */
    Flux<String> findDueReversals(int shard, Instant now);
}
