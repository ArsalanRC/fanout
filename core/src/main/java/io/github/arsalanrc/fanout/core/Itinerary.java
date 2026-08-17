package io.github.arsalanrc.fanout.core;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The flights somebody would actually take, in order.
 *
 * <p>The interesting method here is {@link #key()}, and it is what makes
 * deduplication possible. Three suppliers selling seat availability on the same
 * aircraft are selling one product at three prices, and a result list showing
 * that as three rows looks full while hiding the only thing the user wants to
 * know, which is the spread.
 */
public record Itinerary(List<Leg> legs) {

    public Itinerary {
        Objects.requireNonNull(legs, "legs");
        if (legs.isEmpty()) throw new IllegalArgumentException("An itinerary needs at least one leg");
        legs = List.copyOf(legs);
    }

    public static Itinerary of(Leg... legs) {
        return new Itinerary(List.of(legs));
    }

    /**
     * The identity of this journey, independent of who is selling it.
     *
     * <p>Built from the carrier, flight number and departure instant of every
     * leg. Deliberately not from price, supplier, cabin or fare basis: those
     * are what differ between the sellers of the same seat, which is exactly
     * what has to collapse.
     */
    public String key() {
        return legs.stream().map(Leg::key).collect(Collectors.joining("|"));
    }

    public String origin() {
        return legs.getFirst().origin();
    }

    public String destination() {
        return legs.getLast().destination();
    }

    /** Gate to gate, including time spent connecting. */
    public Duration total() {
        return Duration.between(legs.getFirst().departure(), legs.getLast().arrival());
    }

    public int stops() {
        return legs.size() - 1;
    }
}
