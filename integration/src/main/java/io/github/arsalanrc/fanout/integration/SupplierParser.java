package io.github.arsalanrc.fanout.integration;

import io.github.arsalanrc.fanout.core.Fare;
import io.github.arsalanrc.fanout.core.Query;

import java.time.Instant;
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
     * @param body       the supplier's response, exactly as it arrived
     * @param query      what was asked, since some suppliers omit anything the
     *                   request already said, and passenger count decides
     *                   whether a quoted price is per person
     * @param receivedAt when this response was produced, which is the only
     *                   thing some suppliers give a parser to date an expiry
     *                   from. It is a parameter rather than a call to
     *                   {@link Instant#now()} inside the parser, and that is not
     *                   a style preference: a parser reaching for the wall clock
     *                   makes a recorded response permanently stale, because it
     *                   is read years after it was written. The fare then parses
     *                   correctly, arrives lapsed, and is dropped from the
     *                   results without anything failing. One supplier quietly
     *                   contributes nothing and the search still says it is
     *                   complete.
     */
    List<Fare> parse(String body, Query query, Instant receivedAt);
}
