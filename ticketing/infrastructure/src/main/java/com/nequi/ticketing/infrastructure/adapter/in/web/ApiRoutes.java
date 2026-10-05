package com.nequi.ticketing.infrastructure.adapter.in.web;

import com.nequi.ticketing.application.error.RequestRejectedException;
import com.nequi.ticketing.application.port.in.AvailabilityQuery;
import com.nequi.ticketing.application.port.in.CreateEventCommand;
import com.nequi.ticketing.application.port.in.EventCreationResult;
import com.nequi.ticketing.application.port.in.GetOrderQuery;
import com.nequi.ticketing.application.port.in.ListEventsQuery;
import com.nequi.ticketing.application.port.in.PurchaseResult;
import com.nequi.ticketing.application.port.in.StartPurchaseCommand;
import com.nequi.ticketing.infrastructure.adapter.in.web.ApiRequests.Errors;
import java.net.URI;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * CMP-001 HTTP API entrypoint: API-001 to API-006 under the base path ({@code /api/v1}) as functional WebFlux
 * endpoints returning {@code Mono} (TC-003, TC-008, NFR-003). Each handler validates the syntax of the request,
 * takes the identity from the authenticated token (the owner is the subject, never a value sent by the client;
 * VAL-011, ADR-032), invokes one inbound port and writes the response of OpenAPI v2:
 * <ul>
 *   <li>API-001: 202 with {@code Location} of API-006, or 200 with {@code Idempotency-Replayed: true} and
 *       {@code Location} on a replay (ADR-024, ADR-027);</li>
 *   <li>API-004: 201 with {@code Location} of the Order ({@code CREATED}, or {@code FAILED} after a definitive
 *       enqueue failure), or 200 with {@code Idempotency-Replayed: true} on a replay (ADR-026, ADR-027); the
 *       per-subject rate limiter runs first (CMP-017);</li>
 *   <li>API-005: a malformed Order identifier gets the same {@code ORDER_NOT_FOUND} as a non-existent or foreign
 *       Order (BR-023, AC-027); API-003 and API-006 treat a malformed Event identifier as a non-existent Event.</li>
 * </ul>
 * Every failure, whatever its origin, goes through {@link ApiProblems} (CMP-016).
 */
public final class ApiRoutes {

    static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private final WebApiSettings settings;
    private final WebApiUseCases useCases;
    private final ApiProblems problems;
    private final SubjectRateLimiter rateLimiter;
    private final WebApiEvents events;

    private ApiRoutes(WebApiSettings settings, WebApiUseCases useCases, ApiProblems problems,
                      SubjectRateLimiter rateLimiter, WebApiEvents events) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.problems = Objects.requireNonNull(problems, "problems");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.events = Objects.requireNonNull(events, "events");
    }

    public static RouterFunction<ServerResponse> routes(WebApiSettings settings, WebApiUseCases useCases,
                                                        ApiProblems problems, SubjectRateLimiter rateLimiter,
                                                        WebApiEvents events) {
        return new ApiRoutes(settings, useCases, problems, rateLimiter, events).build();
    }

    private RouterFunction<ServerResponse> build() {
        RouterFunction<ServerResponse> purchase = RouterFunctions.route()
                .POST(settings.path("/orders"), this::startPurchase)
                .filter(rateLimit())
                .build();
        RouterFunction<ServerResponse> others = RouterFunctions.route()
                .POST(settings.path("/events"), this::createEvent)
                .GET(settings.path("/events"), this::listEvents)
                .GET(settings.path("/events/{eventId}/provisioning"), this::provisioningStatus)
                .GET(settings.path("/events/{eventId}/availability"), this::availability)
                .GET(settings.path("/orders/{orderId}"), this::getOrder)
                .build();
        return purchase.and(others)
                .filter((request, next) -> next.handle(request)
                        .onErrorResume(error -> problems.response(error, request.exchange())));
    }

    /** API-001: the {@code Idempotency-Key} is bound to the subject of the authenticated ADMIN (ADR-032). */
    private Mono<ServerResponse> createEvent(ServerRequest request) {
        return subject(request).flatMap(admin -> {
            Errors errors = new Errors();
            String key = ApiRequests.idempotencyKey(request, errors);
            return ApiRequests.readJsonBody(request, settings.maximumBodyBytes(), errors)
                    .map(body -> ApiRequests.createEvent(body, errors))
                    .flatMap(input -> {
                        errors.throwIfAny();
                        return useCases.createEvent().createEvent(new CreateEventCommand(
                                admin, key, input.name(), input.venue(), input.startsAt(), input.capacity(),
                                input.inventory(), traceId(request)));
                    });
        }).flatMap(this::eventCreated);
    }

    private Mono<ServerResponse> eventCreated(EventCreationResult result) {
        URI location = URI.create(settings.path("/events/" + result.status().eventId() + "/provisioning"));
        ServerResponse.BodyBuilder response = result.replayed()
                ? ServerResponse.ok().header(REPLAYED_HEADER, "true")
                : ServerResponse.status(HttpStatus.ACCEPTED);
        return response.location(location)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ApiResponses.provisioningStatus(result.status()));
    }

    /** API-002. */
    private Mono<ServerResponse> listEvents(ServerRequest request) {
        return Mono.defer(() -> {
            Errors errors = new Errors();
            Integer limit = ApiRequests.queryInteger(request, "limit", errors);
            String cursor = ApiRequests.eventCursor(request, errors);
            errors.throwIfAny();
            return useCases.listEvents().listEvents(new ListEventsQuery(limit, cursor));
        }).flatMap(page -> json(ApiResponses.eventPage(page)));
    }

    /** API-006 (ADMIN only, enforced by the security chain). */
    private Mono<ServerResponse> provisioningStatus(ServerRequest request) {
        return Mono.defer(() -> {
            String eventId = request.pathVariable("eventId");
            if (!ApiRequests.isUuid(eventId)) {
                return Mono.error(RequestRejectedException.eventNotFound());
            }
            return useCases.provisioningStatus().getProvisioningStatus(eventId);
        }).flatMap(status -> json(ApiResponses.provisioningStatus(status)));
    }

    /** API-003. */
    private Mono<ServerResponse> availability(ServerRequest request) {
        return Mono.defer(() -> {
            String eventId = request.pathVariable("eventId");
            if (!ApiRequests.isUuid(eventId)) {
                return Mono.error(RequestRejectedException.eventNotFound());
            }
            Errors errors = new Errors();
            String section = ApiRequests.section(request, errors);
            Integer pageSize = ApiRequests.queryInteger(request, "pageSize", errors);
            String cursor = ApiRequests.availabilityCursor(request, errors);
            errors.throwIfAny();
            return useCases.availability().getAvailability(new AvailabilityQuery(eventId, section, pageSize, cursor));
        }).flatMap(view -> json(ApiResponses.availability(view)));
    }

    /** API-004: the owner and the idempotency scope are the subject of the authenticated CUSTOMER. */
    private Mono<ServerResponse> startPurchase(ServerRequest request) {
        return subject(request).flatMap(customer -> {
            Errors errors = new Errors();
            String key = ApiRequests.idempotencyKey(request, errors);
            return ApiRequests.readJsonBody(request, settings.maximumBodyBytes(), errors)
                    .map(body -> ApiRequests.purchase(body, errors))
                    .flatMap(input -> {
                        errors.throwIfAny();
                        return useCases.startPurchase().startPurchase(new StartPurchaseCommand(
                                customer, input.eventId(), input.ticketIds(), key, traceId(request)));
                    });
        }).flatMap(this::purchaseResponse);
    }

    private Mono<ServerResponse> purchaseResponse(PurchaseResult result) {
        if (result.replayed()) {
            return ServerResponse.ok()
                    .header(REPLAYED_HEADER, "true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(ApiResponses.order(result.order()));
        }
        return ServerResponse.created(URI.create(settings.path("/orders/" + result.order().orderId())))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ApiResponses.order(result.order()));
    }

    /** API-005: inexistent, foreign and malformed identifiers produce the same response (BR-023). */
    private Mono<ServerResponse> getOrder(ServerRequest request) {
        return subject(request).flatMap(customer -> {
            String orderId = request.pathVariable("orderId");
            if (!ApiRequests.isUuid(orderId)) {
                return Mono.error(RequestRejectedException.orderNotFound());
            }
            return useCases.getOrder().getOrder(new GetOrderQuery(customer, orderId));
        }).flatMap(order -> json(ApiResponses.order(order)));
    }

    /** CMP-017: per-subject limiter of API-004, immediate 429 with {@code Retry-After} (ADR-032, IV-005). */
    private HandlerFilterFunction<ServerResponse, ServerResponse> rateLimit() {
        return (request, next) -> subject(request).flatMap(subject -> rateLimiter.tryAcquire(subject)
                .map(wait -> {
                    events.rateLimited(traceId(request));
                    return Mono.<ServerResponse>error(ApiRejection.rateLimited(wait));
                })
                .orElseGet(() -> next.handle(request)));
    }

    private static Mono<String> subject(ServerRequest request) {
        return request.principal()
                .filter(Authentication.class::isInstance)
                .map(principal -> ((Authentication) principal).getName())
                .switchIfEmpty(Mono.error(ApiRejection::unauthenticated));
    }

    private static String traceId(ServerRequest request) {
        return TraceIds.of(request.exchange());
    }

    private static Mono<ServerResponse> json(Object body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }
}
