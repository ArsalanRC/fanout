package io.github.arsalanrc.fanout.core;

import java.util.Objects;

/**
 * Something priced separately from the seat.
 *
 * <p>This type exists because of budget airlines, and it is the reason this
 * whole repository is more than a parallel-HTTP exercise.
 *
 * <p>A legacy carrier quotes a fare that already includes a cabin bag, a
 * checked bag and a seat. A low-cost carrier quotes the seat and sells
 * everything else. Ryanair at 19.99 and Lufthansa at 89.00 are not two prices
 * for the same thing, and a metasearch that sorts on the headline number tells
 * the traveller the wrong airline is cheaper. That is not a rounding error, it
 * is the entire answer being wrong, and it is wrong in a way that looks
 * completely normal on the page.
 *
 * <p>So an ancillary is either {@code included} in the fare or has a price, and
 * a fare is only ever compared against a {@link Basket} that says what this
 * traveller actually needs.
 */
public record Ancillary(Kind kind, Money price, boolean included) {

    /**
     * The things that move the answer.
     *
     * <p>Deliberately a small closed set rather than free text. A metasearch
     * comparing "1PC" against "checked bag" against "Aufgabegepäck" is
     * comparing strings, and the whole point here is to stop comparing things
     * that are not the same.
     */
    public enum Kind {
        CABIN_BAG,
        CHECKED_BAG,
        SEAT_SELECTION,
        /** Charged by some carriers on some cards. Real money, and easy to forget. */
        PAYMENT_FEE
    }

    public Ancillary {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(price, "price");

        if (included && !price.isZero()) {
            throw new IllegalArgumentException(
                    kind + " is included but priced at " + price
                            + ". Something included costs nothing by definition, and a fare that says "
                            + "both has been normalised wrongly.");
        }
        if (price.minorUnits() < 0) {
            throw new IllegalArgumentException(kind + " cannot cost less than nothing: " + price);
        }
    }

    /** Included in the fare, at no extra charge. */
    public static Ancillary included(Kind kind, java.util.Currency currency) {
        return new Ancillary(kind, Money.zero(currency), true);
    }

    /** Sold separately, at this price. */
    public static Ancillary at(Kind kind, Money price) {
        return new Ancillary(kind, price, false);
    }

    /** What this adds to a fare, which is nothing when it is already included. */
    public Money cost() {
        return included ? Money.zero(price.currency()) : price;
    }
}
