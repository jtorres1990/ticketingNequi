package com.nequi.ticketing.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.application.usecase.ApiUseCaseSettings;
import com.nequi.ticketing.application.usecase.WorkerUseCaseSettings;
import com.nequi.ticketing.bootstrap.config.TicketingProperties.Role;
import com.nequi.ticketing.domain.event.InventoryLimits;
import com.nequi.ticketing.domain.event.ShardingPolicy;
import com.nequi.ticketing.domain.order.OrderRules;
import com.nequi.ticketing.domain.order.ReversalSchedule;
import com.nequi.ticketing.infrastructure.adapter.in.scheduler.WorkerSchedulerSettings;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.OrderConsumerSettings;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.ProvisioningConsumerSettings;
import com.nequi.ticketing.infrastructure.adapter.in.web.AccessTokenSettings;
import com.nequi.ticketing.infrastructure.adapter.in.web.WebApiSettings;
import com.nequi.ticketing.infrastructure.adapter.out.dynamodb.DynamoDbAdapterSettings;
import com.nequi.ticketing.infrastructure.adapter.out.payment.PaymentGatewaySettings;
import com.nequi.ticketing.infrastructure.adapter.out.sqs.SqsPublisherSettings;
import com.nequi.ticketing.infrastructure.observability.ObservabilitySettings;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * CMP-021, TC-012, plan Annex A: the configuration binds every approved value as its default and every value can
 * be overridden by environment (relaxed binding); IV-012, IV-015, IV-017, IV-019, IV-020 and IV-021 are bound here.
 */
class SettingsFactoryTest {

    private static final String ORDERS = "http://localhost:4566/000000000000/ticketing-orders";
    private static final String PROVISIONING = "http://localhost:4566/000000000000/ticketing-event-provisioning";

    @Test
    @DisplayName("TC-012 Annex A IV-012 IV-015 IV-017 IV-019 IV-020 IV-021 without overrides every setting is the approved value")
    void defaultsAreTheApprovedValues() {
        SettingsFactory factory = factory(required());

        assertThat(factory.role()).isEqualTo(Role.WORKER);
        assertThat(factory.orderRules()).isEqualTo(OrderRules.DEPLOYED);
        assertThat(factory.inventoryLimits()).isEqualTo(InventoryLimits.DEPLOYED);
        assertThat(factory.sharding()).isEqualTo(ShardingPolicy.DEPLOYED);
        assertThat(factory.reversalSchedule()).isEqualTo(ReversalSchedule.DEPLOYED);
        assertThat(factory.apiUseCases()).isEqualTo(ApiUseCaseSettings.DEPLOYED);
        assertThat(factory.workerUseCases("w-1")).isEqualTo(WorkerUseCaseSettings.deployed("w-1"));
        assertThat(factory.scheduler()).isEqualTo(WorkerSchedulerSettings.DEPLOYED);
        assertThat(factory.dynamoDbAdapter()).isEqualTo(DynamoDbAdapterSettings.deployed("ticketing"));
        assertThat(factory.sqsPublisher()).isEqualTo(SqsPublisherSettings.deployed(ORDERS, PROVISIONING));
        assertThat(factory.ordersConsumer()).isEqualTo(OrderConsumerSettings.deployed(ORDERS));
        assertThat(factory.provisioningConsumer()).isEqualTo(ProvisioningConsumerSettings.deployed(PROVISIONING));
        assertThat(factory.paymentGateway()).isEqualTo(
                PaymentGatewaySettings.deployed(URI.create("http://payment-mock:8090"), "test-api-key"));
        assertThat(factory.web()).isEqualTo(WebApiSettings.DEPLOYED);
        assertThat(factory.accessTokens()).isEqualTo(AccessTokenSettings.deployed("https://issuer.test",
                URI.create("http://idp:8080/jwks.json"), Set.of("web-client", "load-client")));
        assertThat(factory.observability()).isEqualTo(ObservabilitySettings.DEPLOYED);
        assertThat(factory.dynamoDbConnection().endpointOverride()).isNull();
        assertThat(factory.sqsConnection().region()).isEqualTo("us-east-1");
        assertThat(factory.workerId()).startsWith("worker-");
    }

    @Test
    @DisplayName("TC-012 IV-012 IV-015 every Annex A value can be overridden by environment with relaxed binding")
    void overridesAreBound() {
        Map<String, Object> values = required();
        values.put("ticketing.orders.maximum-tickets-per-order", "4");
        values.put("ticketing.orders.payment-cutoff", "20s");
        values.put("ticketing.sharding.reservation-shards", "2");
        values.put("ticketing.sharding.reversal-shards", "3");
        values.put("ticketing.sharding.pending-enqueue-shards", "5");
        values.put("ticketing.reversals.maximum-attempts", "4");
        values.put("ticketing.reversals.delays", "1s,2s");
        values.put("ticketing.worker.id", " worker-a ");
        values.put("ticketing.worker.maximum-verification-repairs", "1");
        values.put("ticketing.worker.expiration-concurrency", "6");
        values.put("ticketing.api.sold-out-probe-concurrency", "3");
        values.put("ticketing.inventory.maximum-capacity", "1000");
        values.put("ticketing.scheduler.expiration.period", "2s");
        values.put("ticketing.dynamodb.unprocessed-retries", "7");
        values.put("ticketing.dynamodb.endpoint", "http://dynamodb:8000");
        values.put("ticketing.sqs.orders.loop.retry-jitter", "0");
        values.put("ticketing.sqs.orders.loop.retry-backoff", "1s,2s");
        values.put("ticketing.sqs.orders.processing-cap", "20s");
        values.put("ticketing.sqs.provisioning.heartbeat-interval", "20s");
        values.put("ticketing.sqs.publication.circuit.open-duration", "5s");
        values.put("ticketing.payment.authorization-retries", "0");
        values.put("ticketing.payment.circuit.slow-call-duration-threshold", "1s");
        values.put("ticketing.web.unavailable-retry-after", "2s");
        values.put("ticketing.security.clock-skew", "30s");
        values.put("ticketing.observability.expiration-lag-threshold", "10s");
        SettingsFactory factory = factory(values);

        assertThat(factory.orderRules()).isEqualTo(new OrderRules(4, Duration.ofSeconds(20)));
        assertThat(factory.sharding()).isEqualTo(new ShardingPolicy(2_000, 32, 2, 3, 5));
        assertThat(factory.reversalSchedule())
                .isEqualTo(new ReversalSchedule(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), 4));
        assertThat(factory.workerId()).isEqualTo("worker-a");
        WorkerUseCaseSettings worker = factory.workerUseCases(factory.workerId());
        assertThat(worker.maximumVerificationRepairs()).isEqualTo(1);
        assertThat(worker.expirationConcurrency()).isEqualTo(6);
        assertThat(worker.orderRules().paymentCutoff()).isEqualTo(Duration.ofSeconds(20));
        assertThat(worker.sharding().reservationShards()).isEqualTo(2);
        assertThat(worker.inventoryLimits().maximumCapacity()).isEqualTo(1000);
        ApiUseCaseSettings api = factory.apiUseCases();
        assertThat(api.soldOutProbeConcurrency()).isEqualTo(3);
        assertThat(api.orderRules().maximumTicketsPerOrder()).isEqualTo(4);
        assertThat(factory.scheduler().expiration().period()).isEqualTo(Duration.ofSeconds(2));
        assertThat(factory.scheduler().sharding().reversalShards()).isEqualTo(3);
        assertThat(factory.dynamoDbAdapter().unprocessedRetries()).isEqualTo(7);
        assertThat(factory.dynamoDbAdapter().sharding().pendingEnqueueShards()).isEqualTo(5);
        assertThat(factory.dynamoDbConnection().endpointOverride()).isEqualTo(URI.create("http://dynamodb:8000"));
        assertThat(factory.ordersConsumer().loop().retryJitter()).isZero();
        assertThat(factory.ordersConsumer().loop().retryBackoff())
                .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(factory.ordersConsumer().processingCap()).isEqualTo(Duration.ofSeconds(20));
        assertThat(factory.provisioningConsumer().heartbeatInterval()).isEqualTo(Duration.ofSeconds(20));
        assertThat(factory.sqsPublisher().circuit().openDuration()).isEqualTo(Duration.ofSeconds(5));
        assertThat(factory.paymentGateway().authorizationRetries()).isZero();
        assertThat(factory.paymentGateway().circuit().slowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(1));
        assertThat(factory.web().unavailableRetryAfter()).isEqualTo(Duration.ofSeconds(2));
        assertThat(factory.accessTokens().clockSkew()).isEqualTo(Duration.ofSeconds(30));
        assertThat(factory.observability().expirationLagThreshold()).isEqualTo(Duration.ofSeconds(10));
        assertThat(factory.paymentGateway().toString()).doesNotContain("test-api-key");
    }

    @Test
    @DisplayName("TC-012 ADR-032 missing connection values fail the startup naming the property and the environment variable")
    void missingRequiredValues() {
        SettingsFactory empty = factory(Map.of("ticketing.aws.region", "", "ticketing.dynamodb.table-name", ""));
        assertThatThrownBy(empty::role).hasMessageContaining("TICKETING_ROLE");
        assertThatThrownBy(empty::dynamoDbAdapter).hasMessageContaining("TICKETING_DYNAMODB_TABLE");
        assertThatThrownBy(empty::dynamoDbConnection).hasMessageContaining("AWS_REGION");
        assertThatThrownBy(empty::sqsPublisher).hasMessageContaining("TICKETING_SQS_ORDERS_QUEUE_URL");
        assertThatThrownBy(empty::provisioningQueueUrl).hasMessageContaining("TICKETING_SQS_PROVISIONING_QUEUE_URL");
        assertThatThrownBy(empty::paymentGateway).hasMessageContaining("TICKETING_PAYMENT_BASE_URL");
        assertThatThrownBy(empty::accessTokens).hasMessageContaining("TICKETING_SECURITY_ISSUER");

        Map<String, Object> noKey = required();
        noKey.put("ticketing.payment.api-key", "");
        noKey.put("ticketing.security.jwk-set-uri", "");
        assertThatThrownBy(() -> factory(noKey).paymentGateway()).hasMessageContaining("TICKETING_PAYMENT_API_KEY");
        assertThatThrownBy(() -> factory(noKey).accessTokens()).hasMessageContaining("TICKETING_SECURITY_JWK_SET_URI");
        Map<String, Object> noClients = required();
        noClients.put("ticketing.security.allowed-client-ids", "");
        assertThatThrownBy(() -> factory(noClients).accessTokens())
                .hasMessageContaining("TICKETING_SECURITY_ALLOWED_CLIENT_IDS");
        Map<String, Object> tooMany = required();
        tooMany.put("ticketing.orders.maximum-tickets-per-order", "11");
        assertThatThrownBy(() -> factory(tooMany).orderRules()).isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, Object> required() {
        Map<String, Object> values = new java.util.HashMap<>();
        values.put("ticketing.role", "worker");
        values.put("ticketing.aws.region", "us-east-1");
        values.put("ticketing.dynamodb.table-name", "ticketing");
        values.put("ticketing.dynamodb.endpoint", "");
        values.put("ticketing.sqs.orders-queue-url", ORDERS);
        values.put("ticketing.sqs.provisioning-queue-url", PROVISIONING);
        values.put("ticketing.payment.base-url", "http://payment-mock:8090");
        values.put("ticketing.payment.api-key", "test-api-key");
        values.put("ticketing.security.issuer", "https://issuer.test");
        values.put("ticketing.security.jwk-set-uri", "http://idp:8080/jwks.json");
        values.put("ticketing.security.allowed-client-ids", "web-client, load-client");
        values.put("ticketing.worker.id", "");
        return values;
    }

    private static SettingsFactory factory(Map<String, Object> values) {
        TicketingProperties properties = new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("ticketing", TicketingProperties.class);
        return new SettingsFactory(properties);
    }
}
