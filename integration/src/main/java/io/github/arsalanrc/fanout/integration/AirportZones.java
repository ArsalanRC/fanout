package io.github.arsalanrc.fanout.integration;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

/**
 * Which timezone an airport is in, so a local departure time can become an
 * instant.
 *
 * <p>This exists because of a bug that is almost invisible. Airline APIs quote
 * departure and arrival in **local time at each airport, with no offset**.
 * Amadeus sends {@code "2026-09-01T07:00:00"} and means seven in the morning
 * wherever that airport happens to be.
 *
 * <p>So a flight leaving Düsseldorf at 07:00 and landing at Heathrow at 07:20
 * reads as a twenty minute flight. It is eighty minutes. Sort a result list by
 * duration and that flight is the fastest in Europe, every time, and nothing
 * about the number looks wrong.
 *
 * <p>Twice a year it gets worse, because the two airports change clocks on
 * different dates and the error moves by an hour for a fortnight.
 *
 * <p><b>An unknown airport is refused rather than assumed to be UTC.</b>
 * Defaulting is what turns this from an error into a wrong number, and a wrong
 * duration outranks a correct one silently.
 */
public final class AirportZones {

    /**
     * Deliberately small and explicit.
     *
     * <p>A real deployment reads this from a proper reference dataset. Shipping
     * a guessed table of nine thousand airports would be worse than shipping
     * twelve that are right, because the wrong ones would be indistinguishable.
     */
    private static final Map<String, ZoneId> ZONES = Map.ofEntries(
            Map.entry("DUS", ZoneId.of("Europe/Berlin")),
            Map.entry("BER", ZoneId.of("Europe/Berlin")),
            Map.entry("FRA", ZoneId.of("Europe/Berlin")),
            Map.entry("MUC", ZoneId.of("Europe/Berlin")),
            Map.entry("CGN", ZoneId.of("Europe/Berlin")),
            Map.entry("VIE", ZoneId.of("Europe/Vienna")),
            Map.entry("LHR", ZoneId.of("Europe/London")),
            Map.entry("STN", ZoneId.of("Europe/London")),
            Map.entry("LGW", ZoneId.of("Europe/London")),
            Map.entry("DUB", ZoneId.of("Europe/Dublin")),
            Map.entry("BCN", ZoneId.of("Europe/Madrid")),
            Map.entry("MAD", ZoneId.of("Europe/Madrid")),
            Map.entry("CDG", ZoneId.of("Europe/Paris")),
            Map.entry("FCO", ZoneId.of("Europe/Rome")),
            Map.entry("WAW", ZoneId.of("Europe/Warsaw")),
            Map.entry("KRK", ZoneId.of("Europe/Warsaw")),
            Map.entry("BUD", ZoneId.of("Europe/Budapest")),
            Map.entry("OTP", ZoneId.of("Europe/Bucharest")),
            Map.entry("AMS", ZoneId.of("Europe/Amsterdam")),
            Map.entry("LIS", ZoneId.of("Europe/Lisbon"))
    );

    private AirportZones() {
    }

    public static ZoneId of(String iata) {
        ZoneId zone = ZONES.get(iata);
        if (zone == null) throw new UnknownAirport(iata);
        return zone;
    }

    /**
     * A local time at an airport, as a real instant.
     *
     * @param local the supplier's value, with no offset, e.g. 2026-09-01T07:00
     * @param iata  the airport that time is local to
     */
    public static java.time.Instant instantAt(LocalDateTime local, String iata) {
        return ZonedDateTime.of(local, of(iata)).toInstant();
    }

    /** Raised rather than defaulting to UTC and producing a plausible wrong duration. */
    public static final class UnknownAirport extends RuntimeException {
        public UnknownAirport(String iata) {
            super("No timezone known for " + iata + ". Refusing to assume UTC: a local time read "
                    + "in the wrong zone gives a flight duration that is wrong by hours and "
                    + "looks entirely normal.");
        }
    }
}
