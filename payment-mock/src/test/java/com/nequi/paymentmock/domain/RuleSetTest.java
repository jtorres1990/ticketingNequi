package com.nequi.paymentmock.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** PM-IV-006 and PM-IV-008 on the immutable configuration. */
class RuleSetTest {

    @Test
    void effectiveOrderIsByMatcherPrecedenceThenMostRecentFirst() {
        RuleSet rules = RuleSet.EMPTY
                .add(OutcomeRule.create(1, MatchField.EVENT_ID, "e", Behaviour.approve()))
                .add(OutcomeRule.create(2, MatchField.ORDER_ID, "o", Behaviour.approve()))
                .add(OutcomeRule.create(3, MatchField.CUSTOMER_REF, "c", Behaviour.approve()))
                .add(OutcomeRule.create(4, MatchField.TICKET_ID, "t", Behaviour.approve()))
                .add(OutcomeRule.create(5, MatchField.ORDER_ID, "o", Behaviour.definitiveError()))
                .add(OutcomeRule.create(6, MatchField.EVENT_ID, "e2", Behaviour.approve()))
                .add(OutcomeRule.create(7, MatchField.TICKET_ID, "t2", Behaviour.approve()));

        assertThat(rules.effectiveOrder()).extracting(OutcomeRule::ruleId)
                .containsExactly("rule-5", "rule-2", "rule-7", "rule-4", "rule-3", "rule-6", "rule-1");
    }

    @Test
    void duplicatesAreAllowedAndRemovalIsIdempotent() {
        RuleSet rules = RuleSet.EMPTY
                .add(OutcomeRule.create(1, MatchField.ORDER_ID, "o", Behaviour.approve()))
                .add(OutcomeRule.create(2, MatchField.ORDER_ID, "o", Behaviour.approve()));
        assertThat(rules.effectiveOrder()).hasSize(2);

        RuleSet removed = rules.remove("rule-2");
        assertThat(removed.effectiveOrder()).extracting(OutcomeRule::ruleId).containsExactly("rule-1");
        assertThat(removed.remove("rule-2")).isSameAs(removed);
        assertThat(removed.remove("unknown")).isSameAs(removed);
        assertThat(removed.remove("rule-1").isEmpty()).isTrue();
    }

    @Test
    void ruleIdentifierDerivesFromTheSequence() {
        assertThat(OutcomeRule.create(42, MatchField.EVENT_ID, "e", Behaviour.approve()).ruleId()).isEqualTo("rule-42");
    }

    @Test
    void defaultsAreValidated() {
        assertThat(Defaults.INITIAL).isEqualTo(new Defaults(Outcome.APPROVED, 0));
        assertThat(new Defaults(Outcome.DECLINED, 100).declinePercentage()).isEqualTo(100);
        assertThatThrownBy(() -> new Defaults(Outcome.APPROVED, 101)).isInstanceOf(InvalidConfigurationException.class);
        assertThatThrownBy(() -> new Defaults(Outcome.APPROVED, -1)).isInstanceOf(InvalidConfigurationException.class);
        assertThatThrownBy(() -> new Defaults(null, 0)).isInstanceOf(NullPointerException.class);
        assertThat(MockConfiguration.INITIAL.rules().isEmpty()).isTrue();
        assertThat(MockConfiguration.INITIAL.defaults()).isEqualTo(Defaults.INITIAL);
    }

    @Test
    void payloadComparesTicketsAsASetAndHidesTheCustomerReference() {
        AttemptPayload a = AttemptPayload.of("o", "e", "secret-customer", List.of("T1", "T2"));
        AttemptPayload b = AttemptPayload.of("o", "e", "secret-customer", List.of("T2", "T1", "T2"));
        assertThat(a).isEqualTo(b);
        assertThat(a.toString()).doesNotContain("secret-customer");
        assertThat(a).isNotEqualTo(AttemptPayload.of("o", "e", "other", List.of("T1", "T2")));
    }
}
