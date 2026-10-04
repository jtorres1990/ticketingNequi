package com.nequi.ticketing.application.port.out;

import java.util.List;
import java.util.Objects;

/**
 * Typed result of an atomic conditional write (ADR-034): applied, cancelled by conditions with the
 * reason per item, or a transactional conflict that persisted after the adapter's bounded retries
 * (ADR-023, ADR-035).
 */
public sealed interface TransactionOutcome
        permits TransactionOutcome.Applied, TransactionOutcome.Cancelled, TransactionOutcome.Conflict {

    static TransactionOutcome applied() {
        return new Applied();
    }

    static TransactionOutcome cancelled(List<ItemFailure> failures) {
        return new Cancelled(failures);
    }

    static TransactionOutcome conflict() {
        return new Conflict();
    }

    record Applied() implements TransactionOutcome {
    }

    record Cancelled(List<ItemFailure> failures) implements TransactionOutcome {
        public Cancelled {
            failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
            if (failures.isEmpty()) {
                throw new IllegalArgumentException("a cancellation carries at least one failed item");
            }
        }

        public boolean failed(FailedItem item) {
            return failures.stream().anyMatch(failure -> failure.item() == item);
        }

        public List<String> ticketIds(FailedItem item) {
            return failures.stream()
                    .filter(failure -> failure.item() == item)
                    .map(ItemFailure::ticketId)
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    record Conflict() implements TransactionOutcome {
    }
}
