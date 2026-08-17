package io.github.arsalanrc.fanout.integration;

import io.github.arsalanrc.fanout.core.Deadline;
import io.github.arsalanrc.fanout.core.Fare;
import io.github.arsalanrc.fanout.core.Query;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A supplier answered from a recorded payload instead of the network.
 *
 * <p>This is what the published demo runs on, and the reason it is trustworthy
 * is that it changes only where the bytes come from. The parser is the same
 * object the live connector uses, so normalisation, currency handling,
 * per-passenger arithmetic and the bag rules are all exercised exactly as they
 * would be against the real supplier. A demo built from hand-made Java objects
 * would skip precisely the code most likely to be wrong.
 *
 * <p>An optional latency makes the fan-out visible: real suppliers answer at
 * different speeds and one of them is always slow, which is the whole reason
 * the deadline handling exists.
 *
 * <p><b>A recording carries the moment it was made.</b> Not every supplier
 * states when its quote lapses, and a parser handed no better answer dates the
 * expiry from when the response arrived. Reading a recorded response with
 * today's clock therefore produces a fare that is fresh for fifteen minutes
 * starting now, which is wrong in a way nothing catches: against the recorded
 * market's own timeline that fare is either years early or years late, and it
 * is silently dropped from the results while the supplier still reports that it
 * answered.
 */
public final class FixtureConnector implements Connector {

    private final SupplierParser parser;
    private final String resource;
    private final Duration latency;
    private final Instant recordedAt;

    public FixtureConnector(SupplierParser parser, String resource) {
        this(parser, resource, Duration.ZERO);
    }

    public FixtureConnector(SupplierParser parser, String resource, Duration latency) {
        this(parser, resource, latency, Instant.now());
    }

    /**
     * @param recordedAt the moment this response is taken to have arrived, which
     *                   is what any supplier that does not state its own expiry
     *                   is dated from
     */
    public FixtureConnector(SupplierParser parser, String resource, Duration latency, Instant recordedAt) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.resource = Objects.requireNonNull(resource, "resource");
        this.latency = Objects.requireNonNull(latency, "latency");
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
    }

    @Override
    public String id() {
        return parser.supplier();
    }

    @Override
    public List<Fare> search(Query query, Deadline deadline) throws Exception {
        if (!latency.isZero()) {
            /*
             * Sleep for the latency or the remaining budget, whichever is
             * shorter, then check. Sleeping the full latency first would let a
             * slow fixture overrun a deadline it was supposed to respect, and
             * the fan-out would look broken when the connector was.
             *
             * The budget comes from `remainingMillis`, which rounds up, and not
             * from `remaining().toMillis()`, which truncates. That distinction
             * is the bug this line used to have: truncating 39.7 milliseconds
             * to 39 left the deadline a hair short of expired, so the connector
             * returned normally when it should have given up. It passed on one
             * machine and failed in CI, which is the worst shape a test takes.
             */
            long budget = Math.min(latency.toMillis(), deadline.remainingMillis());
            Thread.sleep(budget);

            if (deadline.expired()) throw new InterruptedException(id() + " ran past the deadline");
        }

        return parser.parse(read(), query, recordedAt);
    }

    private String read() throws IOException {
        try (InputStream in = FixtureConnector.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("No fixture at " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
