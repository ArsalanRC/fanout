package io.github.arsalanrc.fanout.integration;

import io.github.arsalanrc.fanout.core.Fare;
import io.github.arsalanrc.fanout.core.Query;

import java.util.List;

/**
 * Turns one supplier's payload into fares.
 *
 * <p>Separate from {@link Connector} on purpose, and this is the split that
 * makes the demo honest. A connector decides where the bytes come from, a
 * parser decides what they mean. So the same parser runs whether the response
 * arrived over the network with an API key or came off disk as a recorded
 * fixture, and the published demo cannot quietly become a different code path
 * from the real thing.
 *
 * <p>The alternative, a demo built on hand-made objects, tests nothing: it
 * skips exactly the code where supplier differences are absorbed.
 */
public interface SupplierParser {

    /** The supplier this parser speaks for. */
    String supplier();

    /**
     * @param body  the supplier's response, exactly as it arrived
     * @param query what was asked, since some suppliers omit anything the
     *              request already said, and passenger count decides whether a
     *              quoted price is per person
     */
    List<Fare> parse(String body, Query query);
}
