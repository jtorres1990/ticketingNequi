package com.nequi.ticketing.infrastructure.adapter.out.payment;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.Connection;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reactive test server (Reactor Netty) that implements the Payment Mock contract
 * {@code payment-mock.openapi.v1.yaml} for the tests of the payment adapter (ADR-038, IV-001, IV-003). It is
 * written from the contract only and shares no code with {@code payment-mock} (ADR-034 boundary rule 7):
 * <ul>
 *   <li>API-101: {@code X-Api-Key}, {@code Idempotency-Key = paymentAttemptId}, outcome selected by a
 *       behaviour configured per {@code orderId} (approve, decline, definitive error, transient failures
 *       followed by an outcome, added latency, no response, connection closed); idempotent per
 *       {@code paymentAttemptId}; {@code DECLINED} with {@code ATTEMPT_CANCELLED} after an early cancellation,
 *       also for an authorization still in transit;</li>
 *   <li>API-102: {@code REVERSED}, {@code VOIDED} or {@code REGISTERED_BEFORE_CHARGE}, idempotent, with an
 *       optional failure status per attempt;</li>
 *   <li>every request and every response is validated against the versioned copy of the contract; the
 *       violations are exposed to the tests.</li>
 * </ul>
 */
public final class PaymentMockContractServer implements AutoCloseable {

    static final String CONTRACT = "/contracts/payment-mock.openapi.v1.yaml";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String apiKey;
    private final OpenApiInteractionValidator validator;
    private final DisposableServer server;
    private final Map<String, Behaviour> behaviours = new ConcurrentHashMap<>();
    private final Map<String, Integer> cancellationFailures = new ConcurrentHashMap<>();
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final List<String> violations = new CopyOnWriteArrayList<>();
    private volatile Behaviour defaultBehaviour = Behaviour.approve();

    private PaymentMockContractServer(String apiKey) {
        this.apiKey = apiKey;
        this.validator = OpenApiInteractionValidator.createForInlineApiSpecification(contract()).build();
        this.server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes
                        .post("/payments", this::authorize)
                        .post("/payments/{paymentAttemptId}/cancellation", this::cancel))
                .bindNow();
    }

    public static PaymentMockContractServer start(String apiKey) {
        return new PaymentMockContractServer(apiKey);
    }

    static String contract() {
        try (InputStream in = Objects.requireNonNull(PaymentMockContractServer.class.getResourceAsStream(CONTRACT),
                CONTRACT)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.port();
    }

    public void behaviour(String orderId, Behaviour behaviour) {
        behaviours.put(orderId, behaviour);
    }

    public void defaultBehaviour(Behaviour behaviour) {
        defaultBehaviour = behaviour;
    }

    /** API-102 for this attempt answers with the given status (401, 500, 503...) without recording anything. */
    public void failCancellation(String paymentAttemptId, int status) {
        cancellationFailures.put(paymentAttemptId, status);
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    public List<RecordedRequest> requests(String operation) {
        return requests.stream().filter(request -> request.operation().equals(operation)).toList();
    }

    public int authorizations(String paymentAttemptId) {
        Attempt attempt = attempts.get(paymentAttemptId);
        return attempt == null ? 0 : attempt.invocations.get();
    }

    public int cancellations(String paymentAttemptId) {
        Attempt attempt = attempts.get(paymentAttemptId);
        return attempt == null ? 0 : attempt.cancellationsReceived.get();
    }

    public List<String> contractViolations() {
        return List.copyOf(violations);
    }

    @Override
    public void close() {
        server.disposeNow();
    }

    // ------------------------------------------------------------------ API-101

    private Mono<Void> authorize(HttpServerRequest request, HttpServerResponse response) {
        return request.receive().aggregate().asString().defaultIfEmpty("")
                .publishOn(Schedulers.boundedElastic())
                .flatMap(body -> {
                    RecordedRequest recorded = record(PaymentEvents.AUTHORIZE, request, body);
                    Reply invalid = validateRequest(Request.Method.POST, "/payments", recorded);
                    if (invalid != null) {
                        return send(response, "/payments", invalid);
                    }
                    JsonNode json = JSON.readTree(body);
                    String attemptId = json.get("paymentAttemptId").stringValue();
                    if (!attemptId.equals(recorded.idempotencyKey())) {
                        return send(response, "/payments", error(400, "IDEMPOTENCY_KEY_MISMATCH"));
                    }
                    Attempt attempt = attempts.computeIfAbsent(attemptId, Attempt::new);
                    int invocation = attempt.invocations.incrementAndGet();
                    Behaviour behaviour = behaviours.getOrDefault(json.get("orderId").stringValue(), defaultBehaviour);
                    return respondToAuthorization(response, attempt, invocation, behaviour);
                });
    }

    private Mono<Void> respondToAuthorization(HttpServerResponse response, Attempt attempt, int invocation,
            Behaviour behaviour) {
        synchronized (attempt) {
            if (attempt.result != null) {
                return send(response, "/payments", result(attempt, true));
            }
            if (attempt.cancellation != null) {
                attempt.result = new Result("DECLINED", "ATTEMPT_CANCELLED");
                return send(response, "/payments", result(attempt, false));
            }
        }
        return switch (behaviour.type()) {
            case APPROVE -> settle(response, attempt, new Result("APPROVED", null));
            case DECLINE -> settle(response, attempt, new Result("DECLINED", behaviour.reasonCode()));
            case DEFINITIVE_ERROR -> send(response, "/payments", error(behaviour.status(), "DEFINITIVE_ERROR"));
            case TRANSIENT_THEN_OUTCOME -> invocation <= behaviour.transientFailures()
                    ? send(response, "/payments", error(behaviour.status(), "SIMULATED_FAILURE"))
                    : settle(response, attempt, new Result(behaviour.finalOutcome(),
                            "DECLINED".equals(behaviour.finalOutcome()) ? "RULE_DECLINED" : null));
            case LATENCY -> Mono.delay(behaviour.latency())
                    .then(Mono.defer(() -> settle(response, attempt, new Result("APPROVED", null))));
            case INVALID_BODY -> send(response, "/payments", new Reply(200, "{\"status\":\"MAYBE\"}", false));
            case NO_RESPONSE -> Mono.never();
            case CLOSE_CONNECTION -> {
                response.withConnection(Connection::dispose);
                yield Mono.never();
            }
        };
    }

    /** Stores the result unless a cancellation arrived meanwhile (the attempt was in transit, FG-003). */
    private Mono<Void> settle(HttpServerResponse response, Attempt attempt, Result result) {
        synchronized (attempt) {
            if (attempt.result == null) {
                attempt.result = attempt.cancellation != null ? new Result("DECLINED", "ATTEMPT_CANCELLED") : result;
            }
            return send(response, "/payments", result(attempt, false));
        }
    }

    // ------------------------------------------------------------------ API-102

    private Mono<Void> cancel(HttpServerRequest request, HttpServerResponse response) {
        String attemptId = request.param("paymentAttemptId");
        String path = "/payments/" + attemptId + "/cancellation";
        return request.receive().aggregate().asString().defaultIfEmpty("")
                .publishOn(Schedulers.boundedElastic())
                .flatMap(body -> {
                    RecordedRequest recorded = record(PaymentEvents.CANCEL, request, body);
                    Reply invalid = validateRequest(Request.Method.POST, path, recorded);
                    if (invalid != null) {
                        return send(response, path, invalid);
                    }
                    Integer failure = cancellationFailures.get(attemptId);
                    if (failure != null) {
                        return send(response, path, error(failure, "SIMULATED_FAILURE"));
                    }
                    Attempt attempt = attempts.computeIfAbsent(attemptId, Attempt::new);
                    attempt.cancellationsReceived.incrementAndGet();
                    boolean replayed;
                    synchronized (attempt) {
                        replayed = attempt.cancellation != null;
                        if (!replayed) {
                            attempt.cancellation = attempt.result == null
                                    ? "REGISTERED_BEFORE_CHARGE"
                                    : "APPROVED".equals(attempt.result.status()) ? "REVERSED" : "VOIDED";
                        }
                    }
                    ObjectNode result = JSON.createObjectNode()
                            .put("paymentAttemptId", attemptId)
                            .put("cancellationStatus", attempt.cancellation)
                            .put("replayed", replayed);
                    return send(response, path, new Reply(200, JSON.writeValueAsString(result), true));
                });
    }

    // ------------------------------------------------------------------ support

    private RecordedRequest record(String operation, HttpServerRequest request, String body) {
        RecordedRequest recorded = new RecordedRequest(operation, request.uri(),
                request.requestHeaders().get("X-Api-Key"),
                request.requestHeaders().get("Idempotency-Key"),
                request.requestHeaders().get("Content-Type"),
                request.requestHeaders().get("Accept"),
                body);
        requests.add(recorded);
        return recorded;
    }

    /**
     * Contract validation of the request; a missing or wrong API key is a 401 of the contract, and a missing
     * one is also reported as a violation (the adapter must always send it).
     */
    private Reply validateRequest(Request.Method method, String path, RecordedRequest recorded) {
        SimpleRequest.Builder builder = new SimpleRequest.Builder(method, path);
        header(builder, "X-Api-Key", recorded.apiKey());
        header(builder, "Idempotency-Key", recorded.idempotencyKey());
        header(builder, "Content-Type", recorded.contentType());
        header(builder, "Accept", recorded.accept());
        if (!recorded.body().isEmpty()) {
            builder.withBody(recorded.body());
        }
        ValidationReport report = validator.validateRequest(builder.build());
        if (report.hasErrors()) {
            violations.add("request " + path + ": " + report.getMessages());
            return error(400, "CONTRACT_VIOLATION");
        }
        if (recorded.apiKey() == null) {
            // the validator does not check the apiKey security scheme of the contract; the double does
            violations.add("request " + path + ": security requirement apiKey (X-Api-Key) not satisfied");
            return error(401, "UNAUTHORIZED");
        }
        if (!apiKey.equals(recorded.apiKey())) {
            return error(401, "UNAUTHORIZED");
        }
        return null;
    }

    private static void header(SimpleRequest.Builder builder, String name, String value) {
        if (value != null) {
            builder.withHeader(name, value);
        }
    }

    /** Validates the response against the contract (off the event loop) and sends it. */
    private Mono<Void> send(HttpServerResponse response, String path, Reply reply) {
        return Mono.fromRunnable(() -> validateResponse(path, reply))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.defer(() -> response.status(reply.status())
                        .header("Content-Type", "application/json")
                        .sendString(Mono.just(reply.body()))
                        .then()));
    }

    private void validateResponse(String path, Reply reply) {
        if (!reply.validate()) {
            return;
        }
        SimpleResponse simple = SimpleResponse.Builder.status(reply.status())
                .withContentType("application/json")
                .withBody(reply.body())
                .build();
        ValidationReport report = validator.validateResponse(path, Request.Method.POST, simple);
        if (report.hasErrors()) {
            violations.add("response " + path + " " + reply.status() + ": " + report.getMessages());
        }
    }

    private static Reply result(Attempt attempt, boolean replayed) {
        ObjectNode body = JSON.createObjectNode()
                .put("paymentAttemptId", attempt.paymentAttemptId)
                .put("status", attempt.result.status())
                .put("providerReference", "prov-" + attempt.paymentAttemptId);
        if (attempt.result.reasonCode() != null) {
            body.put("reasonCode", attempt.result.reasonCode());
        }
        body.put("replayed", replayed).put("cancelled", attempt.cancellation != null);
        return new Reply(200, JSON.writeValueAsString(body), true);
    }

    private static Reply error(int status, String code) {
        ObjectNode body = JSON.createObjectNode().put("code", code).put("message", "simulated");
        return new Reply(status, JSON.writeValueAsString(body), true);
    }

    public record RecordedRequest(String operation, String uri, String apiKey, String idempotencyKey, String contentType,
            String accept, String body) {

        JsonNode json() {
            return JSON.readTree(body);
        }
    }

    private record Reply(int status, String body, boolean validate) {
    }

    private record Result(String status, String reasonCode) {
    }

    private static final class Attempt {
        private final String paymentAttemptId;
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicInteger cancellationsReceived = new AtomicInteger();
        private Result result;
        private String cancellation;

        private Attempt(String paymentAttemptId) {
            this.paymentAttemptId = paymentAttemptId;
        }
    }

    public enum Type {
        APPROVE,
        DECLINE,
        DEFINITIVE_ERROR,
        TRANSIENT_THEN_OUTCOME,
        LATENCY,
        INVALID_BODY,
        NO_RESPONSE,
        CLOSE_CONNECTION
    }

    /** Behaviour of API-101 for an {@code orderId} (contract {@code OutcomeRuleInput.behaviour}). */
    public record Behaviour(Type type, String reasonCode, int status, int transientFailures, String finalOutcome,
            Duration latency) {

        public static Behaviour approve() {
            return new Behaviour(Type.APPROVE, null, 200, 0, null, Duration.ZERO);
        }

        public static Behaviour decline(String reasonCode) {
            return new Behaviour(Type.DECLINE, reasonCode, 200, 0, null, Duration.ZERO);
        }

        public static Behaviour definitiveError(int status) {
            return new Behaviour(Type.DEFINITIVE_ERROR, null, status, 0, null, Duration.ZERO);
        }

        public static Behaviour transientThen(int failures, int status, String finalOutcome) {
            return new Behaviour(Type.TRANSIENT_THEN_OUTCOME, null, status, failures, finalOutcome, Duration.ZERO);
        }

        public static Behaviour latency(Duration latency) {
            return new Behaviour(Type.LATENCY, null, 200, 0, null, latency);
        }

        public static Behaviour of(Type type) {
            return new Behaviour(type, null, 200, 0, null, Duration.ZERO);
        }
    }

    /** All violations, for assertion messages. */
    static String describe(List<String> violations) {
        return String.join(System.lineSeparator(), new ArrayList<>(violations));
    }
}
