package com.nequi.ticketing.bootstrap.config;

import com.nequi.ticketing.application.usecase.ApiUseCaseSettings;
import com.nequi.ticketing.application.usecase.WorkerUseCaseSettings;
import com.nequi.ticketing.bootstrap.config.TicketingProperties.Circuit;
import com.nequi.ticketing.bootstrap.config.TicketingProperties.Loop;
import com.nequi.ticketing.bootstrap.config.TicketingProperties.Process;
import com.nequi.ticketing.bootstrap.config.TicketingProperties.Role;
import com.nequi.ticketing.domain.event.InventoryLimits;
import com.nequi.ticketing.domain.event.ShardingPolicy;
import com.nequi.ticketing.domain.order.OrderRules;
import com.nequi.ticketing.domain.order.ReversalSchedule;
import com.nequi.ticketing.infrastructure.adapter.in.scheduler.PeriodicProcessSettings;
import com.nequi.ticketing.infrastructure.adapter.in.scheduler.WorkerSchedulerSettings;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.ConsumerLoopSettings;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.OrderConsumerSettings;
import com.nequi.ticketing.infrastructure.adapter.in.sqs.ProvisioningConsumerSettings;
import com.nequi.ticketing.infrastructure.adapter.in.web.AccessTokenSettings;
import com.nequi.ticketing.infrastructure.adapter.in.web.WebApiSettings;
import com.nequi.ticketing.infrastructure.adapter.out.dynamodb.DynamoDbAdapterSettings;
import com.nequi.ticketing.infrastructure.adapter.out.dynamodb.DynamoDbConnectionSettings;
import com.nequi.ticketing.infrastructure.adapter.out.payment.PaymentGatewaySettings;
import com.nequi.ticketing.infrastructure.adapter.out.resilience.CircuitBreakerSettings;
import com.nequi.ticketing.infrastructure.adapter.out.sqs.SqsPublisherSettings;
import com.nequi.ticketing.infrastructure.adapter.sqs.SqsConnectionSettings;
import com.nequi.ticketing.infrastructure.observability.ObservabilitySettings;
import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Maps {@link TicketingProperties} to the settings records of the use cases and adapters (CMP-021). Each absent
 * value takes the approved default of the corresponding record, so the defaults are exactly the approved values
 * (contract §3.8, plan Annex A, IV-004, IV-012, IV-015, IV-017, IV-019, IV-020, IV-021). Missing connection values
 * fail the startup with the property and environment variable to set.
 */
public final class SettingsFactory {

    /** Placeholder used only to read the approved defaults of records that need a queue URL or a base URL. */
    private static final String PLACEHOLDER = "placeholder";

    private final TicketingProperties properties;

    public SettingsFactory(TicketingProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public Role role() {
        if (properties.role() == null) {
            throw missing("ticketing.role", "TICKETING_ROLE", "api or worker");
        }
        return properties.role();
    }

    public OrderRules orderRules() {
        var orders = properties.orders();
        OrderRules deployed = OrderRules.DEPLOYED;
        if (orders == null) {
            return deployed;
        }
        return new OrderRules(or(orders.maximumTicketsPerOrder(), deployed.maximumTicketsPerOrder()),
                or(orders.paymentCutoff(), deployed.paymentCutoff()));
    }

    public InventoryLimits inventoryLimits() {
        var inventory = properties.inventory();
        InventoryLimits deployed = InventoryLimits.DEPLOYED;
        if (inventory == null) {
            return deployed;
        }
        return new InventoryLimits(or(inventory.maximumCapacity(), deployed.maximumCapacity()),
                or(inventory.maximumSections(), deployed.maximumSections()),
                or(inventory.maximumRows(), deployed.maximumRows()),
                or(inventory.maximumSeatsPerRow(), deployed.maximumSeatsPerRow()),
                or(inventory.maximumComplimentaryRanges(), deployed.maximumComplimentaryRanges()),
                or(inventory.provisioningBatchSize(), deployed.provisioningBatchSize()));
    }

    public ShardingPolicy sharding() {
        var sharding = properties.sharding();
        ShardingPolicy deployed = ShardingPolicy.DEPLOYED;
        if (sharding == null) {
            return deployed;
        }
        return new ShardingPolicy(or(sharding.availabilityShardDivisor(), deployed.availabilityShardDivisor()),
                or(sharding.maximumAvailabilityShards(), deployed.maximumAvailabilityShards()),
                or(sharding.reservationShards(), deployed.reservationShards()),
                or(sharding.reversalShards(), deployed.reversalShards()),
                or(sharding.pendingEnqueueShards(), deployed.pendingEnqueueShards()));
    }

    public ReversalSchedule reversalSchedule() {
        var reversals = properties.reversals();
        ReversalSchedule deployed = ReversalSchedule.DEPLOYED;
        if (reversals == null) {
            return deployed;
        }
        return new ReversalSchedule(reversals.delays() == null || reversals.delays().isEmpty()
                ? deployed.delays() : reversals.delays(), or(reversals.maximumAttempts(), deployed.maximumAttempts()));
    }

    public ApiUseCaseSettings apiUseCases() {
        var api = properties.api() == null
                ? new TicketingProperties.Api(null, null, null, null, null, null, null, null, null, null)
                : properties.api();
        ApiUseCaseSettings deployed = ApiUseCaseSettings.DEPLOYED;
        return new ApiUseCaseSettings(inventoryLimits(),
                or(api.idempotencyRetention(), deployed.idempotencyRetention()),
                or(api.conflictRetryAfter(), deployed.conflictRetryAfter()),
                or(api.doubleFailureRetryAfter(), deployed.doubleFailureRetryAfter()),
                or(api.minimumRetryAfter(), deployed.minimumRetryAfter()),
                or(api.defaultEventPageSize(), deployed.defaultEventPageSize()),
                or(api.maximumEventPageSize(), deployed.maximumEventPageSize()),
                or(api.defaultAvailabilityPageSize(), deployed.defaultAvailabilityPageSize()),
                or(api.maximumAvailabilityPageSize(), deployed.maximumAvailabilityPageSize()),
                or(api.availableCountCacheTtl(), deployed.availableCountCacheTtl()),
                or(api.soldOutProbeConcurrency(), deployed.soldOutProbeConcurrency()),
                orderRules(),
                sharding());
    }

    /** Worker identifier: {@code ticketing.worker.id} or {@code worker-<random>} per process (ADR-031 actor). */
    public String workerId() {
        var worker = properties.worker();
        String configured = worker == null ? null : worker.id();
        return blank(configured) ? "worker-" + UUID.randomUUID().toString().substring(0, 8) : configured.trim();
    }

    public WorkerUseCaseSettings workerUseCases(String workerId) {
        var worker = properties.worker() == null
                ? new TicketingProperties.Worker(null, null, null, null, null, null, null, null, null, null, null,
                        null, null)
                : properties.worker();
        WorkerUseCaseSettings deployed = WorkerUseCaseSettings.deployed(workerId);
        return new WorkerUseCaseSettings(workerId,
                or(worker.paymentLeaseDuration(), deployed.paymentLeaseDuration()),
                or(worker.authorizationMargin(), deployed.authorizationMargin()),
                or(worker.provisioningLeaseDuration(), deployed.provisioningLeaseDuration()),
                inventoryLimits(),
                or(worker.stalledProvisioningThreshold(), deployed.stalledProvisioningThreshold()),
                or(worker.maximumProvisioningRepublications(), deployed.maximumProvisioningRepublications()),
                or(worker.republishAge(), deployed.republishAge()),
                or(worker.expirationConcurrency(), deployed.expirationConcurrency()),
                or(worker.republishConcurrency(), deployed.republishConcurrency()),
                or(worker.reversalConcurrency(), deployed.reversalConcurrency()),
                or(worker.cleanupConcurrency(), deployed.cleanupConcurrency()),
                or(worker.maximumReevaluations(), deployed.maximumReevaluations()),
                orderRules(),
                reversalSchedule(),
                or(worker.maximumVerificationRepairs(), deployed.maximumVerificationRepairs()),
                sharding());
    }

    public WorkerSchedulerSettings scheduler() {
        var scheduler = properties.scheduler();
        WorkerSchedulerSettings deployed = WorkerSchedulerSettings.DEPLOYED;
        return new WorkerSchedulerSettings(
                process(scheduler == null ? null : scheduler.expiration(), deployed.expiration()),
                process(scheduler == null ? null : scheduler.republish(), deployed.republish()),
                process(scheduler == null ? null : scheduler.reversal(), deployed.reversal()),
                process(scheduler == null ? null : scheduler.provisioningCleanup(), deployed.provisioningCleanup()),
                sharding());
    }

    public DynamoDbConnectionSettings dynamoDbConnection() {
        var dynamodb = properties.dynamodb();
        return new DynamoDbConnectionSettings(dynamodb == null ? null : uri(dynamodb.endpoint()), region());
    }

    public DynamoDbAdapterSettings dynamoDbAdapter() {
        var dynamodb = properties.dynamodb();
        String table = dynamodb == null ? null : dynamodb.tableName();
        if (blank(table)) {
            throw missing("ticketing.dynamodb.table-name", "TICKETING_DYNAMODB_TABLE", "the physical table name");
        }
        DynamoDbAdapterSettings deployed = DynamoDbAdapterSettings.deployed(table);
        return new DynamoDbAdapterSettings(table.trim(),
                or(dynamodb.transactionConflictRetries(), deployed.transactionConflictRetries()),
                or(dynamodb.conflictBackoffBase(), deployed.conflictBackoffBase()),
                or(dynamodb.conflictBackoffMaximum(), deployed.conflictBackoffMaximum()),
                or(dynamodb.conflictJitter(), deployed.conflictJitter()),
                or(dynamodb.batchWriteSize(), deployed.batchWriteSize()),
                or(dynamodb.batchWriteParallelism(), deployed.batchWriteParallelism()),
                or(dynamodb.batchReadSize(), deployed.batchReadSize()),
                or(dynamodb.batchReadParallelism(), deployed.batchReadParallelism()),
                or(dynamodb.unprocessedRetries(), deployed.unprocessedRetries()),
                or(dynamodb.unprocessedBackoffBase(), deployed.unprocessedBackoffBase()),
                or(dynamodb.unprocessedBackoffMaximum(), deployed.unprocessedBackoffMaximum()),
                or(dynamodb.probeWaveSize(), deployed.probeWaveSize()),
                or(dynamodb.countConcurrency(), deployed.countConcurrency()),
                or(dynamodb.enabledEventCacheSize(), deployed.enabledEventCacheSize()),
                sharding());
    }

    public SqsConnectionSettings sqsConnection() {
        var sqs = properties.sqs();
        return new SqsConnectionSettings(sqs == null ? null : uri(sqs.endpoint()), region());
    }

    public String ordersQueueUrl() {
        var sqs = properties.sqs();
        String url = sqs == null ? null : sqs.ordersQueueUrl();
        if (blank(url)) {
            throw missing("ticketing.sqs.orders-queue-url", "TICKETING_SQS_ORDERS_QUEUE_URL", "the queue URL");
        }
        return url.trim();
    }

    public String provisioningQueueUrl() {
        var sqs = properties.sqs();
        String url = sqs == null ? null : sqs.provisioningQueueUrl();
        if (blank(url)) {
            throw missing("ticketing.sqs.provisioning-queue-url", "TICKETING_SQS_PROVISIONING_QUEUE_URL",
                    "the queue URL");
        }
        return url.trim();
    }

    public SqsPublisherSettings sqsPublisher() {
        var publication = properties.sqs() == null ? null : properties.sqs().publication();
        SqsPublisherSettings deployed = SqsPublisherSettings.deployed(PLACEHOLDER, PLACEHOLDER);
        String orders = ordersQueueUrl();
        String provisioning = provisioningQueueUrl();
        if (publication == null) {
            return new SqsPublisherSettings(orders, provisioning, deployed.attemptTimeout(), deployed.maxAttempts(),
                    deployed.budget(), deployed.backoffBase(), deployed.backoffJitter(), deployed.circuit());
        }
        return new SqsPublisherSettings(orders, provisioning,
                or(publication.attemptTimeout(), deployed.attemptTimeout()),
                or(publication.maxAttempts(), deployed.maxAttempts()),
                or(publication.budget(), deployed.budget()),
                or(publication.backoffBase(), deployed.backoffBase()),
                or(publication.backoffJitter(), deployed.backoffJitter()),
                circuit(publication.circuit(), deployed.circuit()));
    }

    public OrderConsumerSettings ordersConsumer() {
        var orders = properties.sqs() == null ? null : properties.sqs().orders();
        String url = ordersQueueUrl();
        OrderConsumerSettings deployed = OrderConsumerSettings.deployed(url);
        return new OrderConsumerSettings(loop(orders == null ? null : orders.loop(), deployed.loop()),
                or(orders == null ? null : orders.processingCap(), deployed.processingCap()));
    }

    public ProvisioningConsumerSettings provisioningConsumer() {
        var provisioning = properties.sqs() == null ? null : properties.sqs().provisioning();
        String url = provisioningQueueUrl();
        ProvisioningConsumerSettings deployed = ProvisioningConsumerSettings.deployed(url);
        if (provisioning == null) {
            return deployed;
        }
        return new ProvisioningConsumerSettings(loop(provisioning.loop(), deployed.loop()),
                or(provisioning.heartbeatInterval(), deployed.heartbeatInterval()),
                or(provisioning.heartbeatVisibility(), deployed.heartbeatVisibility()),
                or(provisioning.noProgressWindow(), deployed.noProgressWindow()));
    }

    public PaymentGatewaySettings paymentGateway() {
        var payment = properties.payment();
        URI baseUrl = payment == null ? null : uri(payment.baseUrl());
        if (baseUrl == null) {
            throw missing("ticketing.payment.base-url", "TICKETING_PAYMENT_BASE_URL", "the Payment Mock base URL");
        }
        if (blank(payment.apiKey())) {
            throw missing("ticketing.payment.api-key", "TICKETING_PAYMENT_API_KEY",
                    "the Payment Mock API key (secret, environment only)");
        }
        PaymentGatewaySettings deployed = PaymentGatewaySettings.deployed(baseUrl, payment.apiKey());
        return new PaymentGatewaySettings(baseUrl, payment.apiKey(),
                or(payment.authorizationTimeout(), deployed.authorizationTimeout()),
                or(payment.authorizationRetries(), deployed.authorizationRetries()),
                or(payment.retryBackoffBase(), deployed.retryBackoffBase()),
                or(payment.retryBackoffMax(), deployed.retryBackoffMax()),
                or(payment.retryJitter(), deployed.retryJitter()),
                or(payment.cancellationTimeout(), deployed.cancellationTimeout()),
                circuit(payment.circuit(), deployed.circuit()));
    }

    public WebApiSettings web() {
        var web = properties.web();
        WebApiSettings deployed = WebApiSettings.DEPLOYED;
        if (web == null) {
            return deployed;
        }
        return new WebApiSettings(or(web.basePath(), deployed.basePath()),
                or(web.maximumBodyBytes(), deployed.maximumBodyBytes()),
                or(web.purchaseRateLimit(), deployed.purchaseRateLimit()),
                or(web.purchaseRateLimitPeriod(), deployed.purchaseRateLimitPeriod()),
                or(web.unavailableRetryAfter(), deployed.unavailableRetryAfter()));
    }

    public AccessTokenSettings accessTokens() {
        var security = properties.security();
        if (security == null || blank(security.issuer())) {
            throw missing("ticketing.security.issuer", "TICKETING_SECURITY_ISSUER", "the expected iss claim");
        }
        URI jwkSetUri = uri(security.jwkSetUri());
        if (jwkSetUri == null) {
            throw missing("ticketing.security.jwk-set-uri", "TICKETING_SECURITY_JWK_SET_URI", "the JWK set URL");
        }
        Set<String> clients = security.allowedClientIds() == null ? Set.of() : security.allowedClientIds().stream()
                .filter(client -> !blank(client)).map(String::trim).collect(Collectors.toUnmodifiableSet());
        if (clients.isEmpty()) {
            throw missing("ticketing.security.allowed-client-ids", "TICKETING_SECURITY_ALLOWED_CLIENT_IDS",
                    "the comma-separated allowed client_id values");
        }
        return new AccessTokenSettings(security.issuer().trim(), jwkSetUri, clients,
                security.jwsAlgorithms() == null || security.jwsAlgorithms().isEmpty()
                        ? AccessTokenSettings.DEFAULT_JWS_ALGORITHMS : security.jwsAlgorithms(),
                or(security.clockSkew(), AccessTokenSettings.DEFAULT_CLOCK_SKEW),
                orText(security.subjectClaim(), AccessTokenSettings.DEFAULT_SUBJECT_CLAIM),
                orText(security.groupsClaim(), AccessTokenSettings.DEFAULT_GROUPS_CLAIM),
                orText(security.tokenUseClaim(), AccessTokenSettings.DEFAULT_TOKEN_USE_CLAIM),
                orText(security.expectedTokenUse(), AccessTokenSettings.DEFAULT_EXPECTED_TOKEN_USE),
                orText(security.clientIdClaim(), AccessTokenSettings.DEFAULT_CLIENT_ID_CLAIM));
    }

    public ObservabilitySettings observability() {
        var observability = properties.observability();
        ObservabilitySettings deployed = ObservabilitySettings.DEPLOYED;
        if (observability == null) {
            return deployed;
        }
        return new ObservabilitySettings(or(observability.expirationLagThreshold(), deployed.expirationLagThreshold()),
                or(observability.logQueueCapacity(), deployed.logQueueCapacity()));
    }

    private String region() {
        String region = properties.aws() == null ? null : properties.aws().region();
        if (blank(region)) {
            throw missing("ticketing.aws.region", "AWS_REGION", "the AWS region");
        }
        return region.trim();
    }

    private static PeriodicProcessSettings process(Process configured, PeriodicProcessSettings deployed) {
        if (configured == null) {
            return deployed;
        }
        return new PeriodicProcessSettings(or(configured.period(), deployed.period()),
                or(configured.failureInitialWait(), deployed.failureInitialWait()),
                or(configured.failureMaxWait(), deployed.failureMaxWait()));
    }

    private static ConsumerLoopSettings loop(Loop configured, ConsumerLoopSettings deployed) {
        if (configured == null) {
            return deployed;
        }
        return new ConsumerLoopSettings(deployed.queueUrl(),
                or(configured.waitTime(), deployed.waitTime()),
                or(configured.maxMessages(), deployed.maxMessages()),
                or(configured.concurrency(), deployed.concurrency()),
                or(configured.maxReceiveCount(), deployed.maxReceiveCount()),
                configured.retryBackoff() == null || configured.retryBackoff().isEmpty()
                        ? deployed.retryBackoff() : configured.retryBackoff(),
                or(configured.retryJitter(), deployed.retryJitter()),
                or(configured.poisonVisibility(), deployed.poisonVisibility()),
                or(configured.errorInitialWait(), deployed.errorInitialWait()),
                or(configured.errorMaxWait(), deployed.errorMaxWait()));
    }

    private static CircuitBreakerSettings circuit(Circuit configured, CircuitBreakerSettings deployed) {
        if (configured == null) {
            return deployed;
        }
        return new CircuitBreakerSettings(or(configured.slidingWindowSize(), deployed.slidingWindowSize()),
                or(configured.minimumNumberOfCalls(), deployed.minimumNumberOfCalls()),
                or(configured.failureRateThreshold(), deployed.failureRateThresholdPercent()),
                or(configured.openDuration(), deployed.openDuration()),
                or(configured.halfOpenProbeCalls(), deployed.halfOpenProbeCalls()),
                configured.slowCallDurationThreshold() == null
                        ? deployed.slowCallDurationThreshold() : configured.slowCallDurationThreshold(),
                or(configured.slowCallRateThreshold(), deployed.slowCallRateThresholdPercent()));
    }

    private static URI uri(URI value) {
        return value == null || value.toString().isBlank() ? null : value;
    }

    private static <T> T or(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private static String orText(String value, String fallback) {
        return blank(value) ? fallback : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalStateException missing(String property, String environment, String what) {
        return new IllegalStateException("Missing required configuration " + property + " (environment variable "
                + environment + "): " + what);
    }
}
