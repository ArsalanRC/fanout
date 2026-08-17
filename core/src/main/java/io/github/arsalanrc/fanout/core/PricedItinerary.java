package io.github.arsalanrc.fanout.core;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * One journey and every price found for it.
 *
 * <p>This is what deduplication produces: three suppliers selling the same
 * seats become one row carrying three offers, cheapest first. The row keeps all
 * three rather than only the winner, because the spread is information. A
 * supplier consistently twenty per cent above the rest is worth knowing about,
 * and averaging or discarding hides it.
 */
public record PricedItinerary(Itinerary itinerary, List<Fare> offers) {

    public PricedItinerary {
        Objects.requireNonNull(itinerary, "itinerary");
        if (offers == null || offers.isEmpty()) {
            throw new IllegalArgumentException("A priced itinerary needs at least one offer");
        }
        // Sorted on construction so `best()` cannot disagree with the list, and
        // so a caller that renders `offers` in order gets the same answer.
        offers = offers.stream().sorted(Comparator.comparing(Fare::total)).toList();
    }

    public Fare best() {
        return offers.getFirst();
    }

    public int sellers() {
        return offers.size();
    }
}
