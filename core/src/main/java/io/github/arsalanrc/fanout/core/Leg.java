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
 */
public record Leg(String carrier, String number, String origin, String destination,
                  Instant departure, Instant arrival) {

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
