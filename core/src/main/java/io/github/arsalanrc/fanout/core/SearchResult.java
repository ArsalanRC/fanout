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
public record SearchResult(Query query, List<PricedItinerary> itineraries, List<SupplierOutcome> outcomes) {

    public SearchResult {
        Objects.requireNonNull(query, "query");
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
