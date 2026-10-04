package com.nequi.ticketing.application.port.in;

import reactor.core.publisher.Mono;

/**
 * Inbound port "Get Event provisioning status" (CMP-003, API-006). The ADMIN authority is enforced by
 * the inbound adapter (ADR-032); a non-existent Event is {@code EVENT_NOT_FOUND}.
 */
public interface GetEventProvisioningStatusUseCase {

    Mono<ProvisioningStatusView> getProvisioningStatus(String eventId);
}
