package com.nequi.ticketing.infrastructure.adapter.in.sqs;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Configurable values shared by both consumer loops (ADR-029, messaging v2 §3): long-polling wait, messages
 * per reception, concurrency per instance, {@code maxReceiveCount} of the redrive policy (to recognise the
 * last reception), visibility backoff schedule by reception with its jitter, short visibility of poison
 * messages, and the bounded growing wait of the loop after a receive error.
 *
 * <p>The schedule entry {@code n - 1} applies after reception {@code n}; receptions beyond the schedule reuse
 * its last entry. The jitter is symmetric: a delay {@code d} becomes {@code d * (1 +/- jitter)}.
 */
public record ConsumerLoopSettings(
        String queueUrl,
        Duration waitTime,
        int maxMessages,
        int concurrency,
        int maxReceiveCount,
        List<Duration> retryBackoff,
        double retryJitter,
        Duration poisonVisibility,
        Duration errorInitialWait,
        Duration errorMaxWait) {

    /** SQS limits: at most 10 messages per reception and 20 s of long polling (SPK-016). */
    static final int SQS_MAX_MESSAGES = 10;
    static final Duration SQS_MAX_WAIT = Duration.ofSeconds(20);

    public ConsumerLoopSettings {
        Objects.requireNonNull(queueUrl, "queueUrl");
        if (queueUrl.isBlank()) {
            throw new IllegalArgumentException("queueUrl must not be blank");
        }
        Objects.requireNonNull(waitTime, "waitTime");
        if (waitTime.isNegative() || waitTime.compareTo(SQS_MAX_WAIT) > 0) {
            throw new IllegalArgumentException("waitTime must be between 0 and 20 s");
        }
        if (maxMessages < 1 || maxMessages > SQS_MAX_MESSAGES) {
            throw new IllegalArgumentException("maxMessages must be between 1 and 10");
        }
        if (concurrency < 1 || maxReceiveCount < 1) {
            throw new IllegalArgumentException("concurrency and maxReceiveCount must be positive");
        }
        retryBackoff = List.copyOf(retryBackoff);
        if (retryBackoff.isEmpty() || retryBackoff.stream().anyMatch(Duration::isNegative)) {
            throw new IllegalArgumentException("retryBackoff needs at least one non-negative delay");
        }
        if (retryJitter < 0 || retryJitter >= 1) {
            throw new IllegalArgumentException("retryJitter must be in [0, 1)");
        }
        requirePositive(poisonVisibility, "poisonVisibility");
        requirePositive(errorInitialWait, "errorInitialWait");
        requirePositive(errorMaxWait, "errorMaxWait");
    }

    /**
     * Orders queue (ADR-029): wait 20 s, 10 messages, concurrency 16, 5 receptions, backoff 5/15/30/60 s;
     * poison visibility 10 s and loop wait 1 s doubling up to 30 s (IV-004); jitter 20 % (IV-019).
     */
    public static ConsumerLoopSettings orders(String queueUrl) {
        return new ConsumerLoopSettings(queueUrl, Duration.ofSeconds(20), 10, 16, 5,
                List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofSeconds(60)),
                0.2, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(30));
    }

    /**
     * Provisioning queue (ADR-029): wait 20 s, 1 message, concurrency 1, 5 receptions, backoff
     * 30/60/120/240 s; poison visibility 10 s and loop wait 1 s doubling up to 30 s (IV-004); jitter 20 %
     * (IV-019).
     */
    public static ConsumerLoopSettings provisioning(String queueUrl) {
        return new ConsumerLoopSettings(queueUrl, Duration.ofSeconds(20), 1, 1, 5,
                List.of(Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(240)),
                0.2, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(30));
    }

    static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
