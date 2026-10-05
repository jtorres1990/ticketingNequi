package com.nequi.paymentmock.application;

import com.nequi.paymentmock.domain.Behaviour;
import com.nequi.paymentmock.domain.Defaults;
import com.nequi.paymentmock.domain.MatchField;
import com.nequi.paymentmock.domain.OutcomeRule;
import com.nequi.paymentmock.domain.RuleSet;
import com.nequi.paymentmock.domain.Attempt;
import com.nequi.paymentmock.domain.Cancellation;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

/** Control API use cases: rules, defaults, inspection and reset (API-103 to API-110). Non-blocking. */
public final class ControlService {

    private final MockState state;

    public ControlService(MockState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    /** API-103: rules in effective (precedence) order. */
    public List<OutcomeRule> listRules() {
        return state.current().configuration().rules().effectiveOrder();
    }

    /** API-104: the identifier is assigned once, outside the retry loop of the atomic update. */
    public OutcomeRule createRule(MatchField field, String value, Behaviour behaviour) {
        OutcomeRule rule = OutcomeRule.create(state.nextRuleSequence(), field, value, behaviour);
        state.current().updateConfiguration(configuration -> configuration.withRules(configuration.rules().add(rule)));
        return rule;
    }

    /** API-105. */
    public void deleteAllRules() {
        state.current().updateConfiguration(configuration -> configuration.withRules(RuleSet.EMPTY));
    }

    /** API-106: idempotent. */
    public void deleteRule(String ruleId) {
        state.current().updateConfiguration(configuration -> configuration.withRules(
                configuration.rules().remove(ruleId)));
    }

    /** API-107: replaces the defaults as a whole (PM-IV-008). */
    public Defaults setDefaults(Defaults defaults) {
        Objects.requireNonNull(defaults, "defaults");
        return state.current().updateConfiguration(configuration -> configuration.withDefaults(defaults)).defaults();
    }

    public Defaults defaults() {
        return state.current().configuration().defaults();
    }

    /** API-108: read-only; empty when no authorization was received for the attempt. */
    public Optional<AuthorizationView> authorization(String paymentAttemptId) {
        AttemptEntry entry = state.current().attempts().get(paymentAttemptId);
        if (entry == null || !entry.attempt().hasAuthorizations()) {
            return Optional.empty();
        }
        Attempt attempt = entry.attempt();
        return Optional.of(new AuthorizationView(paymentAttemptId, attempt.invocations(), attempt.result(),
                attempt.cancelledAfterResult()));
    }

    /** API-109: read-only; empty when no cancellation was received for the attempt. */
    public Optional<CancellationView> cancellation(String paymentAttemptId) {
        AttemptEntry entry = state.current().attempts().get(paymentAttemptId);
        if (entry == null || entry.attempt().cancellation() == null) {
            return Optional.empty();
        }
        Cancellation cancellation = entry.attempt().cancellation();
        return Optional.of(new CancellationView(paymentAttemptId, cancellation.received(), cancellation.status(),
                cancellation.firstReceivedAt()));
    }

    /** API-110. */
    public void reset() {
        state.reset();
    }
}
