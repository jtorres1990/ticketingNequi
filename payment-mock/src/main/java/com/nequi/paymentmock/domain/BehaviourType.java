package com.nequi.paymentmock.domain;

/** Selectable behaviours of ADR-030. */
public enum BehaviourType {
    APPROVE,
    DECLINE,
    DEFINITIVE_ERROR,
    TRANSIENT_THEN_OUTCOME,
    LATENCY
}
