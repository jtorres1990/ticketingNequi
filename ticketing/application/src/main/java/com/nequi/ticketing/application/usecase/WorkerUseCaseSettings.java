package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.domain.event.InventoryLimits;
import java.time.Duration;
import java.util.Objects;

/**
 * Configurable values used by the {@code worker} role use cases. {@link #deployed(String)} holds the
 * approved defaults: ADR-029 PaymentAttempt lease (45 s); IV-004 application margin of the authorization
 * deadline (2 s); ADR-024 provisioning lease (60 s), batch size (100, through the inventory limits),
 * stalled threshold (3 min) and maximum republications (3); ADR-026 sweep age (30 s); ADR-028
 * concurrencies 16 / 8 / 4 / 2. {@code workerId} identifies the worker instance (lease owner prefix and
 * audit actor, ADR-031). {@code maximumReevaluations} bounds the "re-read and re-evaluate" loop of one
 * message; exceeding it is treated as a transient failure. The bootstrap module binds them from
 * configuration (INC-010).
 */
public record WorkerUseCaseSettings(
        String workerId,
        Duration paymentLeaseDuration,
        Duration authorizationMargin,
        Duration provisioningLeaseDuration,
        InventoryLimits inventoryLimits,
        Duration stalledProvisioningThreshold,
        int maximumProvisioningRepublications,
        Duration republishAge,
        int expirationConcurrency,
        int republishConcurrency,
        int reversalConcurrency,
        int cleanupConcurrency,
        int maximumReevaluations) {

    public WorkerUseCaseSettings {
        Objects.requireNonNull(workerId, "workerId");
        if (workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        requirePositive(paymentLeaseDuration, "paymentLeaseDuration");
        requirePositive(authorizationMargin, "authorizationMargin");
        requirePositive(provisioningLeaseDuration, "provisioningLeaseDuration");
        Objects.requireNonNull(inventoryLimits, "inventoryLimits");
        requirePositive(stalledProvisioningThreshold, "stalledProvisioningThreshold");
        requirePositive(republishAge, "republishAge");
        if (maximumProvisioningRepublications < 0 || expirationConcurrency < 1 || republishConcurrency < 1
                || reversalConcurrency < 1 || cleanupConcurrency < 1 || maximumReevaluations < 1) {
            throw new IllegalArgumentException("counts and concurrencies must be positive");
        }
    }

    public static WorkerUseCaseSettings deployed(String workerId) {
        return new WorkerUseCaseSettings(
                workerId,
                Duration.ofSeconds(45),
                Duration.ofSeconds(2),
                Duration.ofSeconds(60),
                InventoryLimits.DEPLOYED,
                Duration.ofMinutes(3),
                3,
                Duration.ofSeconds(30),
                16,
                8,
                4,
                2,
                5);
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
