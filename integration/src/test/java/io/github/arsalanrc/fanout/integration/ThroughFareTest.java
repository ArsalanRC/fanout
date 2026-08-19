package io.github.arsalanrc.fanout.integration;

import io.github.arsalanrc.fanout.core.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The recorded return fares, and the comparison the page exists to make.
 *
 * <p>These read the shipped fixtures rather than an inline payload, because the
 * claim being tested is about the market as recorded: that a published return
 * really is cheaper than the two singles it replaces, on the same flights.
 */
class ThroughFareTest {

    private static final Query OUT = new Query("CGN", "STN", LocalDate.of(2026, 9, 1), 1);
    private static final Query BACK = new Query("STN", "CGN", LocalDate.of(2026, 9, 8), 1);

    private static List<Fare> fares(SupplierParser parser, String file, Query query) throws Exception {
        return new FixtureConnector(parser, "/fixtures/" + file)
                .search(query, Deadline.in(Duration.ofSeconds(10)));
    }

    private static Fare byNumber(List<Fare> fares, String number) {
        return fares.stream()
                .filter(f -> f.itinerary().legs().getFirst().number().equals(number))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a published return beats the two singles it replaces, on the same flights")
    void the_through_fare_is_cheaper_than_two_one_ways() throws Exception {
        AmadeusParser openfare = new AmadeusParser("openfare");

        Fare through = byNumber(
                fares(openfare, "openfare-cgn-stn-2026-09-01-ret-2026-09-08.json", OUT), "412");
        Fare outbound = byNumber(fares(openfare, "openfare-cgn-stn-2026-09-01.json", OUT), "412");
        Fare inbound = byNumber(fares(openfare, "openfare-stn-cgn-2026-09-08.json", BACK), "412");

        // Same carrier, same two flight numbers, so this is one product against
        // the other rather than two different trips.
        assertTrue(through.isReturn());
        assertEquals("412", through.journey().inbound().legs().getFirst().number());

        Money singles = outbound.totalFor(Basket.SEAT_ONLY).plus(inbound.totalFor(Basket.SEAT_ONLY));
        Money pair = through.totalFor(Basket.SEAT_ONLY);

        assertEquals(Money.of(16130, "EUR"), singles);
        assertEquals(Money.of(14194, "EUR"), pair);

        // The saving has to be comfortably clear of day-to-day price movement,
        // which already runs to 18 per cent between a Friday and a Wednesday. A
        // thin margin here would vanish on some dates and the page would claim
        // a discount it could not then show.
        assertTrue(singles.minus(pair).minorUnits() > 1500,
                "the return should save well over fifteen euros, saved " + singles.minus(pair));
    }

    @Test
    @DisplayName("the agent encodes a return as a nested object, and it normalises to the same thing")
    void the_reseller_nested_inbound_becomes_a_journey() throws Exception {
        Fare fare = fares(new ResellerParser("voyago"),
                "voyago-cgn-stn-2026-09-01-ret-2026-09-08.json", OUT).getFirst();

        assertTrue(fare.isReturn());
        assertEquals("CGN", fare.itinerary().origin());
        assertEquals("STN", fare.itinerary().destination());
        assertEquals("STN", fare.journey().inbound().origin());
        assertEquals("CGN", fare.journey().inbound().destination());

        // The carrier is stated once, on the outbound. The way home inherits it
        // rather than defaulting to anything, which would invent an airline.
        assertEquals(fare.itinerary().legs().getFirst().carrier(),
                fare.journey().inbound().legs().getFirst().carrier());
    }

    @Test
    void the_one_way_fixtures_are_still_one_ways() throws Exception {
        assertFalse(fares(new AmadeusParser("openfare"), "openfare-cgn-stn-2026-09-01.json", OUT)
                .getFirst().isReturn());
        assertFalse(fares(new ResellerParser("voyago"), "voyago-cgn-stn-2026-09-01.json", OUT)
                .getFirst().isReturn());
    }

    @Test
    @DisplayName("the budget carriers sell two one-ways, because that is their actual model")
    void no_return_is_recorded_for_the_low_cost_carriers() {
        // Not an omission to be filled in later. Staying out of third-party
        // channels and pricing each direction alone is the strategy, and it is
        // the same constraint that made this repository worth building.
        assertNull(getClass().getResourceAsStream(
                "/fixtures/fineair-cgn-stn-2026-09-01-ret-2026-09-08.json"));
        assertNull(getClass().getResourceAsStream(
                "/fixtures/bizzair-cgn-stn-2026-09-01-ret-2026-09-08.json"));

        assertNotNull(getClass().getResourceAsStream(
                "/fixtures/openfare-cgn-stn-2026-09-01-ret-2026-09-08.json"));
    }
}
