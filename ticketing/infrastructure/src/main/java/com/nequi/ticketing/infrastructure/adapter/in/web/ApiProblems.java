package com.nequi.ticketing.infrastructure.adapter.in.web;

import com.nequi.ticketing.application.error.DependencyUnavailableException;
import com.nequi.ticketing.application.error.RequestRejectedException;
import com.nequi.ticketing.domain.error.DomainErrorCode;
import com.nequi.ticketing.domain.error.DomainException;
import com.nequi.ticketing.domain.error.ValidationException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * CMP-016 error translation: the single place where every result that is not a success becomes an HTTP
 * response (ADR-035). Responses are Problem Details ({@code application/problem+json}) with {@code type},
 * {@code title}, {@code status}, a functional {@code detail}, the stable {@code code} and the {@code traceId};
 * {@code VALIDATION_ERROR} adds {@code errors} and the purchase conflicts add {@code unavailableTicketIds} /
 * {@code unknownTicketIds} (OpenAPI v2). No stack trace, class name, SDK message or internal identifier is
 * ever written. {@code SERVICE_UNAVAILABLE} and {@code RATE_LIMITED} carry {@code Retry-After} in whole
 * seconds (at least 1); {@code UNAUTHENTICATED} carries {@code WWW-Authenticate: Bearer} without detail.
 */
public final class ApiProblems {

    static final URI TYPE = URI.create("about:blank");
    static final String DEFAULT_FIELD = "request";

    private final WebApiSettings settings;
    private final WebApiEvents events;
    private final ServerResponse.Context context;

    public ApiProblems(WebApiSettings settings, WebApiEvents events) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.events = Objects.requireNonNull(events, "events");
        this.context = new WriterContext(WebApiCodecs.strategies(settings));
    }

    /** HTTP status of each stable code; exhaustive over the closed set of domain codes (ADR-035). */
    static HttpStatus status(DomainErrorCode code) {
        return switch (code) {
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case EVENT_NOT_FOUND, ORDER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TICKETS_UNAVAILABLE, EVENT_NOT_ON_SALE, ACTIVE_ORDER_EXISTS -> HttpStatus.CONFLICT;
            case UNKNOWN_TICKETS, IDEMPOTENCY_KEY_REUSED -> HttpStatus.UNPROCESSABLE_CONTENT;
            case PAYLOAD_TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR, INVALID_STATE_TRANSITION, INCONSISTENT_TICKET_STATE -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /** Code written in the response: internal invariant violations are never exposed as such. */
    static String exposedCode(DomainErrorCode code) {
        return switch (code) {
            case INVALID_STATE_TRANSITION, INCONSISTENT_TICKET_STATE -> DomainErrorCode.INTERNAL_ERROR.name();
            default -> code.name();
        };
    }

    static String detail(DomainErrorCode code) {
        return switch (code) {
            case VALIDATION_ERROR -> "The request is invalid.";
            case UNAUTHENTICATED -> "A valid access token is required.";
            case FORBIDDEN -> "The authenticated identity is not allowed to perform this operation.";
            case EVENT_NOT_FOUND -> "The event does not exist.";
            case ORDER_NOT_FOUND -> "The order does not exist.";
            case TICKETS_UNAVAILABLE -> "One or more requested tickets are not available.";
            case EVENT_NOT_ON_SALE -> "The event is no longer on sale.";
            case ACTIVE_ORDER_EXISTS -> "The customer already has an active order for this event.";
            case UNKNOWN_TICKETS -> "One or more requested tickets do not exist in the event.";
            case IDEMPOTENCY_KEY_REUSED -> "The idempotency key was already used for a different request.";
            case PAYLOAD_TOO_LARGE -> "The request body exceeds the allowed size.";
            case RATE_LIMITED -> "Too many requests for this identity.";
            case SERVICE_UNAVAILABLE -> "The service is temporarily unavailable; the request may be repeated.";
            case INTERNAL_ERROR, INVALID_STATE_TRANSITION, INCONSISTENT_TICKET_STATE -> "The request could not be processed.";
        };
    }

    /** Translation for the handlers of the router. */
    Mono<ServerResponse> response(Throwable error, ServerWebExchange exchange) {
        String traceId = TraceIds.of(exchange);
        Translation translation = translate(error, traceId);
        ProblemDetail problem = ProblemDetail.forStatus(translation.status());
        problem.setType(TYPE);
        problem.setTitle(translation.status().getReasonPhrase());
        problem.setDetail(detail(translation.code()));
        problem.setProperty("code", exposedCode(translation.code()));
        problem.setProperty("traceId", traceId);
        translation.extensions().forEach(problem::setProperty);
        return ServerResponse.status(translation.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .headers(headers -> {
                    if (translation.retryAfter() != null) {
                        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(translation.retryAfter())));
                    }
                    if (translation.code() == DomainErrorCode.UNAUTHENTICATED) {
                        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                    }
                })
                .bodyValue(problem);
    }

    /** Translation for web filters and the security handlers, which write on the exchange directly. */
    public Mono<Void> write(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        return response(error, exchange).flatMap(response -> response.writeTo(exchange, context));
    }

    Translation translate(Throwable error, String traceId) {
        return switch (error) {
            case ApiRejection rejection -> new Translation(
                    rejection.code(), rejection.retryAfter().orElse(null), validationErrors(rejection.errors()));
            case RequestRejectedException rejected -> new Translation(
                    rejected.code(), rejected.retryAfter().orElse(null), ticketExtensions(rejected));
            case ValidationException invalid -> new Translation(
                    invalid.code(),
                    null,
                    validationErrors(List.of(new ApiRejection.FieldError(
                            invalid.field().orElse(DEFAULT_FIELD), invalid.getMessage()))));
            case DomainException domain -> {
                events.unclassifiedFailure(traceId, domain);
                yield new Translation(domain.code(), null, Map.of());
            }
            case DependencyUnavailableException unavailable ->
                    new Translation(DomainErrorCode.SERVICE_UNAVAILABLE, settings.unavailableRetryAfter(), Map.of());
            case DataBufferLimitException tooLarge -> new Translation(DomainErrorCode.PAYLOAD_TOO_LARGE, null, Map.of());
            case AuthenticationException unauthenticated ->
                    new Translation(DomainErrorCode.UNAUTHENTICATED, null, Map.of());
            case AccessDeniedException denied -> new Translation(DomainErrorCode.FORBIDDEN, null, Map.of());
            default -> {
                events.unclassifiedFailure(traceId, error);
                yield new Translation(DomainErrorCode.INTERNAL_ERROR, null, Map.of());
            }
        };
    }

    static long retryAfterSeconds(Duration retryAfter) {
        long millis = Math.max(0, retryAfter.toMillis());
        return Math.max(1, Math.ceilDiv(millis, 1_000L));
    }

    private static Map<String, Object> validationErrors(List<ApiRejection.FieldError> errors) {
        if (errors.isEmpty()) {
            return Map.of();
        }
        List<Map<String, String>> entries = errors.stream()
                .map(error -> {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("field", error.field());
                    entry.put("reason", error.reason());
                    return entry;
                })
                .toList();
        return Map.of("errors", entries);
    }

    private static Map<String, Object> ticketExtensions(RequestRejectedException rejected) {
        return switch (rejected.code()) {
            case TICKETS_UNAVAILABLE -> Map.of("unavailableTicketIds", rejected.ticketIds());
            case UNKNOWN_TICKETS -> Map.of("unknownTicketIds", rejected.ticketIds());
            default -> Map.of();
        };
    }

    /** Writers used when a problem is written on the exchange outside the router; no views are rendered. */
    record WriterContext(HandlerStrategies strategies) implements ServerResponse.Context {

        @Override
        public List<HttpMessageWriter<?>> messageWriters() {
            return strategies.messageWriters();
        }

        @Override
        public List<ViewResolver> viewResolvers() {
            return List.of();
        }
    }

    record Translation(DomainErrorCode code, Duration retryAfter, Map<String, Object> extensions) {

        Translation {
            Objects.requireNonNull(code, "code");
            extensions = Map.copyOf(extensions);
        }

        HttpStatus status() {
            return ApiProblems.status(code);
        }
    }
}
