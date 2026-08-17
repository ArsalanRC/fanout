package io.github.arsalanrc.fanout.core;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * How one supplier did, whether or not it produced anything.
 *
 * <p>This type is the second argument the repository makes. A metasearch that
 * returns only fares cannot tell the difference between "nobody flies that
 * route" and "half our suppliers timed out", and those need opposite responses
 * from everyone downstream. Reporting the outcome beside the results costs one
 * record and answers the question honestly.
 */
public record SupplierOutcome(String supplier, Status status, Duration took, List<Fare> fares, String detail) {

    public enum Status {
        /** Answered inside the deadline. May still have found nothing. */
        ANSWERED,
        /** Did not answer before the budget ran out. Not an error, just late. */
        TIMED_OUT,
        /** Answered with something this system could not use. */
        FAILED,
        /** Not called at all: its breaker was open after recent failures. */
        SKIPPED
    }

    public SupplierOutcome {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(took, "took");
        fares = fares == null ? List.of() : List.copyOf(fares);

        if (status != Status.ANSWERED && !fares.isEmpty()) {
            throw new IllegalArgumentException(
                    supplier + " is " + status + " but carries " + fares.size()
                            + " fares. A supplier that did not answer has nothing to contribute.");
        }
    }

    public static SupplierOutcome answered(String supplier, Duration took, List<Fare> fares) {
        return new SupplierOutcome(supplier, Status.ANSWERED, took, fares, null);
    }

    public static SupplierOutcome timedOut(String supplier, Duration took) {
        return new SupplierOutcome(supplier, Status.TIMED_OUT, took, List.of(), null);
    }

    public static SupplierOutcome failed(String supplier, Duration took, String detail) {
        return new SupplierOutcome(supplier, Status.FAILED, took, List.of(), detail);
    }

    public static SupplierOutcome skipped(String supplier, String detail) {
        return new SupplierOutcome(supplier, Status.SKIPPED, Duration.ZERO, List.of(), detail);
    }

    public boolean contributed() {
        return status == Status.ANSWERED;
    }
}
