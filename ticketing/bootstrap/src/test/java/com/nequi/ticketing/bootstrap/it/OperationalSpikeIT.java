package com.nequi.ticketing.bootstrap.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nequi.ticketing.bootstrap.it.RoleEnvironment.RoleContext;
import com.nequi.ticketing.infrastructure.adapter.in.web.TestTokenIssuer;
import com.nequi.ticketing.infrastructure.adapter.out.payment.PaymentMockContractServer.Behaviour;
import com.nequi.ticketing.infrastructure.adapter.sqs.LocalStackSqsSupport;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/**
 * SPK-024 (architecture §13 #20, operational part): management port separate from the application port with the
 * health groups on the application port, metrics only on the management port, export of metrics (Prometheus) and
 * traces (trace context propagated from the request to the message), and ordered shutdown that completes what is
 * in flight in both roles (ADR-032, ADR-037, IV-007).
 */
class OperationalSpikeIT {

    private static RoleEnvironment environment;

    @BeforeAll
    static void startEnvironment() {
        environment = RoleEnvironment.start();
    }

    @AfterAll
    static void stopEnvironment() {
        environment.close();
    }

    @Test
    @DisplayName("SPK-024 ADR-032 ADR-037 health on the application port, metrics and management only on the internal port")
    void healthAndManagementPorts() {
        try (RoleContext api = environment.start("api", Map.of())) {
            var app = environment.client(api);
            var management = environment.managementClient(api);
            assertThat(api.port()).isNotEqualTo(api.managementPort());

            app.get().uri("/livez").exchange().expectStatus().isOk().expectBody(String.class)
                    .value(body -> assertThat(body).contains("UP"));
            app.get().uri("/readyz").exchange().expectStatus().isOk();
            app.get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
            app.get().uri("/actuator/health").exchange().expectStatus().isUnauthorized();

            management.get().uri("/actuator/health").exchange().expectStatus().isOk();
            String metrics = management.get().uri("/actuator/prometheus").exchange().expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody();
            assertThat(metrics)
                    .contains("ticketing_circuit_state{circuit=\"sqs-publication\",state=\"CLOSED\"} 1.0")
                    .contains("ticketing_circuit_state{circuit=\"sqs-publication\",state=\"OPEN\"} 0.0")
                    .contains("ticketing_log_dropped_total")
                    .contains("jvm_memory_used_bytes");
            assertThat(api.bean(Tracer.class)).isNotSameAs(Tracer.NOOP);
            System.out.println("SPK-024 api ports: application " + api.port() + ", management " + api.managementPort());
        }
    }

    @Test
    @DisplayName("SPK-024 ADR-037 IV-007 the trace of the request reaches the Problem Details and the traceparent of MSG-002")
    void traceContextIsPropagatedToMessages() {
        LocalStackSqsSupport.Queues queues = LocalStackSqsSupport.createQueues();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String traceParent = "00-" + traceId + "-00f067aa0ba902b7-01";
        try (RoleContext api = environment.start("api", Map.of(
                "ticketing.sqs.orders-queue-url", queues.orders(),
                "ticketing.sqs.provisioning-queue-url", queues.provisioning()))) {
            TicketingApi client = new TicketingApi(environment.client(api), environment.tokens);

            TicketingApi.Reply created = client.createEvent(TicketingApi.eventBody("Traced",
                    java.time.Instant.now().plus(Duration.ofDays(5)), 1, 5), traceParent);
            assertThat(created.status()).as(created.body()).isEqualTo(202);

            List<Message> messages = LocalStackSqsSupport.admin().receiveMessage(builder -> builder
                    .queueUrl(queues.provisioning()).waitTimeSeconds(5).maxNumberOfMessages(1)
                    .messageAttributeNames("All").messageSystemAttributeNames(MessageSystemAttributeName.ALL))
                    .join().messages();
            assertThat(messages).hasSize(1);
            String propagated = messages.getFirst().messageAttributes().get("traceparent").stringValue();
            assertThat(propagated).startsWith("00-" + traceId + "-").doesNotContain("00f067aa0ba902b7");

            TicketingApi.Reply missing = client.get("/api/v1/orders/" + UUID.randomUUID(),
                    environment.tokens.token(TestTokenIssuer.CUSTOMER_A));
            assertThat(missing.status()).isEqualTo(404);
            TicketingApi.Reply traced = new TicketingApi(environment.client(api), environment.tokens)
                    .purchase(TestTokenIssuer.CUSTOMER_A, UUID.randomUUID().toString(), TicketingApi.key(),
                            headers -> headers.set("traceparent", traceParent), "A-1-2");
            assertThat(traced.status()).isEqualTo(404);
            assertThat(traced.text("traceId")).isEqualTo(traceId);
            System.out.println("SPK-024 traceparent sent " + traceParent + " -> MSG-002 " + propagated);
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("SPK-024 IV-007 ADR-032 structured JSON logs with event, trace and identifiers, without tokens")
    void structuredJsonLogs(CapturedOutput output) {
        LocalStackSqsSupport.Queues queues = LocalStackSqsSupport.createQueues();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        try (RoleContext api = environment.start("api", Map.of(
                "logging.structured.format.console", "ecs",
                "ticketing.sqs.orders-queue-url", queues.orders(),
                "ticketing.sqs.provisioning-queue-url", queues.provisioning()))) {
            TicketingApi client = new TicketingApi(environment.client(api), environment.tokens);
            TicketingApi.Reply created = client.createEvent(TicketingApi.eventBody("Logged",
                    java.time.Instant.now().plus(Duration.ofDays(5)), 1, 5), "00-" + traceId + "-00f067aa0ba902b7-01");
            assertThat(created.status()).as(created.body()).isEqualTo(202);
            String eventId = created.text("eventId");

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(output.getOut())
                    .contains("\"event\":\"provisioning.started\"")
                    .contains(eventId));
            String line = output.getOut().lines().filter(candidate -> candidate.contains("provisioning.started"))
                    .findFirst().orElseThrow();
            assertThat(line).startsWith("{").contains("\"log\":{\"level\":\"INFO\"").contains("\"correlationId\":\"" + traceId + "\"")
                    .doesNotContain("Bearer").doesNotContain("eyJ");
            System.out.println("SPK-024 structured log line: " + line);
        }
    }

    @Test
    @DisplayName("SPK-024 ADR-037 api ordered shutdown: the request in flight completes, the stop waits for it")
    void apiShutdownCompletesTheRequestInFlight() throws Exception {
        RoleContext api = environment.start("api", Map.of(), SlowEndpoint.class);
        var client = environment.client(api);
        CompletableFuture<Integer> inFlight = CompletableFuture.supplyAsync(() -> client.get().uri("/spike/slow")
                .exchange().returnResult(String.class).getStatus().value());
        await().atMost(Duration.ofSeconds(10)).until(() -> SlowEndpoint.started > 0);

        long start = System.nanoTime();
        api.close();
        long stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(inFlight.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(stopMillis).isGreaterThanOrEqualTo(1_000);
        System.out.println("SPK-024 api stop waited " + stopMillis + " ms for the request in flight");
    }

    @Test
    @DisplayName("SPK-024 ADR-037 worker ordered shutdown: receiving stops and the message in flight completes before the stop")
    void workerShutdownCompletesTheMessageInFlight() {
        try (RoleContext api = environment.start("api", Map.of())) {
            RoleContext worker = environment.start("worker", Map.of());
            TicketingApi client = new TicketingApi(environment.client(api), environment.tokens);
            String eventId = client.createEnabledEvent(1, 10, Duration.ofSeconds(60));
            environment.paymentMock.defaultBehaviour(Behaviour.latency(Duration.ofMillis(2_000)));
            try {
                TicketingApi.Reply purchase = client.purchase(TestTokenIssuer.CUSTOMER_B, eventId, "A-1-5");
                assertThat(purchase.status()).as(purchase.body()).isEqualTo(201);
                String orderId = purchase.text("orderId");
                await().atMost(Duration.ofSeconds(30))
                        .until(() -> environment.paymentMock.authorizations(orderId + "-1") >= 1);

                long start = System.nanoTime();
                worker.close();
                long stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

                assertThat(client.order(TestTokenIssuer.CUSTOMER_B, orderId).json().get("status").stringValue())
                        .isEqualTo("CONFIRMED");
                assertThat(stopMillis).isGreaterThanOrEqualTo(500);
                System.out.println("SPK-024 worker stop waited " + stopMillis + " ms for the message in flight");
            } finally {
                environment.paymentMock.defaultBehaviour(Behaviour.approve());
            }
        }
    }

    /** Test-only endpoint that takes 2 s, public on the application port. */
    @Configuration(proxyBeanMethods = false)
    static class SlowEndpoint {

        static volatile int started;

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE + 2)
        SecurityWebFilterChain spikeChain(ServerHttpSecurity http) {
            return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/spike/**"))
                    .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                    .csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .build();
        }

        @Bean
        RouterFunction<ServerResponse> slowRoute() {
            return RouterFunctions.route().GET("/spike/slow", request -> {
                started++;
                return Mono.delay(Duration.ofSeconds(2)).then(ServerResponse.ok().bodyValue("done"));
            }).build();
        }
    }
}
