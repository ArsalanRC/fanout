package io.github.arsalanrc.fanout.core;

import java.time.Instant;
import java.util.Objects;

/**
 * A price, from one supplier, for one itinerary.
 *
 * <p>The price is always the total for the whole query, in one currency, after
 * taxes and surcharges. Getting a fare into that shape is the connector's job
 * and it is the hardest job in the system, because every supplier draws those
 * lines somewhere else.
 *
 * <p>{@code expiresAt} is not decoration. Fares are quotes, and a quote that
 * has lapsed is worse than no quote: it survives to the top of a sorted list,
 * gets clicked, and fails at the point where somebody was about to pay. A fare
 * past its expiry is dropped rather than shown.
 */
public record Fare(String supplier, Itinerary itinerary, Money total, Instant expiresAt) {

    public Fare {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(itinerary, "itinerary");
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(expiresAt, "expiresAt");

        if (total.minorUnits() < 0) {
            throw new IllegalArgumentException("A fare cannot cost less than nothing: " + total);
        }
    }

    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
