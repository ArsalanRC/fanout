package io.github.arsalanrc.fanout.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything one search produced, including what went wrong.
 *
 * <p>{@link #complete()} is the field that keeps the page honest. A result
 * built from six of eight suppliers is a real result and worth showing, but a
 * page that presents it as the whole market is lying by omission, and the
 * cheapest fare on it may not be the cheapest fare available.
 */
public record SearchResult(Query query, List<PricedItinerary> itineraries,
                           List<SupplierOutcome> outcomes, Dropped dropped) {

    /**
     * Offers a supplier really sent, which were then not shown.
     *
     * <p>Counted rather than discarded quietly. A supplier can answer, be
     * recorded as having answered, and still contribute nothing to the page.
     * Without a number for this there is nothing anywhere that says so, and
     * that is a genuinely hard state to debug: every status is green and a row
     * is missing.
     *
     * <p>It is also the only place the freshness rule becomes visible.
     * Dropping a lapsed fare is the correct behaviour and it is invisible by
     * construction, so it gets counted instead.
     */
    public record Dropped(int lapsed, int unpriceable) {

        public static final Dropped NONE = new Dropped(0, 0);

        public int total() {
            return lapsed + unpriceable;
        }
    }

    /** A result with nothing dropped, which is the ordinary case. */
    public SearchResult(Query query, List<PricedItinerary> itineraries, List<SupplierOutcome> outcomes) {
        this(query, itineraries, outcomes, Dropped.NONE);
    }

    public SearchResult {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(dropped, "dropped");
        itineraries = List.copyOf(itineraries);
        outcomes = List.copyOf(outcomes);
    }

    /** Whether every supplier answered. False means the list may be missing something cheaper. */
    public boolean complete() {
        return outcomes.stream().allMatch(SupplierOutcome::contributed);
    }

    public List<SupplierOutcome> missing() {
        return outcomes.stream().filter(o -> !o.contributed()).toList();
    }

    public Optional<PricedItinerary> cheapest() {
        return itineraries.stream().findFirst();
    }
}
