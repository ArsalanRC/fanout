package io.github.arsalanrc.fanout.integration;

import io.github.arsalanrc.fanout.core.Deadline;
import io.github.arsalanrc.fanout.core.Fare;
import io.github.arsalanrc.fanout.core.Query;

import java.util.List;

/**
 * One supplier, as far as the rest of the system is concerned.
 *
 * <p>Everything specific to a supplier stops here: its URL, its authentication,
 * the shape of its JSON, whether it prices per passenger, whether it sells bags
 * separately. What comes back is always a list of {@link Fare}, and by then
 * every supplier looks the same.
 *
 * <p>That boundary is the reason this is a module rather than a package. A
 * connector that leaked its own shape upward would put a supplier's quirks in
 * the ranking code, where they would be found again two years later by somebody
 * wondering why one airline is always slightly cheap.
 */
public interface Connector {

    /** Stable, lower case, and the name a visitor sees on the results page. */
    String id();

    /**
     * Ask this supplier for fares, and give up when the deadline does.
     *
     * <p>May throw. The fan-out is responsible for turning a failure into a
     * {@link io.github.arsalanrc.fanout.core.SupplierOutcome}, because a
     * connector that swallowed its own errors would report an empty result and
     * an empty result is indistinguishable from a route nobody flies.
     */
    List<Fare> search(Query query, Deadline deadline) throws Exception;
}
