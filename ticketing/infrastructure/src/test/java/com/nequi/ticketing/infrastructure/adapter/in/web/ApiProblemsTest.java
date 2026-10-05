package com.nequi.ticketing.infrastructure.adapter.in.web;

import static com.nequi.ticketing.infrastructure.adapter.in.web.WebApiFlowTest.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.application.error.DependencyUnavailableException;
import com.nequi.ticketing.application.error.RequestRejectedException;
import com.nequi.ticketing.application.port.in.CreateEventCommand;
import com.nequi.ticketing.application.port.in.StartPurchaseCommand;
import com.nequi.ticketing.domain.error.DomainErrorCode;
import com.nequi.ticketing.domain.error.InvalidStateTransitionException;
import com.nequi.ticketing.domain.error.ValidationException;
import com.nequi.ticketing.infrastructure.adapter.in.web.WebApiTestServer.Reply;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * CMP-016 (TC-009, ERR-002, ERR-006, ERR-009, ADR-035): exhaustive translation of every domain code and of
 * every technical failure into Problem Details, the precedence-relevant codes of API-004 over HTTP and the
 * absence of technical detail. Every HTTP response is validated against OpenAPI v2.
 */
class ApiProblemsTest {

    private static final Map<DomainErrorCode, Integer> STATUS = new EnumMap<>(Map.ofEntries(
            Map.entry(DomainErrorCode.VALIDATION_ERROR, 400),
            Map.entry(DomainErrorCode.UNAUTHENTICATED, 401),
            Map.entry(DomainErrorCode.FORBIDDEN, 403),
            Map.entry(DomainErrorCode.EVENT_NOT_FOUND, 404),
            Map.entry(DomainErrorCode.ORDER_NOT_FOUND, 404),
            Map.entry(DomainErrorCode.TICKETS_UNAVAILABLE, 409),
            Map.entry(DomainErrorCode.EVENT_NOT_ON_SALE, 409),
            Map.entry(DomainErrorCode.ACTIVE_ORDER_EXISTS, 409),
            Map.entry(DomainErrorCode.UNKNOWN_TICKETS, 422),
            Map.entry(DomainErrorCode.IDEMPOTENCY_KEY_REUSED, 422),
            Map.entry(DomainErrorCode.PAYLOAD_TOO_LARGE, 413),
            Map.entry(DomainErrorCode.RATE_LIMITED, 429),
            Map.entry(DomainErrorCode.SERVICE_UNAVAILABLE, 503),
            Map.entry(DomainErrorCode.INTERNAL_ERROR, 500),
            Map.entry(DomainErrorCode.INVALID_STATE_TRANSITION, 500),
            Map.entry(DomainErrorCode.INCONSISTENT_TICKET_STATE, 500)));

    /** {@code Problem.code} enumeration of OpenAPI v2. */
    private static final Set<String> CONTRACT_CODES = Set.of(
            "VALIDATION_ERROR", "UNAUTHENTICATED", "FORBIDDEN", "EVENT_NOT_FOUND", "ORDER_NOT_FOUND",
            "TICKETS_UNAVAILABLE", "EVENT_NOT_ON_SALE", "ACTIVE_ORDER_EXISTS", "UNKNOWN_TICKETS",
            "IDEMPOTENCY_KEY_REUSED", "PAYLOAD_TOO_LARGE", "RATE_LIMITED", "SERVICE_UNAVAILABLE", "INTERNAL_ERROR");

    private static TestTokenIssuer issuer;

    private WebApiMocks mocks;
    private WebApiTestServer server;

    @BeforeAll
    static void startIssuer() {
        issuer = TestTokenIssuer.start(WebApiTestServer.NOW);
    }

    @AfterAll
    static void stopIssuer() {
        issuer.close();
    }

    @BeforeEach
    void startServer() {
        mocks = new WebApiMocks();
        server = WebApiTestServer.start(issuer, mocks.useCases());
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @ParameterizedTest(name = "ADR-035 {0}")
    @EnumSource(DomainErrorCode.class)
    @DisplayName("ADR-035 exhaustive mapping: every domain code has its HTTP status and a contract code")
    void everyDomainCodeIsMapped(DomainErrorCode code) {
        assertThat(ApiProblems.status(code).value()).isEqualTo(STATUS.get(code));
        assertThat(CONTRACT_CODES).contains(ApiProblems.exposedCode(code));
        assertThat(ApiProblems.detail(code)).isNotBlank().doesNotContain(code.name());
    }

    @Test
    @DisplayName("ADR-035 Retry-After is expressed in whole seconds, rounded up, at least 1")
    void retryAfterSeconds() {
        assertThat(ApiProblems.retryAfterSeconds(Duration.ZERO)).isEqualTo(1);
        assertThat(ApiProblems.retryAfterSeconds(Duration.ofMillis(1))).isEqualTo(1);
        assertThat(ApiProblems.retryAfterSeconds(Duration.ofMillis(1_000))).isEqualTo(1);
        assertThat(ApiProblems.retryAfterSeconds(Duration.ofMillis(1_001))).isEqualTo(2);
        assertThat(ApiProblems.retryAfterSeconds(Duration.ofSeconds(10))).isEqualTo(10);
        assertThat(ApiProblems.retryAfterSeconds(Duration.ofSeconds(-3))).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-035 technical failures: unavailability 503, limits 413, security 401/403, invariants and the unknown 500")
    void technicalFailures() {
        WebApiTestServer.RecordingEvents events = new WebApiTestServer.RecordingEvents();
        ApiProblems problems = new ApiProblems(WebApiSettings.DEPLOYED, events);
        DependencyUnavailableException unavailable = new DependencyUnavailableException("DynamoDB throttled", new RuntimeException()) {
        };
        InvalidStateTransitionException invariant = new InvalidStateTransitionException("order is terminal");
        IllegalStateException unknown = new IllegalStateException("boom");

        assertThat(problems.translate(unavailable, "t").code()).isEqualTo(DomainErrorCode.SERVICE_UNAVAILABLE);
        assertThat(problems.translate(unavailable, "t").retryAfter()).isEqualTo(Duration.ofSeconds(1));
        assertThat(problems.translate(new DataBufferLimitException("too big"), "t").code())
                .isEqualTo(DomainErrorCode.PAYLOAD_TOO_LARGE);
        assertThat(problems.translate(new BadCredentialsException("bad"), "t").code())
                .isEqualTo(DomainErrorCode.UNAUTHENTICATED);
        assertThat(problems.translate(new AccessDeniedException("denied"), "t").code())
                .isEqualTo(DomainErrorCode.FORBIDDEN);
        assertThat(problems.translate(invariant, "t").status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(problems.translate(unknown, "t").code()).isEqualTo(DomainErrorCode.INTERNAL_ERROR);
        assertThat(problems.translate(new ValidationException("no field"), "t").extensions())
                .containsKey("errors");
        assertThat(events.unclassified).containsExactly(invariant, unknown);
    }

    @Test
    @DisplayName("FR-009 AC-006 ADR-035 every functional cause of a non-confirmed Order has a functional message")
    void functionalCauseMessages() {
        for (com.nequi.ticketing.domain.order.Order.FunctionalCause cause
                : com.nequi.ticketing.domain.order.Order.FunctionalCause.values()) {
            assertThat(ApiResponses.message(cause)).isNotBlank().endsWith(".").doesNotContain("_");
        }
        assertThat(new ApiProblems.WriterContext(WebApiCodecs.strategies(WebApiSettings.DEPLOYED)).viewResolvers()).isEmpty();
        WebApiEvents.NONE.unclassifiedFailure("trace", new IllegalStateException());
        WebApiEvents.NONE.rateLimited("trace");
    }

    @Test
    @DisplayName("ADR-035 a failure after the response was committed is propagated instead of writing a second response")
    void committedResponse() {
        ApiProblems problems = new ApiProblems(WebApiSettings.DEPLOYED, WebApiEvents.NONE);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/events"));
        exchange.getResponse().setComplete().block();
        IllegalStateException failure = new IllegalStateException("late");

        StepVerifier.create(problems.write(exchange, failure)).expectErrorMatches(failure::equals).verify();
        StepVerifier.create(new ApiExceptionHandler(problems).handle(exchange, failure))
                .expectErrorMatches(failure::equals).verify();
    }

    @Test
    @DisplayName("AC-047 ADR-035 API-004 every functional rejection of the use case keeps its code and status over HTTP")
    void purchaseRejections() {
        Map<Supplier<RequestRejectedException>, Integer> rejections = Map.of(
                RequestRejectedException::eventNotFound, 404,
                RequestRejectedException::eventNotOnSale, 409,
                RequestRejectedException::activeOrderExists, 409,
                () -> RequestRejectedException.ticketsUnavailable(List.of("A-1-1")), 409,
                () -> RequestRejectedException.unknownTickets(List.of("A-1-1")), 422,
                RequestRejectedException::idempotencyKeyReused, 422,
                () -> RequestRejectedException.serviceUnavailable(Duration.ofMillis(300)), 503);
        int sequence = 0;
        for (Map.Entry<Supplier<RequestRejectedException>, Integer> rejection : rejections.entrySet()) {
            RequestRejectedException error = rejection.getKey().get();
            when(mocks.startPurchase.startPurchase(any())).thenReturn(Mono.error(error));

            Reply reply = server.send(WebApiMocks.purchase(server, TestTokenIssuer.CUSTOMER_A, WebApiMocks.key(++sequence)), true);

            assertProblem(reply, rejection.getValue(), error.code().name());
            if (error.code() == DomainErrorCode.SERVICE_UNAVAILABLE) {
                assertThat(reply.header(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            }
        }
    }

    @Test
    @DisplayName("ADR-035 API-001 to API-006 temporary unavailability after bounded retries: 503 SERVICE_UNAVAILABLE with Retry-After")
    void serviceUnavailableOnEveryOperation() {
        Supplier<Mono<Object>> unavailable = () -> Mono.error(new DependencyUnavailableException("DynamoDB: ThrottlingException") {
        });
        when(mocks.createEvent.createEvent(any())).thenAnswer(invocation -> unavailable.get());
        when(mocks.listEvents.listEvents(any())).thenAnswer(invocation -> unavailable.get());
        when(mocks.provisioningStatus.getProvisioningStatus(any())).thenAnswer(invocation -> unavailable.get());
        when(mocks.availability.getAvailability(any())).thenAnswer(invocation -> unavailable.get());
        when(mocks.startPurchase.startPurchase(any())).thenAnswer(invocation -> unavailable.get());
        when(mocks.getOrder.getOrder(any())).thenAnswer(invocation -> unavailable.get());
        String admin = server.token(TestTokenIssuer.ADMIN_CUSTOMER);

        List<WebTestClient.RequestHeadersSpec<?>> requests = List.of(
                server.client.post().uri("/api/v1/events").headers(headers -> {
                    headers.setBearerAuth(admin);
                    headers.set("Idempotency-Key", WebApiMocks.key(1));
                }).contentType(MediaType.APPLICATION_JSON).bodyValue(WebApiMocks.EVENT_BODY),
                server.client.get().uri("/api/v1/events").headers(headers -> headers.setBearerAuth(admin)),
                server.client.get().uri("/api/v1/events/" + WebApiMocks.EVENT_ID + "/provisioning")
                        .headers(headers -> headers.setBearerAuth(admin)),
                server.client.get().uri("/api/v1/events/" + WebApiMocks.EVENT_ID + "/availability")
                        .headers(headers -> headers.setBearerAuth(admin)),
                WebApiMocks.purchase(server, TestTokenIssuer.ADMIN_CUSTOMER, WebApiMocks.key(2)),
                server.client.get().uri("/api/v1/orders/" + WebApiMocks.ORDER_ID).headers(headers -> headers.setBearerAuth(admin)));

        for (WebTestClient.RequestHeadersSpec<?> request : requests) {
            Reply reply = server.send(request, true);

            assertProblem(reply, 503, "SERVICE_UNAVAILABLE");
            assertThat(reply.header(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            assertThat(reply.body()).doesNotContain("DynamoDB").doesNotContain("Throttling");
        }
        assertThat(server.events.unclassified).isEmpty();
    }

    @Test
    @DisplayName("ADR-035 an unclassified failure is 500 INTERNAL_ERROR without message, class or stack trace, and is reported to the hook")
    void unclassifiedFailure() {
        when(mocks.getOrder.getOrder(any())).thenReturn(Mono.error(new IllegalStateException("secret table name ticketing")));

        // OpenAPI v2 declares no 500 per operation; INTERNAL_ERROR belongs to its Problem.code enumeration and
        // ADR-035 maps every unclassified result to it, so only the Problem body is checked here.
        Reply reply = new Reply(server.client.get().uri("/api/v1/orders/" + WebApiMocks.ORDER_ID)
                .headers(headers -> headers.setBearerAuth(server.token(TestTokenIssuer.CUSTOMER_A)))
                .exchange().expectBody().returnResult());

        assertProblem(reply, 500, "INTERNAL_ERROR");
        assertThat(CONTRACT_CODES).contains(reply.code());
        assertThat(reply.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(reply.json().get("detail").stringValue()).isEqualTo("The request could not be processed.");
        assertThat(reply.body()).doesNotContain("secret").doesNotContain("IllegalState").doesNotContain("ticketing");
        assertThat(server.events.unclassified).singleElement().isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("ADR-035 traceId: the trace-id of a valid traceparent, also handed to the use case as correlationId")
    void traceIdFromTraceParent() {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        when(mocks.startPurchase.startPurchase(any())).thenReturn(Mono.error(RequestRejectedException.activeOrderExists()));
        when(mocks.createEvent.createEvent(any())).thenReturn(Mono.error(RequestRejectedException.idempotencyKeyReused()));

        Reply purchase = server.send(WebApiMocks.purchase(server, TestTokenIssuer.CUSTOMER_A, WebApiMocks.key(1))
                .header(TraceIds.TRACE_PARENT, "00-" + traceId + "-00f067aa0ba902b7-01"), true);
        Reply creation = server.send(server.client.post().uri("/api/v1/events").headers(headers -> {
            headers.setBearerAuth(server.token(TestTokenIssuer.ADMIN));
            headers.set("Idempotency-Key", WebApiMocks.key(2));
            headers.set(TraceIds.TRACE_PARENT, "00-" + traceId + "-00f067aa0ba902b7-01");
        }).contentType(MediaType.APPLICATION_JSON).bodyValue(WebApiMocks.EVENT_BODY), true);

        assertThat(purchase.json().get("traceId").stringValue()).isEqualTo(traceId);
        assertProblem(creation, 422, "IDEMPOTENCY_KEY_REUSED");
        ArgumentCaptor<StartPurchaseCommand> purchaseCommand = ArgumentCaptor.forClass(StartPurchaseCommand.class);
        verify(mocks.startPurchase).startPurchase(purchaseCommand.capture());
        assertThat(purchaseCommand.getValue().correlationId()).isEqualTo(traceId);
        assertThat(purchaseCommand.getValue().customerId()).isEqualTo("customer-a");
        ArgumentCaptor<CreateEventCommand> eventCommand = ArgumentCaptor.forClass(CreateEventCommand.class);
        verify(mocks.createEvent).createEvent(eventCommand.capture());
        assertThat(eventCommand.getValue().correlationId()).isEqualTo(traceId);
        assertThat(eventCommand.getValue().adminSubject()).isEqualTo("admin");
    }

    @Test
    @DisplayName("ADR-035 traceId: invalid or absent traceparent yields a random 128-bit identifier")
    void traceIdWithoutValidTraceParent() {
        assertThat(TraceIds.fromTraceParent(null)).matches("[0-9a-f]{32}");
        assertThat(TraceIds.fromTraceParent("00-" + "0".repeat(32) + "-00f067aa0ba902b7-01")).doesNotContain("0".repeat(32));
        assertThat(TraceIds.fromTraceParent("ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
                .isNotEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(TraceIds.fromTraceParent("garbage")).matches("[0-9a-f]{32}");
        assertThat(TraceIds.fromTraceParent(" 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01 "))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }
}
