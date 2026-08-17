package io.github.arsalanrc.fanout.core;

import java.util.EnumSet;
import java.util.Set;

/**
 * What this traveller actually needs, so that two fares can be compared.
 *
 * <p>There is no such thing as "the price of a flight" once budget airlines are
 * in the results. There is the price of a flight <em>for somebody</em>: with
 * hand luggage only, or with a suitcase, or with a seat chosen in advance.
 * Those are different questions with different winners, and a metasearch that
 * refuses to ask which one is being asked is guessing.
 *
 * <p>{@link #HAND_LUGGAGE_ONLY} is the default because it is the honest
 * comparison for the largest group of travellers, and it is the one where
 * budget carriers genuinely do win. The point of this type is not to make them
 * look expensive. It is to stop the answer changing depending on which supplier
 * happened to bundle what.
 */
public record Basket(Set<Ancillary.Kind> needed) {

    /** Cabin bag only. The default, and the comparison most people mean. */
    public static final Basket HAND_LUGGAGE_ONLY = new Basket(EnumSet.of(Ancillary.Kind.CABIN_BAG));

    /** Cabin bag and a suitcase in the hold. Where the two carrier models diverge most. */
    public static final Basket WITH_CHECKED_BAG =
            new Basket(EnumSet.of(Ancillary.Kind.CABIN_BAG, Ancillary.Kind.CHECKED_BAG));

    /**
     * The seat alone, ignoring everything sold beside it.
     *
     * <p>This is what a naive metasearch compares, and it is here so the tests
     * can show it ranking the wrong carrier first rather than only describing
     * that it would.
     */
    public static final Basket SEAT_ONLY = new Basket(EnumSet.noneOf(Ancillary.Kind.class));

    public Basket {
        needed = needed.isEmpty()
                ? EnumSet.noneOf(Ancillary.Kind.class)
                : EnumSet.copyOf(needed);
    }

    public boolean wants(Ancillary.Kind kind) {
        return needed.contains(kind);
    }

    @Override
    public String toString() {
        return needed.isEmpty() ? "seat only" : needed.toString();
    }
}
