package com.nequi.ticketing.infrastructure.adapter.in.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.internal.AtomicRateLimiter;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * CMP-017 request guard, rate limit (ADR-032, IV-005; verified in SPK-023): in-memory, non-blocking limiter per
 * authenticated subject for API-004, 10 requests per 10 s by default, with Resilience4j {@code RateLimiter}.
 * A request beyond the limit is rejected immediately (no waiting) and the wait until the next permission is
 * reported for {@code Retry-After}. Each subject gets its own limiter on its first request; a limiter idle for
 * a whole period is discarded, which is equivalent to keeping it because its permissions would already be
 * replenished, so memory is bounded by the subjects active in the last period. The limiter is approximate
 * with several instances; the authoritative control is at the edge (ADR-032, ADR-037).
 */
public final class SubjectRateLimiter {

    private final RateLimiterConfig config;
    private final LongSupplier nanoTime;
    private final Cache<String, AtomicRateLimiter> limiters;

    public SubjectRateLimiter(WebApiSettings settings) {
        this(settings, System::nanoTime);
    }

    SubjectRateLimiter(WebApiSettings settings, LongSupplier nanoTime) {
        Objects.requireNonNull(settings, "settings");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.config = RateLimiterConfig.custom()
                .limitForPeriod(settings.purchaseRateLimit())
                .limitRefreshPeriod(settings.purchaseRateLimitPeriod())
                .timeoutDuration(Duration.ZERO)
                .writableStackTraceEnabled(false)
                .build();
        this.limiters = Caffeine.newBuilder()
                .expireAfterAccess(settings.purchaseRateLimitPeriod())
                .ticker(nanoTime::getAsLong)
                .executor(Runnable::run)
                .build();
    }

    /** Empty when the request may proceed; otherwise the wait until the next permission of {@code subject}. */
    Optional<Duration> tryAcquire(String subject) {
        AtomicRateLimiter limiter = limiters.get(Objects.requireNonNull(subject, "subject"),
                name -> new AtomicRateLimiter(name, config, nanoTime::getAsLong));
        if (limiter.acquirePermission()) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofNanos(Math.max(0, limiter.getDetailedMetrics().getNanosToWait())));
    }

    long trackedSubjects() {
        limiters.cleanUp();
        return limiters.estimatedSize();
    }
}
