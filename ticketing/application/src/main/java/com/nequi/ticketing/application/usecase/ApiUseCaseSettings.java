package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.domain.event.InventoryLimits;
import com.nequi.ticketing.domain.event.ShardingPolicy;
import com.nequi.ticketing.domain.order.OrderRules;
import java.time.Duration;
import java.util.Objects;

/**
 * Configurable values used by the {@code api} role use cases. {@link #DEPLOYED} holds the approved
 * defaults: ADR-024 / FG-002 inventory limits, ADR-027 idempotency retention, ADR-035 and IV-004
 * {@code Retry-After} values, OpenAPI v2 / ADR-040 page sizes and count cache age, IV-004 sold-out
 * probe concurrency, the Order rules (maximum of 10 Tickets per Order, IV-012) and the sharding of ADR-022
 * (IV-015). The bootstrap module binds them from configuration (INC-010).
 */
public record ApiUseCaseSettings(
        InventoryLimits inventoryLimits,
        Duration idempotencyRetention,
        Duration conflictRetryAfter,
        Duration doubleFailureRetryAfter,
        Duration minimumRetryAfter,
        int defaultEventPageSize,
        int maximumEventPageSize,
        int defaultAvailabilityPageSize,
        int maximumAvailabilityPageSize,
        Duration availableCountCacheTtl,
        int soldOutProbeConcurrency,
        OrderRules orderRules,
        ShardingPolicy sharding) {

    public static final ApiUseCaseSettings DEPLOYED = new ApiUseCaseSettings(
            InventoryLimits.DEPLOYED,
            Duration.ofHours(24),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            20,
            100,
            50,
            100,
            Duration.ofSeconds(1),
            8,
            OrderRules.DEPLOYED,
            ShardingPolicy.DEPLOYED);

    public ApiUseCaseSettings {
        Objects.requireNonNull(inventoryLimits, "inventoryLimits");
        Objects.requireNonNull(orderRules, "orderRules");
        Objects.requireNonNull(sharding, "sharding");
        requirePositive(idempotencyRetention, "idempotencyRetention");
        requirePositive(conflictRetryAfter, "conflictRetryAfter");
        requirePositive(doubleFailureRetryAfter, "doubleFailureRetryAfter");
        requirePositive(minimumRetryAfter, "minimumRetryAfter");
        requirePositive(availableCountCacheTtl, "availableCountCacheTtl");
        if (defaultEventPageSize < 1 || defaultEventPageSize > maximumEventPageSize
                || defaultAvailabilityPageSize < 1 || defaultAvailabilityPageSize > maximumAvailabilityPageSize
                || soldOutProbeConcurrency < 1) {
            throw new IllegalArgumentException("page sizes and concurrency must be positive and consistent");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
