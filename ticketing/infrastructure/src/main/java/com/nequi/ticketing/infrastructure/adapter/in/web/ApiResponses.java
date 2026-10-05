package com.nequi.ticketing.infrastructure.adapter.in.web;

import com.nequi.ticketing.application.port.in.AvailabilityView;
import com.nequi.ticketing.application.port.in.AvailableTicketView;
import com.nequi.ticketing.application.port.in.EventSummaryPage;
import com.nequi.ticketing.application.port.in.EventSummaryView;
import com.nequi.ticketing.application.port.in.OrderView;
import com.nequi.ticketing.application.port.in.ProvisioningStatusView;
import com.nequi.ticketing.domain.order.Order.FunctionalCause;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON bodies of the successful responses, with exactly the members of the schemas of
 * {@code ticketing.openapi.v2.yaml}: {@code EventProvisioningStatus}, {@code EventPage}, {@code EventAvailability}
 * and {@code Order}. Instants are ISO-8601 in UTC. Nullable members are written as {@code null}. The Order body
 * exposes no technical attribute (enqueue marker, lease, reversal mark, quarantine; ADR-025) and its
 * {@code failureCause.message} is the functional text produced here from the code (FR-009, AC-006).
 */
final class ApiResponses {

    private ApiResponses() {
    }

    static Map<String, Object> provisioningStatus(ProvisioningStatusView view) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", view.eventId());
        body.put("provisioningStatus", view.provisioningStatus().name());
        body.put("capacity", view.capacity());
        body.put("complimentaryTickets", view.complimentaryTickets());
        body.put("provisionedTickets", view.provisionedTickets());
        body.put("createdAt", instant(view.createdAt()));
        body.put("enabledAt", instant(view.enabledAt()));
        body.put("failedAt", instant(view.failedAt()));
        body.put("failureCause", view.failureCause() == null ? null : view.failureCause().name());
        return body;
    }

    static Map<String, Object> eventPage(EventSummaryPage page) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", page.items().stream().map(ApiResponses::eventSummary).toList());
        body.put("nextCursor", page.nextCursor());
        return body;
    }

    static Map<String, Object> availability(AvailabilityView view) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", view.eventId());
        body.put("name", view.name());
        body.put("venue", view.venue());
        body.put("startsAt", instant(view.startsAt()));
        body.put("capacity", view.capacity());
        body.put("sections", view.sections());
        body.put("availableCount", view.availableCount());
        body.put("generatedAt", instant(view.generatedAt()));
        body.put("informative", view.informative());
        if (view.sectionFilter() != null) {
            body.put("sectionFilter", view.sectionFilter());
        }
        body.put("items", view.items().stream().map(ApiResponses::availableTicket).toList());
        body.put("nextCursor", view.nextCursor());
        return body;
    }

    static Map<String, Object> order(OrderView view) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", view.orderId());
        body.put("eventId", view.eventId());
        body.put("ticketIds", view.ticketIds());
        body.put("status", view.status().name());
        if (view.failureCause() != null) {
            body.put("failureCause", failureCause(view.failureCause()));
        }
        body.put("reservationExpiresAt", instant(view.reservationExpiresAt()));
        body.put("createdAt", instant(view.createdAt()));
        body.put("updatedAt", instant(view.updatedAt()));
        return body;
    }

    /** Functional message of each cause of a non-confirmed Order (ADR-035), without technical detail. */
    static String message(FunctionalCause cause) {
        return switch (cause) {
            case PAYMENT_DECLINED -> "The payment was declined.";
            case PROCESSING_UNAVAILABLE -> "The order could not be queued for processing.";
            case PROCESSING_FAILED -> "The order could not be processed.";
            case RESERVATION_EXPIRED -> "The reservation expired before the purchase was confirmed.";
        };
    }

    private static Map<String, Object> failureCause(FunctionalCause cause) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", cause.name());
        body.put("message", message(cause));
        return body;
    }

    private static Map<String, Object> eventSummary(EventSummaryView view) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", view.eventId());
        body.put("name", view.name());
        body.put("venue", view.venue());
        body.put("startsAt", instant(view.startsAt()));
        body.put("capacity", view.capacity());
        body.put("soldOut", view.soldOut());
        return body;
    }

    private static Map<String, Object> availableTicket(AvailableTicketView view) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", view.ticketId());
        body.put("section", view.section());
        body.put("row", view.row());
        body.put("seat", view.seat());
        return body;
    }

    private static String instant(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
