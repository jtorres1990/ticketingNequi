package com.nequi.paymentmock.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Immutable set of rules kept in effective order (PM-IV-006): by matcher precedence (orderId, ticketId,
 * customerRef, eventId) and, within a matcher, from the most recent to the oldest. The applicable rule is the first
 * matching rule in that order, so the most recent rule wins within a category.
 */
public final class RuleSet {

    static final Comparator<OutcomeRule> EFFECTIVE_ORDER = Comparator
            .comparing(OutcomeRule::field)
            .thenComparing(Comparator.comparingLong(OutcomeRule::sequence).reversed());

    public static final RuleSet EMPTY = new RuleSet(List.of());

    private final List<OutcomeRule> rules;

    private RuleSet(List<OutcomeRule> sortedRules) {
        this.rules = List.copyOf(sortedRules);
    }

    public RuleSet add(OutcomeRule rule) {
        List<OutcomeRule> next = new ArrayList<>(rules);
        next.add(rule);
        next.sort(EFFECTIVE_ORDER);
        return new RuleSet(next);
    }

    /** Idempotent: removing an unknown rule returns an equal set. */
    public RuleSet remove(String ruleId) {
        List<OutcomeRule> next = rules.stream().filter(rule -> !rule.ruleId().equals(ruleId)).toList();
        return next.size() == rules.size() ? this : new RuleSet(next);
    }

    public List<OutcomeRule> effectiveOrder() {
        return rules;
    }

    public Optional<OutcomeRule> select(AttemptPayload payload) {
        for (OutcomeRule rule : rules) {
            if (rule.matches(payload)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }
}
