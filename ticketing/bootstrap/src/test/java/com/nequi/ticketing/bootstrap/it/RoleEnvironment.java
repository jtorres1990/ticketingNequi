package com.nequi.ticketing.bootstrap.it;

import com.nequi.ticketing.application.port.out.Clock;
import com.nequi.ticketing.bootstrap.TicketingApplication;
import com.nequi.ticketing.infrastructure.adapter.in.web.TestTokenIssuer;
import com.nequi.ticketing.infrastructure.adapter.out.dynamodb.DynamoDbLocalSupport;
import com.nequi.ticketing.infrastructure.adapter.out.payment.PaymentMockContractServer;
import com.nequi.ticketing.infrastructure.adapter.sqs.LocalStackSqsSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Component integration environment of INC-010 inside the JVM (ADR-038): DynamoDB Local 3.3.1 and LocalStack
 * 4.14.0 (Testcontainers, table and queues created by the fixtures exactly as data model §2 and messaging §1), the
 * Payment Mock contract test server of INC-007 (never the real {@code payment-mock} project, ADR-034 boundary rule
 * 7), the test token issuer of INC-008 and real Spring Boot contexts of the {@code api} and {@code worker} roles
 * built from {@link TicketingApplication} with configuration passed as properties, as the environment would.
 */
public final class RoleEnvironment implements AutoCloseable {

    public static final String API_KEY = "it-api-key";

    static {
        // DynamoDB Local and LocalStack accept any credentials: placeholders read by the default chain, not secrets.
        System.setProperty("aws.accessKeyId", "local");
        System.setProperty("aws.secretAccessKey", "local");
    }

    public final String table;
    public final LocalStackSqsSupport.Queues queues;
    public final PaymentMockContractServer paymentMock;
    public final TestTokenIssuer tokens;
    public final OffsetClock clock = new OffsetClock();

    private RoleEnvironment(LocalStackSqsSupport.Queues queues) {
        this.table = DynamoDbLocalSupport.createTable();
        this.queues = queues;
        this.paymentMock = PaymentMockContractServer.start(API_KEY);
        this.tokens = TestTokenIssuer.start(Instant.now());
    }

    public static RoleEnvironment start() {
        return new RoleEnvironment(LocalStackSqsSupport.createQueues());
    }

    /** Common configuration of both roles: the environment variables of the deployment as properties. */
    public Map<String, Object> properties(String role) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("ticketing.role", role);
        properties.put("ticketing.aws.region", "us-east-1");
        properties.put("ticketing.dynamodb.endpoint", DynamoDbLocalSupport.endpoint().toString());
        properties.put("ticketing.dynamodb.table-name", table);
        properties.put("ticketing.sqs.endpoint", LocalStackSqsSupport.connection().endpointOverride().toString());
        properties.put("ticketing.sqs.orders-queue-url", queues.orders());
        properties.put("ticketing.sqs.provisioning-queue-url", queues.provisioning());
        properties.put("ticketing.payment.base-url", paymentMock.baseUrl());
        properties.put("ticketing.payment.api-key", API_KEY);
        properties.put("ticketing.security.issuer", TestTokenIssuer.ISSUER);
        properties.put("ticketing.security.jwk-set-uri", tokens.jwkSetUri());
        properties.put("ticketing.security.allowed-client-ids", TestTokenIssuer.CLIENT_ID);
        properties.put("server.port", "0");
        properties.put("management.server.port", "0");
        properties.put("logging.structured.format.console", "");
        return properties;
    }

    /** Starts a role context; {@code overrides} replace or extend the common configuration. */
    public RoleContext start(String role, Map<String, Object> overrides, Object... extraSources) {
        Map<String, Object> properties = properties(role);
        properties.putAll(overrides);
        // Command-line arguments: the highest precedence, like the environment variables of application.yaml.
        String[] arguments = properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
        ConfigurableApplicationContext context = new SpringApplicationBuilder(TicketingApplication.class)
                .initializers(applicationContext -> applicationContext.getBeanFactory()
                        .registerSingleton("integrationTestClock", clock))
                .sources(IsolatedReactorResources.class)
                .sources(extraSources.length == 0 ? new Class<?>[0] : classes(extraSources))
                .run(arguments);
        return new RoleContext(context);
    }

    private static Class<?>[] classes(Object... sources) {
        Class<?>[] classes = new Class<?>[sources.length];
        for (int index = 0; index < sources.length; index++) {
            classes[index] = (Class<?>) sources[index];
        }
        return classes;
    }

    public WebTestClient client(RoleContext context) {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + context.port())
                .responseTimeout(Duration.ofSeconds(30)).build();
    }

    public WebTestClient managementClient(RoleContext context) {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + context.managementPort())
                .responseTimeout(Duration.ofSeconds(30)).build();
    }

    /** Value of one Prometheus sample of the management port (labels in alphabetical order), or -1 when absent. */
    public double metric(RoleContext context, String sample) {
        String body = managementClient(context).get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        return body.lines()
                .filter(line -> line.startsWith(sample + " "))
                .map(line -> Double.parseDouble(line.substring(sample.length() + 1).trim()))
                .findFirst()
                .orElse(-1.0);
    }

    @Override
    public void close() {
        paymentMock.close();
        tokens.close();
    }

    /**
     * Several role contexts share this JVM with the test servers (token issuer, Payment Mock contract server). With
     * the default global Reactor Netty resources, closing one context disposes them (phase 0, after the ordered stop
     * of the role) and the test servers bound on them stop. In a deployment there is one context per process, so
     * this only isolates the server resources of each test context.
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class IsolatedReactorResources {

        @org.springframework.context.annotation.Bean
        org.springframework.http.client.ReactorResourceFactory reactorResourceFactory() {
            org.springframework.http.client.ReactorResourceFactory factory =
                    new org.springframework.http.client.ReactorResourceFactory();
            factory.setUseGlobalResources(false);
            return factory;
        }
    }

    /** A running role: its context, application port and management port. */
    public record RoleContext(ConfigurableApplicationContext context) implements AutoCloseable {

        public int port() {
            return ((WebServerApplicationContext) context).getWebServer().getPort();
        }

        public int managementPort() {
            return Integer.parseInt(context.getEnvironment().getProperty("local.management.port"));
        }

        public <T> T bean(Class<T> type) {
            return context.getBean(type);
        }

        @Override
        public void close() {
            context.close();
        }
    }

    /** Application clock shared by both roles: the server time plus an adjustable offset (Clock port, ADR-034). */
    public static final class OffsetClock implements Clock {

        private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

        @Override
        public Instant now() {
            return Instant.now().plus(offset.get());
        }

        public void advance(Duration duration) {
            offset.updateAndGet(current -> current.plus(duration));
        }

        public void reset() {
            offset.set(Duration.ZERO);
        }
    }
}
