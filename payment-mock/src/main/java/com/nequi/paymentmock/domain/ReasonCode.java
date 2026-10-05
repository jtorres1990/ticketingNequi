package com.nequi.paymentmock.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Decline reasons of the contract. Rules may configure only {@link #RULE_CONFIGURABLE}; {@link #PERCENTAGE_DECLINED}
 * and {@link #ATTEMPT_CANCELLED} are reserved to the mock (PM-IV-007).
 */
public enum ReasonCode {
    CARD_DECLINED,
    INSUFFICIENT_FUNDS,
    RULE_DECLINED,
    PERCENTAGE_DECLINED,
    ATTEMPT_CANCELLED;

    public static final Set<ReasonCode> RULE_CONFIGURABLE = EnumSet.of(CARD_DECLINED, INSUFFICIENT_FUNDS, RULE_DECLINED);

    /** Reason used when a rule or the default outcome declines without an explicit reason (PM-IV-007). */
    public static final ReasonCode DEFAULT_DECLINE = RULE_DECLINED;
}
