package com.nequi.ticketing.infrastructure.adapter.in.web;

import com.nequi.ticketing.application.port.in.CreateEventUseCase;
import com.nequi.ticketing.application.port.in.GetEventAvailabilityUseCase;
import com.nequi.ticketing.application.port.in.GetEventProvisioningStatusUseCase;
import com.nequi.ticketing.application.port.in.GetOrderUseCase;
import com.nequi.ticketing.application.port.in.ListEventsUseCase;
import com.nequi.ticketing.application.port.in.StartPurchaseUseCase;
import java.util.Objects;

/** Inbound ports invoked by the HTTP API (ADR-034: inbound adapters only reach inbound ports). */
public record WebApiUseCases(
        CreateEventUseCase createEvent,
        ListEventsUseCase listEvents,
        GetEventProvisioningStatusUseCase provisioningStatus,
        GetEventAvailabilityUseCase availability,
        StartPurchaseUseCase startPurchase,
        GetOrderUseCase getOrder) {

    public WebApiUseCases {
        Objects.requireNonNull(createEvent, "createEvent");
        Objects.requireNonNull(listEvents, "listEvents");
        Objects.requireNonNull(provisioningStatus, "provisioningStatus");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(startPurchase, "startPurchase");
        Objects.requireNonNull(getOrder, "getOrder");
    }
}
