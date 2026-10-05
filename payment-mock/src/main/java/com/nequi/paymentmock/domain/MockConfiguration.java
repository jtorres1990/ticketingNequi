package com.nequi.paymentmock.domain;

import java.util.Objects;

/** Immutable outcome-selection configuration: rules and defaults. Replaced atomically as a whole. */
public record MockConfiguration(RuleSet rules, Defaults defaults) {

    public static final MockConfiguration INITIAL = new MockConfiguration(RuleSet.EMPTY, Defaults.INITIAL);

    public MockConfiguration {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(defaults, "defaults");
    }

    public MockConfiguration withRules(RuleSet next) {
        return new MockConfiguration(next, defaults);
    }

    public MockConfiguration withDefaults(Defaults next) {
        return new MockConfiguration(rules, next);
    }
}
