package com.nequi.ticketing.bootstrap.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nequi.ticketing.application.port.out.OrderProcessingRequested;
import com.nequi.ticketing.application.port.out.PublishResult;
import com.nequi.ticketing.bootstrap.config.WorkerRuntime;
import com.nequi.ticketing.bootstrap.it.RoleEnvironment.RoleContext;
import com.nequi.ticketing.infrastructure.adapter.in.web.TestTokenIssuer;
import com.nequi.ticketing.infrastructure.adapter.out.payment.PaymentMockContractServer.Behaviour;
import com.nequi.ticketing.infrastructure.adapter.out.payment.PaymentMockContractServer.RecordedRequest;
import com.nequi.ticketing.infrastructure.adapter.out.payment.PaymentMockGateway;
import com.nequi.ticketing.infrastructure.adapter.out.sqs.SqsPublisherSettings;
import com.nequi.ticketing.infrastructure.adapter.out.sqs.SqsQueuePublisher;
import com.nequi.ticketing.infrastructure.adapter.sqs.LocalStackSqsSupport;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsEvents;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import tools.jackson.databind.JsonNode;

/**
 * Component integration by role inside the JVM (plan INC-010, ADR-038): a real {@code api} context and a real
 * {@code worker} context of the same application, on DynamoDB Local, LocalStack and the Payment Mock contract
 * server, exercising every AP / API / MSG end to end within the JVM. Worker overrides, to keep the test short and
 * to show the configuration by environment (TC-012): visibility backoff between receptions 2 s without jitter
 * ({@code ticketing.sqs.orders.loop.*}), no authorization retries ({@code ticketing.payment.authorization-retries=0})
 * and a Payment Mock circuit window of 50 calls ({@code ticketing.payment.circuit.*}), so that the five receptions
 * with transient failures of the reversal flow do not open the circuit shared with the previous flows of the
 * class (the opening and the pause are covered by the unit tests of INC-007 and INC-009), and a PaymentAttempt lease
 * of 1 s ({@code ticketing.worker.payment-lease-duration}; approved 45 s), shorter than the backoff so that every
 * reception claims the expired lease and authorizes again; with the approved values a reception that finds the lease
 * of the previous one is postponed until it ends (ADR-029 rule 7) and the fifth reception is again a claim.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RoleComponentIT {

    private static final Duration FLOW = Duration.ofSeconds(60);

    private static RoleEnvironment environment;
    private static RoleContext api;
    private static RoleContext worker;
    private static TicketingApi client;
    private static String eventId;

    @BeforeAll
    static void startRoles() {
        environment = RoleEnvironment.start();
        api = environment.start("api", Map.of());
        worker = environment.start("worker", Map.of(
                "ticketing.sqs.orders.loop.retry-backoff", "2s",
                "ticketing.sqs.orders.loop.retry-jitter", "0",
                "ticketing.payment.authorization-retries", "0",
                "ticketing.payment.circuit.sliding-window-size", "50",
                "ticketing.payment.circuit.minimum-number-of-calls", "50",
                "ticketing.worker.payment-lease-duration", "1s"));
        client = new TicketingApi(environment.client(api), environment.tokens);
    }

    @AfterAll
    static void stopRoles() {
        worker.close();
        api.close();
        environment.close();
    }

    @AfterEach
    void approveByDefault() {
        environment.paymentMock.defaultBehaviour(Behaviour.approve());
        environment.clock.reset();
    }

    @Test
    @Order(1)
    @DisplayName("AC-001 MF-001 FR-001 FR-003 API-001 API-006 MSG-002 the api creates the Event and the worker provisions and enables it with exactly its capacity")
    void provisioningComplete() {
        TicketingApi.Reply created = client.createEvent(TicketingApi.eventBody("Concert",
                Instant.now().plus(Duration.ofDays(30)), 3, 10), null);
        assertThat(created.status()).as(created.body()).isEqualTo(202);
        assertThat(created.headers().getFirst("Location")).endsWith("/api/v1/events/" + created.text("eventId")
                + "/provisioning");
        eventId = created.text("eventId");

        JsonNode enabled = client.awaitProvisioning(eventId, "ENABLED", FLOW);

        assertThat(enabled.get("capacity").intValue()).isEqualTo(30);
        assertThat(enabled.get("provisionedTickets").intValue()).isEqualTo(30);
        assertThat(enabled.get("complimentaryTickets").intValue()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(client.availableCount(eventId)).isEqualTo(29));
    }

    @Test
    @Order(2)
    @DisplayName("MF-003 AC-005 AC-019 AC-024 API-004 API-005 MSG-001 API-101 happy purchase: reserved by the api, paid and confirmed by the worker")
    void happyPurchase() {
        TicketingApi.Reply purchase = client.purchase(TestTokenIssuer.CUSTOMER_A, eventId, "A-1-2", "A-1-3");
        assertThat(purchase.status()).as(purchase.body()).isEqualTo(201);
        assertThat(purchase.text("status")).isEqualTo("CREATED");
        String orderId = purchase.text("orderId");

        JsonNode confirmed = client.awaitOrder(TestTokenIssuer.CUSTOMER_A, orderId, "CONFIRMED", FLOW);

        assertThat(confirmed.has("failureCause")).isFalse();
        List<RecordedRequest> authorizations = environment.paymentMock.requests("authorize").stream()
                .filter(request -> (orderId + "-1").equals(request.idempotencyKey())).toList();
        assertThat(authorizations).hasSize(1);
        assertThat(authorizations.getFirst().apiKey()).isEqualTo(RoleEnvironment.API_KEY);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(client.availableCount(eventId)).isEqualTo(27));
        assertThat(environment.paymentMock.contractViolations()).isEmpty();
    }

    @Test
    @Order(3)
    @DisplayName("AC-023 ADR-038 message idempotency: repeated and simultaneous deliveries of MSG-001 make a single PaymentAttempt")
    void duplicateDeliveries() {
        TicketingApi.Reply purchase = client.purchase(TestTokenIssuer.CUSTOMER_B, eventId, "A-2-1");
        assertThat(purchase.status()).as(purchase.body()).isEqualTo(201);
        String orderId = purchase.text("orderId");
        SqsQueuePublisher duplicates = SqsQueuePublisher.create(LocalStackSqsSupport.admin(),
                SqsPublisherSettings.deployed(environment.queues.orders(), environment.queues.provisioning()),
                Instant::now, SqsEvents.NONE);
        OrderProcessingRequested message = new OrderProcessingRequested(orderId, eventId, Instant.now(),
                "duplicate-delivery", OrderProcessingRequested.Publisher.SWEEP);
        for (int copy = 0; copy < 3; copy++) {
            assertThat(duplicates.publish(message).block(Duration.ofSeconds(5))).isEqualTo(PublishResult.PUBLISHED);
        }

        client.awaitOrder(TestTokenIssuer.CUSTOMER_B, orderId, "CONFIRMED", FLOW);
        await().atMost(FLOW).untilAsserted(() -> assertThat(metric(worker,
                "ticketing_messages_processed_total{action=\"delete\",queue=\"orders\",reason=\"ALREADY_TERMINAL\"}"))
                .isGreaterThanOrEqualTo(1.0));
        assertThat(environment.paymentMock.authorizations(orderId + "-1")).isEqualTo(1);
        // After the late copies the Order is still CONFIRMED with its single PaymentAttempt.
        assertThat(client.order(TestTokenIssuer.CUSTOMER_B, orderId).json().get("status").stringValue())
                .isEqualTo("CONFIRMED");
    }

    @Test
    @Order(4)
    @DisplayName("AC-020 ALT-005 ST-008 payment declined: REJECTED with PAYMENT_DECLINED, Tickets released and the active Order lock removed")
    void paymentDeclined() {
        environment.paymentMock.defaultBehaviour(Behaviour.decline("INSUFFICIENT_FUNDS"));
        TicketingApi.Reply purchase = client.purchase(TestTokenIssuer.CUSTOMER_A, eventId, "A-3-1");
        assertThat(purchase.status()).as(purchase.body()).isEqualTo(201);
        String orderId = purchase.text("orderId");

        JsonNode rejected = client.awaitOrder(TestTokenIssuer.CUSTOMER_A, orderId, "REJECTED", FLOW);

        assertThat(rejected.get("failureCause").get("code").stringValue()).isEqualTo("PAYMENT_DECLINED");
        // The page of AVAILABLE Tickets is read strongly; the count may be up to 1 s old (ADR-040).
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(client.availableTicketIds(eventId))
                .contains("A-3-1"));
        environment.paymentMock.defaultBehaviour(Behaviour.approve());
        TicketingApi.Reply again = client.purchase(TestTokenIssuer.CUSTOMER_A, eventId, "A-3-1");
        assertThat(again.status()).as(again.body()).isEqualTo(201);
        client.awaitOrder(TestTokenIssuer.CUSTOMER_A, again.text("orderId"), "CONFIRMED", FLOW);
    }

    @Test
    @Order(5)
    @DisplayName("AC-021 AC-034 ERR-005 FR-023 API-102 failure with reversal: transient failures until the last reception, FAILED and the payment reversed once")
    void failureWithReversal() {
        environment.paymentMock.defaultBehaviour(Behaviour.transientThen(1_000, 503, "APPROVED"));
        TicketingApi.Reply purchase = client.purchase(TestTokenIssuer.ADMIN_CUSTOMER, eventId, "A-3-5");
        assertThat(purchase.status()).as(purchase.body()).isEqualTo(201);
        String orderId = purchase.text("orderId");

        JsonNode failed = client.awaitOrder(TestTokenIssuer.ADMIN_CUSTOMER, orderId, "FAILED", Duration.ofSeconds(90));

        assertThat(failed.get("failureCause").get("code").stringValue()).isEqualTo("PROCESSING_FAILED");
        // One PaymentAttempt reused by every reception (some receptions only find the lease of the previous one).
        assertThat(environment.paymentMock.authorizations(orderId + "-1")).isBetween(1, 5);
        assertThat(environment.paymentMock.requests("authorize").stream()
                .filter(request -> request.body().contains(orderId)).map(RecordedRequest::idempotencyKey).distinct()
                .toList()).containsExactly(orderId + "-1");
        await().atMost(FLOW).untilAsserted(() -> assertThat(environment.paymentMock.cancellations(orderId + "-1"))
                .isEqualTo(1));
        await().atMost(FLOW).untilAsserted(() -> assertThat(metric(worker,
                "ticketing_payment_reversals_total{outcome=\"confirmed\"}")).isGreaterThanOrEqualTo(1.0));
        assertThat(environment.paymentMock.cancellations(orderId + "-1")).isEqualTo(1);
    }

    @Test
    @Order(6)
    @DisplayName("AC-008 AC-009 BR-030 FR-011 expiration with release: past expiresAt the Order expires and its Tickets are AVAILABLE again within 15 s")
    void expirationReleasesTheTickets() {
        environment.paymentMock.defaultBehaviour(Behaviour.latency(Duration.ofMillis(1_500)));
        TicketingApi.Reply purchase = client.purchase(TestTokenIssuer.CUSTOMER_B, eventId, "A-3-9", "A-3-10");
        assertThat(purchase.status()).as(purchase.body()).isEqualTo(201);
        String orderId = purchase.text("orderId");
        Instant expiresAt = Instant.parse(purchase.text("reservationExpiresAt"));
        environment.clock.advance(Duration.between(Instant.now(), expiresAt).plusSeconds(1));

        JsonNode expired = client.awaitOrder(TestTokenIssuer.CUSTOMER_B, orderId, "EXPIRED", Duration.ofSeconds(15));

        assertThat(expired.get("failureCause").get("code").stringValue()).isEqualTo("RESERVATION_EXPIRED");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(client.availableTicketIds(eventId))
                .contains("A-3-9", "A-3-10"));
        assertThat(metric(worker, "ticketing_orders_terminal_total{cause=\"RESERVATION_EXPIRED\",status=\"EXPIRED\"}"))
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @Order(7)
    @DisplayName("TC-006 CMP-021 CMP-018 CMP-026 roles: the api serves the API only, the worker runs the loops only; metrics of both roles")
    void rolesAndMetrics() {
        assertThat(environment.client(worker).get().uri("/api/v1/events")
                .headers(headers -> headers.setBearerAuth(environment.tokens.token(TestTokenIssuer.ADMIN)))
                .exchange().returnResult(String.class).getStatus().value()).isEqualTo(401);
        assertThat(worker.bean(WorkerRuntime.class).isRunning()).isTrue();
        assertBeanAbsent(api, WorkerRuntime.class);
        assertBeanAbsent(api, PaymentMockGateway.class);

        assertThat(metric(api, "ticketing_reservations_total{code=\"none\",outcome=\"created\"}")).isGreaterThanOrEqualTo(5.0);
        assertThat(metric(api, "ticketing_provisioning_total{outcome=\"started\"}")).isGreaterThanOrEqualTo(1.0);
        assertThat(metric(api, "ticketing_circuit_state{circuit=\"sqs-publication\",state=\"CLOSED\"}")).isEqualTo(1.0);
        assertThat(metric(worker, "ticketing_provisioning_total{outcome=\"enabled\"}")).isGreaterThanOrEqualTo(1.0);
        assertThat(metric(worker, "ticketing_orders_terminal_total{cause=\"none\",status=\"CONFIRMED\"}")).isGreaterThanOrEqualTo(3.0);
        assertThat(metric(worker, "ticketing_orders_terminal_total{cause=\"PAYMENT_DECLINED\",status=\"REJECTED\"}")).isGreaterThanOrEqualTo(1.0);
        assertThat(metric(worker, "ticketing_payment_duration_seconds_count{operation=\"authorize\",outcome=\"approved\"}"))
                .isGreaterThanOrEqualTo(3.0);
        assertThat(metric(worker, "ticketing_circuit_state{circuit=\"payment-mock\",state=\"CLOSED\"}")).isEqualTo(1.0);
        assertThat(metric(worker, "ticketing_dynamodb_requests_seconds_count{operation=\"TransactWriteItems\",outcome=\"success\"}"))
                .isGreaterThanOrEqualTo(1.0);
        assertThat(metric(worker, "ticketing_scheduler_cycles_total{outcome=\"completed\",process=\"EXPIRATION\"}"))
                .isGreaterThanOrEqualTo(1.0);
        assertThat(metric(worker, "ticketing_orders_expiration_lag_seconds_count")).isGreaterThanOrEqualTo(0.0);
    }

    private static void assertBeanAbsent(RoleContext context, Class<?> type) {
        try {
            context.bean(type);
            throw new AssertionError(type.getSimpleName() + " must not exist in this role");
        } catch (NoSuchBeanDefinitionException expected) {
            // The role does not compose it.
        }
    }

    private static double metric(RoleContext context, String sample) {
        return environment.metric(context, sample);
    }
}
