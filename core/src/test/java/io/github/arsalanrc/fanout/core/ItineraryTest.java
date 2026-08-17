package io.github.arsalanrc.fanout.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ItineraryTest {

    private static final Instant NOON = Instant.parse("2026-09-01T12:00:00Z");

    private static Leg leg(String carrier, String number, String from, String to, int startMin, int minutes) {
        return new Leg(carrier, number, from, to,
                NOON.plus(Duration.ofMinutes(startMin)),
                NOON.plus(Duration.ofMinutes(startMin + minutes)));
    }

    private static Fare fare(String supplier, Itinerary it, long minor) {
        return Fare.inclusive(supplier, it, Money.of(minor, "EUR"), NOON.plusSeconds(600));
    }

    @Test
    @DisplayName("the same flight from two suppliers has the same key, which is what lets them merge")
    void identical_flights_share_a_key() {
        Itinerary a = Itinerary.of(leg("LH", "992", "DUS", "LHR", 0, 95));
        Itinerary b = Itinerary.of(leg("LH", "992", "DUS", "LHR", 0, 95));

        assertEquals(a.key(), b.key());
    }

    @Test
    void a_different_departure_time_is_a_different_journey() {
        Itinerary morning = Itinerary.of(leg("LH", "992", "DUS", "LHR", 0, 95));
        Itinerary later = Itinerary.of(leg("LH", "992", "DUS", "LHR", 60, 95));

        assertNotEquals(morning.key(), later.key());
    }

    @Test
    void a_connection_is_not_the_same_journey_as_a_direct_flight() {
        Itinerary direct = Itinerary.of(leg("LH", "992", "DUS", "LHR", 0, 95));
        Itinerary viaFrankfurt = Itinerary.of(
                leg("LH", "23", "DUS", "FRA", 0, 45),
                leg("LH", "914", "FRA", "LHR", 90, 95));

        assertNotEquals(direct.key(), viaFrankfurt.key());
        assertEquals(1, viaFrankfurt.stops());
        assertEquals(0, direct.stops());
    }

    @Test
    void measures_the_whole_journey_including_the_connection() {
        Itinerary viaFrankfurt = Itinerary.of(
                leg("LH", "23", "DUS", "FRA", 0, 45),
                leg("LH", "914", "FRA", "LHR", 90, 95));

        // Gate to gate is 185 minutes, not the 140 the two flights add up to.
        // A duration that ignores connection time makes a bad itinerary look
        // good, and it is the sort that sorts to the top of a "fastest" list.
        assertEquals(Duration.ofMinutes(185), viaFrankfurt.total());
        assertEquals("DUS", viaFrankfurt.origin());
        assertEquals("LHR", viaFrankfurt.destination());
    }

    @Test
    void refuses_a_leg_that_lands_before_it_leaves() {
        assertThrows(IllegalArgumentException.class,
                () -> new Leg("LH", "992", "DUS", "LHR", NOON, NOON.minusSeconds(60)));
    }

    @Test
    void a_priced_itinerary_sorts_its_offers_so_the_best_is_never_a_guess() {
        Itinerary it = Itinerary.of(leg("LH", "992", "DUS", "LHR", 0, 95));

        PricedItinerary priced = PricedItinerary.of(it,
                List.of(fare("gamma", it, 24_900), fare("alpha", it, 19_900), fare("beta", it, 21_500)),
                Basket.SEAT_ONLY);

        assertEquals("alpha", priced.best().supplier());
        assertEquals(3, priced.sellers());
        // The list is sorted too, so rendering it in order agrees with best().
        assertEquals(List.of("alpha", "beta", "gamma"), priced.offers().stream().map(Fare::supplier).toList());
    }

    @Test
    void a_fare_knows_when_it_has_lapsed() {
        Itinerary it = Itinerary.of(leg("LH", "992", "DUS", "LHR", 0, 95));
        Fare quote = Fare.inclusive("alpha", it, Money.of(19_900, "EUR"), NOON.plusSeconds(600));

        assertFalse(quote.expiredAt(NOON.plusSeconds(599)));
        // Expiry is inclusive: a fare expiring exactly now has expired. The
        // other reading loses a race with the supplier and fails at payment.
        assertTrue(quote.expiredAt(NOON.plusSeconds(600)));
    }

    @Test
    @DisplayName("a supplier that did not answer cannot smuggle fares through")
    void an_outcome_that_did_not_answer_carries_nothing() {
        Itinerary it = Itinerary.of(leg("LH", "992", "DUS", "LHR", 0, 95));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new SupplierOutcome("alpha", SupplierOutcome.Status.TIMED_OUT,
                        Duration.ofSeconds(3), List.of(fare("alpha", it, 19_900)), null));

        assertTrue(thrown.getMessage().contains("did not answer"));
    }

    @Test
    void a_result_says_plainly_when_it_is_missing_a_supplier() {
        Query query = new Query("DUS", "LHR", LocalDate.of(2026, 9, 1), 1);
        Itinerary it = Itinerary.of(leg("LH", "992", "DUS", "LHR", 0, 95));

        SearchResult partial = new SearchResult(query,
                List.of(PricedItinerary.of(it, List.of(fare("alpha", it, 19_900)), Basket.SEAT_ONLY)),
                List.of(SupplierOutcome.answered("alpha", Duration.ofMillis(400), List.of(fare("alpha", it, 19_900))),
                        SupplierOutcome.timedOut("beta", Duration.ofSeconds(20))));

        // The page has to be able to say "six of eight answered". Without this
        // it cannot tell an empty route from a broken supplier.
        assertFalse(partial.complete());
        assertEquals(List.of("beta"), partial.missing().stream().map(SupplierOutcome::supplier).toList());
        assertEquals("alpha", partial.cheapest().orElseThrow().best().supplier());
    }

    @Test
    void a_query_normalises_airport_codes_and_refuses_nonsense() {
        assertEquals("DUS", new Query(" dus ", "lhr", LocalDate.of(2026, 9, 1), 1).origin());

        assertThrows(IllegalArgumentException.class,
                () -> new Query("DUS", "DUS", LocalDate.of(2026, 9, 1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Query("DUS", "LONDON", LocalDate.of(2026, 9, 1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Query("DUS", "LHR", LocalDate.of(2026, 9, 1), 0));
    }
}
