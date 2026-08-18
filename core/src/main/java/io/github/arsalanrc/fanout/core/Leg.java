package io.github.arsalanrc.fanout.core;

import java.time.Instant;
import java.util.Objects;

/**
 * One flight, from one airport to another.
 *
 * <p>Times are {@link Instant}, never local times with a separate zone field.
 * A flight leaving Düsseldorf at 07:00 and landing in London at 07:20 has not
 * taken twenty minutes, and every duration calculation on local times gets that
 * wrong twice a year in the other direction as well.
 *
 * <p>{@code aircraft} is here because somebody comparing two fares an hour
 * apart is often really comparing two aircraft, and because it is the one field
 * on this record that is not invented. The carriers in this project are
 * fictional. The aircraft are the types that really fly these routes.
 */
public record Leg(String carrier, String number, String origin, String destination,
                  Instant departure, Instant arrival, String aircraft) {

    /**
     * A leg whose aircraft the supplier did not state.
     *
     * <p>Common, and not an error: plenty of suppliers omit the type, and one
     * that does has said nothing rather than said "unknown". Left null so the
     * page can leave the field out instead of printing a placeholder.
     */
    public Leg(String carrier, String number, String origin, String destination,
               Instant departure, Instant arrival) {
        this(carrier, number, origin, destination, departure, arrival, null);
    }

    public Leg {
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(departure, "departure");
        Objects.requireNonNull(arrival, "arrival");

        if (arrival.isBefore(departure)) {
            throw new IllegalArgumentException(
                    "Leg " + carrier + number + " arrives before it leaves: " + departure + " to " + arrival);
        }
    }

    /** The stable identity of this flight, whoever is selling it. */
    public String key() {
        return carrier + number + "@" + departure.toEpochMilli();
    }
}
