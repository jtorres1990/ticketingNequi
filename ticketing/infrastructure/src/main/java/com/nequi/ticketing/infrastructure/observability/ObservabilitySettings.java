package com.nequi.ticketing.infrastructure.observability;

import java.time.Duration;
import java.util.Objects;

/**
 * Configurable values of CMP-018 with their defaults in {@link #DEPLOYED}:
 * <ul>
 *   <li>{@code expirationLagThreshold}: maximum target delay between {@code expiresAt} and the release of an
 *       expired Reservation, used as the threshold of the expiration lag signal (15 s; AV-003, ADR-028, plan
 *       Annex A "demora máxima objetivo (15 s, umbral de métrica)", ADR-037 alarm catalog);</li>
 *   <li>{@code logQueueCapacity}: bound of the pending structured log writes of {@link LogDispatcher} (10,000;
 *       implementation guard submitted to human review in IV-023).</li>
 * </ul>
 */
public record ObservabilitySettings(Duration expirationLagThreshold, int logQueueCapacity) {

    public static final ObservabilitySettings DEPLOYED = new ObservabilitySettings(Duration.ofSeconds(15), 10_000);

    public ObservabilitySettings {
        Objects.requireNonNull(expirationLagThreshold, "expirationLagThreshold");
        if (expirationLagThreshold.isNegative() || expirationLagThreshold.isZero()) {
            throw new IllegalArgumentException("expirationLagThreshold must be positive");
        }
        if (logQueueCapacity < 1) {
            throw new IllegalArgumentException("logQueueCapacity must be positive");
        }
    }
}
