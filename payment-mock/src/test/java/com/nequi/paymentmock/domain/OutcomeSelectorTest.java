package com.nequi.paymentmock.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** ADR-030 selection: matchers, precedence between categories, tie-break within a category, percentage, default. */
class OutcomeSelectorTest {

    static final AttemptPayload PAYLOAD = AttemptPayload.of("order-1", "event-1", "customer-1", List.of("T1", "T2"));
    static final String ID = "a-1"; // bucket 41

    static MockConfiguration rules(OutcomeRule... rules) {
        RuleSet set = RuleSet.EMPTY;
        for (OutcomeRule rule : rules) {
            set = set.add(rule);
        }
        return MockConfiguration.INITIAL.withRules(set);
    }

    static OutcomeRule rule(long sequence, MatchField field, String value, Behaviour behaviour) {
        return OutcomeRule.create(sequence, field, value, behaviour);
    }

    static AuthorizationResult decided(MockConfiguration configuration) {
        OutcomeSelector.Plan plan = OutcomeSelector.select(configuration, ID, PAYLOAD);
        assertThat(plan).isInstanceOf(OutcomeSelector.Decide.class);
        return ((OutcomeSelector.Decide) plan).result();
    }

    @Test
    void eachMatcherSelectsItsRule() {
        Behaviour decline = Behaviour.decline(ReasonCode.CARD_DECLINED);
        AuthorizationResult cardDeclined = AuthorizationResult.declined(ReasonCode.CARD_DECLINED);
        assertThat(decided(rules(rule(1, MatchField.ORDER_ID, "order-1", decline)))).isEqualTo(cardDeclined);
        assertThat(decided(rules(rule(1, MatchField.TICKET_ID, "T1", decline)))).isEqualTo(cardDeclined);
        assertThat(decided(rules(rule(1, MatchField.TICKET_ID, "T2", decline)))).isEqualTo(cardDeclined);
        assertThat(decided(rules(rule(1, MatchField.CUSTOMER_REF, "customer-1", decline)))).isEqualTo(cardDeclined);
        assertThat(decided(rules(rule(1, MatchField.EVENT_ID, "event-1", decline)))).isEqualTo(cardDeclined);
    }

    @Test
    void nonMatchingAndCaseDifferentValuesDoNotApply() {
        Behaviour decline = Behaviour.decline(null);
        assertThat(decided(rules(rule(1, MatchField.ORDER_ID, "ORDER-1", decline),
                rule(2, MatchField.TICKET_ID, "T3", decline),
                rule(3, MatchField.CUSTOMER_REF, "customer-2", decline),
                rule(4, MatchField.EVENT_ID, "event-1 ", decline),
                rule(5, MatchField.ORDER_ID, "event-1", decline)))).isEqualTo(AuthorizationResult.APPROVED);
    }

    static Stream<Arguments> precedencePairs() {
        // Every ordered pair (higher, lower) of distinct categories: 4 x 3 combinations, created in both orders.
        Stream.Builder<Arguments> pairs = Stream.builder();
        for (MatchField higher : MatchField.values()) {
            for (MatchField lower : MatchField.values()) {
                if (higher != lower) {
                    pairs.add(Arguments.of(higher, lower));
                }
            }
        }
        return pairs.build();
    }

    static String value(MatchField field) {
        return switch (field) {
            case ORDER_ID -> "order-1";
            case TICKET_ID -> "T2";
            case CUSTOMER_REF -> "customer-1";
            case EVENT_ID -> "event-1";
        };
    }

    @ParameterizedTest(name = "{0} vs {1}")
    @MethodSource("precedencePairs")
    void higherCategoryWinsWhateverTheCreationOrder(MatchField first, MatchField second) {
        MatchField winner = first.ordinal() < second.ordinal() ? first : second;
        OutcomeRule a = rule(1, first, value(first), Behaviour.decline(ReasonCode.CARD_DECLINED));
        OutcomeRule b = rule(2, second, value(second), Behaviour.decline(ReasonCode.INSUFFICIENT_FUNDS));
        AuthorizationResult expected = winner == first
                ? AuthorizationResult.declined(ReasonCode.CARD_DECLINED)
                : AuthorizationResult.declined(ReasonCode.INSUFFICIENT_FUNDS);
        assertThat(decided(rules(a, b))).isEqualTo(expected);
    }

    @Test
    void mostRecentRuleWinsWithinACategoryIncludingDifferentTickets() {
        assertThat(decided(rules(rule(1, MatchField.ORDER_ID, "order-1", Behaviour.decline(null)),
                rule(2, MatchField.ORDER_ID, "order-1", Behaviour.approve())))).isEqualTo(AuthorizationResult.APPROVED);
        assertThat(decided(rules(rule(1, MatchField.ORDER_ID, "order-1", Behaviour.approve()),
                rule(2, MatchField.ORDER_ID, "order-1", Behaviour.decline(null)))))
                .isEqualTo(AuthorizationResult.declined(ReasonCode.RULE_DECLINED));
        // Two different Tickets of the request have rules: the most recent rule wins.
        assertThat(decided(rules(rule(5, MatchField.TICKET_ID, "T2", Behaviour.decline(ReasonCode.CARD_DECLINED)),
                rule(6, MatchField.TICKET_ID, "T1", Behaviour.decline(ReasonCode.INSUFFICIENT_FUNDS)))))
                .isEqualTo(AuthorizationResult.declined(ReasonCode.INSUFFICIENT_FUNDS));
        assertThat(decided(rules(rule(6, MatchField.TICKET_ID, "T1", Behaviour.decline(ReasonCode.INSUFFICIENT_FUNDS)),
                rule(7, MatchField.TICKET_ID, "T2", Behaviour.decline(ReasonCode.CARD_DECLINED)))))
                .isEqualTo(AuthorizationResult.declined(ReasonCode.CARD_DECLINED));
    }

    @Test
    void ruleWinsOverPercentageAndPercentageOverDefault() {
        MockConfiguration allDeclined = MockConfiguration.INITIAL.withDefaults(new Defaults(Outcome.APPROVED, 100));
        assertThat(decided(allDeclined)).isEqualTo(AuthorizationResult.declined(ReasonCode.PERCENTAGE_DECLINED));
        assertThat(decided(allDeclined.withRules(RuleSet.EMPTY.add(rule(1, MatchField.EVENT_ID, "event-1",
                Behaviour.approve()))))).isEqualTo(AuthorizationResult.APPROVED);

        MockConfiguration declinedDefault = MockConfiguration.INITIAL.withDefaults(new Defaults(Outcome.DECLINED, 0));
        assertThat(decided(declinedDefault)).isEqualTo(AuthorizationResult.declined(ReasonCode.RULE_DECLINED));
        // bucket 41: 42 % declines by percentage before looking at the default
        assertThat(decided(MockConfiguration.INITIAL.withDefaults(new Defaults(Outcome.DECLINED, 42))))
                .isEqualTo(AuthorizationResult.declined(ReasonCode.PERCENTAGE_DECLINED));
        assertThat(decided(MockConfiguration.INITIAL.withDefaults(new Defaults(Outcome.APPROVED, 41))))
                .isEqualTo(AuthorizationResult.APPROVED);
        assertThat(decided(MockConfiguration.INITIAL)).isEqualTo(AuthorizationResult.APPROVED);
    }

    @Test
    void failureAndLatencyBehavioursProducePlans() {
        assertThat(OutcomeSelector.select(rules(rule(1, MatchField.ORDER_ID, "order-1", Behaviour.definitiveError())),
                ID, PAYLOAD)).isEqualTo(new OutcomeSelector.DefinitiveError("rule-1"));
        assertThat(OutcomeSelector.select(rules(rule(2, MatchField.ORDER_ID, "order-1",
                Behaviour.transientThenOutcome(3, Outcome.DECLINED, null))), ID, PAYLOAD))
                .isEqualTo(new OutcomeSelector.TransientThenOutcome("rule-2", 3,
                        AuthorizationResult.declined(ReasonCode.RULE_DECLINED)));
        assertThat(OutcomeSelector.select(rules(rule(3, MatchField.ORDER_ID, "order-1",
                Behaviour.latency(500, Outcome.APPROVED, null))), ID, PAYLOAD))
                .isEqualTo(new OutcomeSelector.Delay(500, AuthorizationResult.APPROVED));
        // LATENCY without finalOutcome composes with the percentage and the default (PM-IV-013).
        MockConfiguration latencyAndPercentage = rules(rule(4, MatchField.ORDER_ID, "order-1",
                Behaviour.latency(500, null, null))).withDefaults(new Defaults(Outcome.APPROVED, 100));
        assertThat(OutcomeSelector.select(latencyAndPercentage, ID, PAYLOAD)).isEqualTo(
                new OutcomeSelector.Delay(500, AuthorizationResult.declined(ReasonCode.PERCENTAGE_DECLINED)));
    }
}
