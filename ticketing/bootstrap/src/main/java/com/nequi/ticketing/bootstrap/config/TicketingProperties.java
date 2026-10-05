package com.nequi.ticketing.bootstrap.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration by environment of the {@code ticketing} process (CMP-021, TC-012, plan Annex A). Every member is
 * optional in the binding: an absent tuning value takes the approved default of its settings record
 * ({@code ApiUseCaseSettings.DEPLOYED}, {@code WorkerUseCaseSettings.deployed}, {@code ...Settings.deployed}),
 * so the approved values live in one place and this class never restates them ({@link SettingsFactory} does the
 * mapping). The connection values without a default (role, region, table, queues, issuer, Payment Mock) are
 * required for the role that uses them and are validated at startup. Secrets (Payment Mock API key, AWS
 * credentials) only arrive through the environment, never through versioned configuration (ADR-032).
 */
@ConfigurationProperties("ticketing")
public record TicketingProperties(
        Role role,
        Aws aws,
        Orders orders,
        Inventory inventory,
        Sharding sharding,
        Reversals reversals,
        Api api,
        Worker worker,
        Scheduler scheduler,
        DynamoDb dynamodb,
        Sqs sqs,
        Payment payment,
        Web web,
        Security security,
        Observability observability) {

    /** Process role (ADR-037: two services of the same image). */
    public enum Role { API, WORKER }

    public record Aws(String region) {
    }

    /** IV-012, IV-015: maximum of Tickets per Order (10) and payment cutoff margin (15 s). */
    public record Orders(Integer maximumTicketsPerOrder, Duration paymentCutoff) {
    }

    /** FG-002, ADR-024: inventory limits and provisioning batch size. */
    public record Inventory(Integer maximumCapacity, Integer maximumSections, Integer maximumRows,
            Integer maximumSeatsPerRow, Integer maximumComplimentaryRanges, Integer provisioningBatchSize) {
    }

    /** ADR-022, IV-015: availability divisor and maximum, RESV#, REVERSAL# and PENDQ# shards. */
    public record Sharding(Integer availabilityShardDivisor, Integer maximumAvailabilityShards,
            Integer reservationShards, Integer reversalShards, Integer pendingEnqueueShards) {
    }

    /** ADR-025, IV-015: reversal backoff (the last delay repeats) and maximum attempts. */
    public record Reversals(List<Duration> delays, Integer maximumAttempts) {
    }

    /** IV-004, ADR-027, ADR-035, ADR-040: values of the api use cases. */
    public record Api(Duration idempotencyRetention, Duration conflictRetryAfter, Duration doubleFailureRetryAfter,
            Duration minimumRetryAfter, Integer defaultEventPageSize, Integer maximumEventPageSize,
            Integer defaultAvailabilityPageSize, Integer maximumAvailabilityPageSize, Duration availableCountCacheTtl,
            Integer soldOutProbeConcurrency) {
    }

    /** ADR-024, ADR-026, ADR-028, ADR-029, IV-004, IV-015: values of the worker use cases. */
    public record Worker(String id, Duration paymentLeaseDuration, Duration authorizationMargin,
            Duration provisioningLeaseDuration, Duration stalledProvisioningThreshold,
            Integer maximumProvisioningRepublications, Duration republishAge, Integer expirationConcurrency,
            Integer republishConcurrency, Integer reversalConcurrency, Integer cleanupConcurrency,
            Integer maximumReevaluations, Integer maximumVerificationRepairs) {
    }

    /** ADR-028, IV-004, IV-021: periods and bounded failure waits of the four periodic processes. */
    public record Scheduler(Process expiration, Process republish, Process reversal, Process provisioningCleanup) {
    }

    public record Process(Duration period, Duration failureInitialWait, Duration failureMaxWait) {
    }

    /** ADR-023, ADR-024, ADR-039, ADR-040, IV-004, IV-017: DynamoDB connection and adapter values. */
    public record DynamoDb(URI endpoint, String tableName, Integer transactionConflictRetries,
            Duration conflictBackoffBase, Duration conflictBackoffMaximum, Double conflictJitter,
            Integer batchWriteSize, Integer batchWriteParallelism, Integer batchReadSize, Integer batchReadParallelism,
            Integer unprocessedRetries, Duration unprocessedBackoffBase, Duration unprocessedBackoffMaximum,
            Integer probeWaveSize, Integer countConcurrency, Long enabledEventCacheSize) {
    }

    /** ADR-026, ADR-029, ADR-035, IV-004, IV-019: SQS connection, publication and consumption values. */
    public record Sqs(URI endpoint, String ordersQueueUrl, String provisioningQueueUrl, Publication publication,
            OrdersConsumer orders, ProvisioningConsumer provisioning) {
    }

    public record Publication(Duration attemptTimeout, Integer maxAttempts, Duration budget, Duration backoffBase,
            Double backoffJitter, Circuit circuit) {
    }

    public record OrdersConsumer(Loop loop, Duration processingCap) {
    }

    public record ProvisioningConsumer(Loop loop, Duration heartbeatInterval, Duration heartbeatVisibility,
            Duration noProgressWindow) {
    }

    public record Loop(Duration waitTime, Integer maxMessages, Integer concurrency, Integer maxReceiveCount,
            List<Duration> retryBackoff, Double retryJitter, Duration poisonVisibility, Duration errorInitialWait,
            Duration errorMaxWait) {
    }

    /** ADR-035: circuit breaker values (slow calls only for the Payment Mock). */
    public record Circuit(Integer slidingWindowSize, Integer minimumNumberOfCalls, Float failureRateThreshold,
            Duration openDuration, Integer halfOpenProbeCalls, Duration slowCallDurationThreshold,
            Float slowCallRateThreshold) {
    }

    /** ADR-030, ADR-035, IV-004: Payment Mock connection (the API key only from the environment) and policies. */
    public record Payment(URI baseUrl, String apiKey, Duration authorizationTimeout, Integer authorizationRetries,
            Duration retryBackoffBase, Duration retryBackoffMax, Double retryJitter, Duration cancellationTimeout,
            Circuit circuit) {

        @Override
        public String toString() {
            return "Payment[baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null ? "null" : "***") + "]";
        }
    }

    /** ADR-032, IV-004, IV-005, IV-020: HTTP guards. */
    public record Web(String basePath, Integer maximumBodyBytes, Integer purchaseRateLimit,
            Duration purchaseRateLimitPeriod, Duration unavailableRetryAfter) {
    }

    /** ADR-032, ADR-033: access token validation (issuer and JWK set URL may differ). */
    public record Security(String issuer, URI jwkSetUri, Set<String> allowedClientIds, List<String> jwsAlgorithms,
            Duration clockSkew, String subjectClaim, String groupsClaim, String tokenUseClaim, String expectedTokenUse,
            String clientIdClaim) {
    }

    /** CMP-018: expiration lag threshold (15 s) and log queue bound (IV-023). */
    public record Observability(Duration expirationLagThreshold, Integer logQueueCapacity) {
    }
}
