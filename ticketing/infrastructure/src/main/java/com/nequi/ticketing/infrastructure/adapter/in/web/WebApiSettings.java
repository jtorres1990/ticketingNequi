package com.nequi.ticketing.infrastructure.adapter.in.web;

import java.time.Duration;
import java.util.Objects;

/**
 * Configurable values of the HTTP API (CMP-001, CMP-016, CMP-017). {@link #DEPLOYED} holds the approved
 * defaults: base path {@code /api/v1} (OpenAPI v2 {@code servers}, IV-004), in-memory body limit of 256 KB
 * (ADR-032), per-subject rate limit of API-004 of 10 requests per 10 s (ADR-032, IV-005) and the
 * {@code Retry-After} of a temporary unavailability after bounded retries, 1 s (ADR-035, IV-004). The
 * bootstrap module binds them from configuration (INC-010).
 */
public record WebApiSettings(
        String basePath,
        int maximumBodyBytes,
        int purchaseRateLimit,
        Duration purchaseRateLimitPeriod,
        Duration unavailableRetryAfter) {

    public static final WebApiSettings DEPLOYED = new WebApiSettings(
            "/api/v1",
            256 * 1024,
            10,
            Duration.ofSeconds(10),
            Duration.ofSeconds(1));

    public WebApiSettings {
        Objects.requireNonNull(basePath, "basePath");
        if (!basePath.startsWith("/") || basePath.endsWith("/")) {
            throw new IllegalArgumentException("basePath must start with '/' and must not end with '/'");
        }
        if (maximumBodyBytes < 1 || purchaseRateLimit < 1) {
            throw new IllegalArgumentException("maximumBodyBytes and purchaseRateLimit must be positive");
        }
        requirePositive(purchaseRateLimitPeriod, "purchaseRateLimitPeriod");
        requirePositive(unavailableRetryAfter, "unavailableRetryAfter");
    }

    String path(String relative) {
        return basePath + relative;
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
