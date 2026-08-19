package io.github.arsalanrc.fanout.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A price, from one supplier, for one itinerary.
 *
 * <p><b>There is no single total on this record, and that is the point.</b>
 * A fare has a base, which is what the supplier headlines, and a list of things
 * priced beside it. Whether a checked bag costs 25 euros or nothing is not a
 * detail: it is the difference between the right answer and the wrong one once
 * budget carriers are in the results.
 *
 * <p>So the total is a question rather than a field. Ask
 * {@link #totalFor(Basket)} what this costs for a traveller who needs a cabin
 * bag and a suitcase, and two fares built on completely different commercial
 * models become comparable numbers.
 *
 * <p>{@code expiresAt} is not decoration either. Fares are quotes, and a quote
 * that has lapsed is worse than no quote: it survives to the top of a sorted
 * list, gets clicked, and fails where somebody was about to pay.
 */
public record Fare(String supplier, Journey journey, Money base,
                   List<Ancillary> ancillaries, Instant expiresAt) {

    /**
     * A price for one direction, which is what most suppliers quote.
     *
     * <p>Here so that pricing a {@link Journey} did not have to be a change at
     * every call site that only ever sells one way. Same reason {@link Leg} has
     * a constructor without an aircraft.
     */
    public Fare(String supplier, Itinerary itinerary, Money base,
                List<Ancillary> ancillaries, Instant expiresAt) {
        this(supplier, Journey.oneWay(itinerary), base, ancillaries, expiresAt);
    }

    public Fare {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(journey, "journey");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(expiresAt, "expiresAt");
        ancillaries = ancillaries == null ? List.of() : List.copyOf(ancillaries);

        if (base.minorUnits() < 0) {
            throw new IllegalArgumentException("A fare cannot cost less than nothing: " + base);
        }

        // Every part of one fare is quoted in one currency by the time it gets
        // here. A connector that leaves two in a fare has not finished
        // normalising, and the failure would otherwise surface much later as a
        // mismatch thrown from inside a sort.
        for (Ancillary extra : ancillaries) {
            if (!extra.price().currency().equals(base.currency())) {
                throw new IllegalArgumentException(
                        supplier + " quotes " + extra.kind() + " in " + extra.price().currency()
                                + " and the fare in " + base.currency()
                                + ". The connector has to convert before it gets here.");
            }
        }
    }

    /** A fare with nothing sold beside it, which is how a fully inclusive carrier quotes. */
    public static Fare inclusive(String supplier, Itinerary itinerary, Money base, Instant expiresAt) {
        return new Fare(supplier, itinerary, base, List.of(), expiresAt);
    }

    /** The same, for a supplier selling both directions as one product. */
    public static Fare inclusive(String supplier, Journey journey, Money base, Instant expiresAt) {
        return new Fare(supplier, journey, base, List.of(), expiresAt);
    }

    /**
     * The outbound this fare covers.
     *
     * <p>Kept because almost everything reading a fare wants the direction the
     * traveller is searching, and because a one-way fare has nothing else. Ask
     * {@link #journey()} when both directions matter.
     */
    public Itinerary itinerary() {
        return journey.outbound();
    }

    /** Whether this is one price for both directions rather than for one. */
    public boolean isReturn() {
        return journey.isReturn();
    }

    /**
     * What this fare costs a traveller who needs exactly what is in the basket.
     *
     * <p>Anything the basket does not ask for is not charged, and anything the
     * carrier already includes adds nothing. What is left is the honest number
     * to sort on.
     *
     * @throws MissingAncillary when the basket asks for something this fare
     *         neither includes nor sells. Guessing a price would invent one, and
     *         treating it as free would rank an unusable fare first.
     */
    public Money totalFor(Basket basket) {
        Money total = base;

        for (Ancillary.Kind kind : Ancillary.Kind.values()) {
            if (!basket.wants(kind)) continue;

            Ancillary match = find(kind).orElseThrow(() -> new MissingAncillary(supplier, kind));
            total = total.plus(match.cost());
        }

        // A payment fee is charged whether or not anybody asked for it, so it
        // is added outside the basket. Leaving it out is how a fare that looked
        // cheapest stops being cheapest at the last screen.
        Optional<Ancillary> fee = find(Ancillary.Kind.PAYMENT_FEE);
        if (fee.isPresent() && !basket.wants(Ancillary.Kind.PAYMENT_FEE)) {
            total = total.plus(fee.get().cost());
        }

        return total;
    }

    public Optional<Ancillary> find(Ancillary.Kind kind) {
        return ancillaries.stream().filter(a -> a.kind() == kind).findFirst();
    }

    /** Whether this fare sells everything the basket asks for. */
    public boolean satisfies(Basket basket) {
        return basket.needed().stream().allMatch(kind -> find(kind).isPresent());
    }

    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /**
     * Raised rather than guessing.
     *
     * <p>A carrier that will not sell a checked bag on this fare has not made
     * it free. Treating the absence as zero puts a fare the traveller cannot
     * actually use at the top of the list.
     */
    public static final class MissingAncillary extends RuntimeException {
        public MissingAncillary(String supplier, Ancillary.Kind kind) {
            super(supplier + " does not quote " + kind + " on this fare, so it cannot be priced "
                    + "for a basket that needs one. Refusing to treat it as free.");
        }
    }
}
