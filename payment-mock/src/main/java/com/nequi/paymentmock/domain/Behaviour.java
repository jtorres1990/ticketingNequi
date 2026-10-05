package com.nequi.paymentmock.domain;

/**
 * Behaviour of an outcome rule, validated strictly per type (PM-IV-007). Optional fields are kept exactly as
 * configured (null when absent) so that the rule is echoed and listed as created.
 *
 * <ul>
 *   <li>{@code APPROVE}, {@code DEFINITIVE_ERROR}: no other field;</li>
 *   <li>{@code DECLINE}: optional {@code reasonCode};</li>
 *   <li>{@code TRANSIENT_THEN_OUTCOME}: mandatory {@code transientFailures} (&ge; 0) and {@code finalOutcome};
 *       {@code reasonCode} only when {@code finalOutcome = DECLINED};</li>
 *   <li>{@code LATENCY}: mandatory {@code addedLatencyMs} (0..60000); optional {@code finalOutcome};
 *       {@code reasonCode} only when {@code finalOutcome = DECLINED}.</li>
 * </ul>
 * A configured {@code reasonCode} must be one of {@link ReasonCode#RULE_CONFIGURABLE}.
 */
public record Behaviour(BehaviourType type, Long transientFailures, Outcome finalOutcome, Integer addedLatencyMs,
        ReasonCode reasonCode) {

    public static final int MAX_ADDED_LATENCY_MS = 60_000;

    public Behaviour {
        if (type == null) {
            throw new InvalidConfigurationException("behaviour.type is required");
        }
        if (reasonCode != null && !ReasonCode.RULE_CONFIGURABLE.contains(reasonCode)) {
            throw new InvalidConfigurationException("behaviour.reasonCode is not configurable");
        }
        switch (type) {
            case APPROVE, DEFINITIVE_ERROR -> {
                forbid(transientFailures, "transientFailures", type);
                forbid(finalOutcome, "finalOutcome", type);
                forbid(addedLatencyMs, "addedLatencyMs", type);
                forbid(reasonCode, "reasonCode", type);
            }
            case DECLINE -> {
                forbid(transientFailures, "transientFailures", type);
                forbid(finalOutcome, "finalOutcome", type);
                forbid(addedLatencyMs, "addedLatencyMs", type);
            }
            case TRANSIENT_THEN_OUTCOME -> {
                require(transientFailures, "transientFailures", type);
                require(finalOutcome, "finalOutcome", type);
                forbid(addedLatencyMs, "addedLatencyMs", type);
                if (transientFailures < 0) {
                    throw new InvalidConfigurationException("behaviour.transientFailures must be >= 0");
                }
                reasonOnlyWhenDeclined(finalOutcome, reasonCode);
            }
            case LATENCY -> {
                require(addedLatencyMs, "addedLatencyMs", type);
                forbid(transientFailures, "transientFailures", type);
                if (addedLatencyMs < 0 || addedLatencyMs > MAX_ADDED_LATENCY_MS) {
                    throw new InvalidConfigurationException("behaviour.addedLatencyMs must be between 0 and 60000");
                }
                reasonOnlyWhenDeclined(finalOutcome, reasonCode);
            }
        }
    }

    public static Behaviour approve() {
        return new Behaviour(BehaviourType.APPROVE, null, null, null, null);
    }

    public static Behaviour decline(ReasonCode reasonCode) {
        return new Behaviour(BehaviourType.DECLINE, null, null, null, reasonCode);
    }

    public static Behaviour definitiveError() {
        return new Behaviour(BehaviourType.DEFINITIVE_ERROR, null, null, null, null);
    }

    public static Behaviour transientThenOutcome(long transientFailures, Outcome finalOutcome, ReasonCode reasonCode) {
        return new Behaviour(BehaviourType.TRANSIENT_THEN_OUTCOME, transientFailures, finalOutcome, null, reasonCode);
    }

    public static Behaviour latency(int addedLatencyMs, Outcome finalOutcome, ReasonCode reasonCode) {
        return new Behaviour(BehaviourType.LATENCY, null, finalOutcome, addedLatencyMs, reasonCode);
    }

    /** Decline reason produced by this behaviour: the configured one or {@link ReasonCode#DEFAULT_DECLINE}. */
    public ReasonCode declineReason() {
        return reasonCode != null ? reasonCode : ReasonCode.DEFAULT_DECLINE;
    }

    private static void forbid(Object value, String field, BehaviourType type) {
        if (value != null) {
            throw new InvalidConfigurationException("behaviour." + field + " is not allowed for " + type);
        }
    }

    private static void require(Object value, String field, BehaviourType type) {
        if (value == null) {
            throw new InvalidConfigurationException("behaviour." + field + " is required for " + type);
        }
    }

    private static void reasonOnlyWhenDeclined(Outcome finalOutcome, ReasonCode reasonCode) {
        if (reasonCode != null && finalOutcome != Outcome.DECLINED) {
            throw new InvalidConfigurationException("behaviour.reasonCode is allowed only when finalOutcome is DECLINED");
        }
    }
}
