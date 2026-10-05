package com.nequi.ticketing.bootstrap.it;

import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * BlockHound allowance for an internal of Spring Boot (NFR-003, plan section 4.3), documented in the INC-010 report
 * and submitted to human review (IV-024); no project code is exempted.
 *
 * <p>{@code PrometheusExemplarsAutoConfiguration$LazyTracingSpanContext#currentSpan} resolves the tracer lazily
 * through a {@code SingletonSupplier}, whose first initialization takes a {@code ReentrantLock} for a few
 * instructions without I/O; when the first two observations of the process stop at the same time on event-loop
 * threads, one of them parks for that instant. After the first initialization the supplier is lock-free.
 */
public final class SpringBootBlockHoundIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        builder.allowBlockingCallsInside(
                "org.springframework.boot.micrometer.tracing.autoconfigure.prometheus."
                        + "PrometheusExemplarsAutoConfiguration$LazyTracingSpanContext",
                "currentSpan");
    }
}
