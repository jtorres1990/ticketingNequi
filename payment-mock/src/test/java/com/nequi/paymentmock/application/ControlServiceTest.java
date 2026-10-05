package com.nequi.paymentmock.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.paymentmock.domain.Behaviour;
import com.nequi.paymentmock.domain.Defaults;
import com.nequi.paymentmock.domain.MatchField;
import com.nequi.paymentmock.domain.OutcomeRule;
import com.nequi.paymentmock.domain.Outcome;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** API-103 to API-107 and API-110 at service level: identifiers, order, idempotency, reset and concurrency. */
class ControlServiceTest {

    private final MockState state = new MockState();
    private final ControlService control = new ControlService(state);

    @Test
    void initialStateHasNoRulesAndApprovesByDefault() {
        assertThat(control.listRules()).isEmpty();
        assertThat(control.defaults()).isEqualTo(Defaults.INITIAL);
    }

    @Test
    void ruleIdentifiersAreMonotonicAndNeverReusedAfterReset() {
        OutcomeRule first = control.createRule(MatchField.ORDER_ID, "o", Behaviour.approve());
        OutcomeRule second = control.createRule(MatchField.ORDER_ID, "o", Behaviour.approve());
        control.reset();
        OutcomeRule third = control.createRule(MatchField.ORDER_ID, "o", Behaviour.approve());
        assertThat(List.of(first.ruleId(), second.ruleId(), third.ruleId()))
                .containsExactly("rule-1", "rule-2", "rule-3");
        assertThat(control.listRules()).extracting(OutcomeRule::ruleId).containsExactly("rule-3");
    }

    @Test
    void deletionIsIdempotentAndDeleteAllClearsEverything() {
        OutcomeRule rule = control.createRule(MatchField.EVENT_ID, "e", Behaviour.approve());
        control.createRule(MatchField.CUSTOMER_REF, "c", Behaviour.decline(null));
        control.deleteRule(rule.ruleId());
        control.deleteRule(rule.ruleId());
        control.deleteRule("rule-999");
        assertThat(control.listRules()).hasSize(1);
        control.deleteAllRules();
        control.deleteAllRules();
        assertThat(control.listRules()).isEmpty();
    }

    @Test
    void defaultsAreReplacedAsAWholeAndResetRestoresTheInitialState() {
        assertThat(control.setDefaults(new Defaults(Outcome.DECLINED, 40))).isEqualTo(new Defaults(Outcome.DECLINED, 40));
        control.createRule(MatchField.ORDER_ID, "o", Behaviour.approve());
        control.reset();
        assertThat(control.defaults()).isEqualTo(Defaults.INITIAL);
        assertThat(control.listRules()).isEmpty();
    }

    @Test
    void concurrentCreationsLoseNoRuleAndAssignUniqueIdentifiers() throws Exception {
        int threads = 8;
        int perThread = 250;
        CyclicBarrier start = new CyclicBarrier(threads);
        List<Future<List<String>>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                int thread = t;
                futures.add(executor.submit(() -> {
                    start.await();
                    List<String> ids = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        MatchField field = MatchField.values()[(thread + i) % MatchField.values().length];
                        ids.add(control.createRule(field, "v" + i, Behaviour.approve()).ruleId());
                        if (i % 50 == 0) {
                            control.setDefaults(new Defaults(Outcome.APPROVED, i % 100));
                        }
                    }
                    return ids;
                }));
            }
            Set<String> created = new HashSet<>();
            for (Future<List<String>> future : futures) {
                created.addAll(future.get());
            }
            assertThat(created).hasSize(threads * perThread);
        }
        assertThat(control.listRules()).hasSize(threads * perThread);
        assertThat(control.listRules()).extracting(OutcomeRule::ruleId).doesNotHaveDuplicates();
    }
}
