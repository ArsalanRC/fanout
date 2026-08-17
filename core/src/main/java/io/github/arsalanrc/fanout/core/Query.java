package io.github.arsalanrc.fanout.core;

import java.time.LocalDate;
import java.util.Objects;

/**
 * What somebody is looking for.
 *
 * <p>Passengers is part of the query rather than applied afterwards, because
 * suppliers disagree about whether the price they quote is per passenger or for
 * the booking. A connector cannot normalise that without knowing the count, and
 * a search that multiplies at the end has already lost the information about
 * which suppliers needed multiplying.
 */
public record Query(String origin, String destination, LocalDate departure, int passengers) {

    public Query {
        origin = requireIata(origin, "origin");
        destination = requireIata(destination, "destination");
        Objects.requireNonNull(departure, "departure");

        if (origin.equals(destination)) {
            throw new IllegalArgumentException("Origin and destination are both " + origin);
        }
        if (passengers < 1) {
            throw new IllegalArgumentException("A search needs at least one passenger, got " + passengers);
        }
    }

    /**
     * Airport codes are upper case everywhere they are printed, and suppliers
     * are inconsistent about accepting lower case. Normalising here means the
     * connectors never have to remember, and it makes the itinerary key stable.
     */
    private static String requireIata(String code, String field) {
        Objects.requireNonNull(code, field);
        String upper = code.trim().toUpperCase(java.util.Locale.ROOT);

        if (!upper.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException(field + " must be a three-letter IATA code, got: " + code);
        }
        return upper;
    }
}
