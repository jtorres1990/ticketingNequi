package com.nequi.ticketing.infrastructure.adapter.in.web;

import static org.mockito.Mockito.mock;

import com.nequi.ticketing.application.port.in.CreateEventUseCase;
import com.nequi.ticketing.application.port.in.GetEventAvailabilityUseCase;
import com.nequi.ticketing.application.port.in.GetEventProvisioningStatusUseCase;
import com.nequi.ticketing.application.port.in.GetOrderUseCase;
import com.nequi.ticketing.application.port.in.ListEventsUseCase;
import com.nequi.ticketing.application.port.in.OrderView;
import com.nequi.ticketing.application.port.in.ProvisioningStatusView;
import com.nequi.ticketing.application.port.in.StartPurchaseUseCase;
import com.nequi.ticketing.domain.event.Event.ProvisioningStatus;
import com.nequi.ticketing.domain.order.Order.OrderStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Mocked inbound ports for web layer tests that only exercise the adapter (security, guards, translation). */
final class WebApiMocks {

    static final String EVENT_ID = "e0000000-0000-4000-8000-000000000001";
    static final String ORDER_ID = "00000000-0000-4000-8000-000000000001";
    static final String EVENT_BODY = WebApiFlowTest.EVENT_BODY;

    final CreateEventUseCase createEvent = mock(CreateEventUseCase.class);
    final ListEventsUseCase listEvents = mock(ListEventsUseCase.class);
    final GetEventProvisioningStatusUseCase provisioningStatus = mock(GetEventProvisioningStatusUseCase.class);
    final GetEventAvailabilityUseCase availability = mock(GetEventAvailabilityUseCase.class);
    final StartPurchaseUseCase startPurchase = mock(StartPurchaseUseCase.class);
    final GetOrderUseCase getOrder = mock(GetOrderUseCase.class);

    WebApiUseCases useCases() {
        return new WebApiUseCases(createEvent, listEvents, provisioningStatus, availability, startPurchase, getOrder);
    }

    static OrderView order(String customerOrderId) {
        Instant now = WebApiTestServer.NOW;
        return new OrderView(customerOrderId, EVENT_ID, List.of("A-1-1"), OrderStatus.CREATED, null,
                now.plusSeconds(600), now, now);
    }

    static ProvisioningStatusView provisioning() {
        return new ProvisioningStatusView(EVENT_ID, ProvisioningStatus.PROVISIONING, 25, 1, 0,
                WebApiTestServer.NOW, null, null, null);
    }

    static String key(int sequence) {
        return "web-mock-key-%08d".formatted(sequence);
    }

    static WebTestClient.RequestHeadersSpec<?> purchase(WebApiTestServer server, TestTokenIssuer.Identity who, String key) {
        return server.client.post().uri("/api/v1/orders")
                .headers(headers -> {
                    headers.setBearerAuth(server.token(who));
                    headers.set("Idempotency-Key", key);
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(WebApiFlowTest.purchaseBody(EVENT_ID, "A-1-1"));
    }
}
