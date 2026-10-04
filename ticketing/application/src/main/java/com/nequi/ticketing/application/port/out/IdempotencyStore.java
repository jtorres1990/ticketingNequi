package com.nequi.ticketing.application.port.out;

import reactor.core.publisher.Mono;

/** Outbound port: strongly consistent idempotency lookups (AP-009, AP-022; ADR-027). */
public interface IdempotencyStore {

    /** AP-009: empty when no record exists for the customer and key. */
    Mono<IdempotencyRecord> findPurchase(String customerId, String idempotencyKey);

    /** AP-022: empty when no record exists for the ADMIN subject and key. */
    Mono<IdempotencyRecord> findEventCreation(String adminSubject, String idempotencyKey);
}
