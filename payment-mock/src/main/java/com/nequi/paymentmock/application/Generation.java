package com.nequi.paymentmock.application;

import com.nequi.paymentmock.domain.MockConfiguration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * One generation of the volatile state (PM-IV-014): configuration (rules and defaults) and payment attempts
 * (authorizations, cancellations and transient progress). {@code POST /control/reset} replaces the whole generation
 * atomically; an operation works against the generation it captured when it started, so nothing started before a
 * reset is visible after it.
 *
 * <p>Attempt transitions are applied with {@link ConcurrentHashMap#compute}, whose remapping function runs exactly
 * once per call and atomically per key (PM-SPK-005): one linearizable boundary per {@code paymentAttemptId}.
 */
final class Generation {

    private final AtomicReference<MockConfiguration> configuration = new AtomicReference<>(MockConfiguration.INITIAL);
    private final ConcurrentHashMap<String, AttemptEntry> attempts = new ConcurrentHashMap<>();

    MockConfiguration configuration() {
        return configuration.get();
    }

    /** Lock-free atomic update; {@code change} must be a pure function. */
    MockConfiguration updateConfiguration(UnaryOperator<MockConfiguration> change) {
        return configuration.updateAndGet(change);
    }

    ConcurrentHashMap<String, AttemptEntry> attempts() {
        return attempts;
    }
}
