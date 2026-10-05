package com.nequi.ticketing.architecture.fixture.infrastructure.adapter.out.sqs;

import io.github.resilience4j.ratelimiter.RateLimiter;
import org.springframework.security.core.Authentication;

/** Control fixture: a type outside the HTTP adapter that uses the rate limiter and a security type. */
public final class ForbiddenRateLimiterFixture {

    private ForbiddenRateLimiterFixture() {
    }

    public static boolean limited(RateLimiter limiter, Authentication authentication) {
        return authentication.isAuthenticated() && !limiter.acquirePermission();
    }
}
