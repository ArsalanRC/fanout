package io.github.arsalanrc.fanout.core;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * One journey, every price found for it, and the basket they were compared on.
 *
 * <p>This is what deduplication produces: three suppliers selling the same
 * seats become one row carrying three offers rather than three rows. The offers
 * are all kept rather than reduced to the winner, because the spread is
 * information. A supplier consistently twenty per cent above the rest is worth
 * seeing, and averaging or discarding hides it.
 *
 * <p>The basket is stored alongside, because "cheapest" has no meaning without
 * it. The same three offers reorder completely between hand luggage only and a
 * checked bag, and a row that did not record which question it answered would
 * be quietly unfalsifiable.
 */
public record PricedItinerary(Itinerary itinerary, List<Fare> offers, Basket basket) {

    public PricedItinerary {
        Objects.requireNonNull(itinerary, "itinerary");
        Objects.requireNonNull(basket, "basket");

        if (offers == null || offers.isEmpty()) {
            throw new IllegalArgumentException("A priced itinerary needs at least one offer");
        }

        // Sorted on construction so `best()` cannot disagree with the list, and
        // so a caller rendering `offers` in order gets the same answer.
        offers = offers.stream()
                .sorted(Comparator.comparing(fare -> fare.totalFor(basket)))
                .toList();
    }

    public static PricedItinerary of(Itinerary itinerary, List<Fare> offers, Basket basket) {
        return new PricedItinerary(itinerary, offers, basket);
    }

    /** The cheapest offer for this basket, which is not the cheapest headline. */
    public Fare best() {
        return offers.getFirst();
    }

    public Money bestPrice() {
        return best().totalFor(basket);
    }

    public int sellers() {
        return offers.size();
    }

    /**
     * The gap between the cheapest and the dearest, for this basket.
     *
     * <p>Worth surfacing. A spread of two euros means the market agrees and the
     * choice does not matter; a spread of ninety means somebody is paying for
     * not shopping around, which is the entire reason a metasearch exists.
     */
    public Money spread() {
        return offers.getLast().totalFor(basket).minus(bestPrice());
    }
}
