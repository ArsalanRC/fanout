package io.github.arsalanrc.fanout.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class JourneyTest {

    private static final Instant NOON = Instant.parse("2026-09-01T12:00:00Z");
    private static final int A_WEEK = 60 * 24 * 7;

    private static Leg leg(String carrier, String number, String from, String to, int startMin, int minutes) {
        return new Leg(carrier, number, from, to,
                NOON.plus(Duration.ofMinutes(startMin)),
                NOON.plus(Duration.ofMinutes(startMin + minutes)));
    }

    private static Itinerary out() {
        return Itinerary.of(leg("FE", "410", "DUS", "LIS", 0, 195));
    }

    private static Itinerary back() {
        return Itinerary.of(leg("FE", "411", "LIS", "DUS", A_WEEK, 200));
    }

    @Test
    @DisplayName("the turnaround is not a stop, which is the whole reason this type exists")
    void a_return_is_not_one_long_itinerary() {
        Journey trip = Journey.roundTrip(out(), back());

        // Folded into a single itinerary, these two direct flights would report
        // one stop and a journey time of a week. Kept as a pair, each direction
        // is still what the traveller actually flies.
        assertEquals(0, trip.outbound().stops());
        assertEquals(0, trip.inbound().stops());
        assertEquals(Duration.ofMinutes(195), trip.outbound().total());
        assertEquals(Duration.ofMinutes(200), trip.inbound().total());

        Itinerary folded = Itinerary.of(
                leg("FE", "410", "DUS", "LIS", 0, 195),
                leg("FE", "411", "LIS", "DUS", A_WEEK, 200));
        assertEquals(1, folded.stops());
        assertTrue(folded.total().toDays() >= 7);
    }

    @Test
    void a_one_way_keeps_the_key_it_always_had() {
        Itinerary only = out();

        assertEquals(only.key(), Journey.oneWay(only).key());
        assertFalse(Journey.oneWay(only).isReturn());
    }

    @Test
    @DisplayName("a through fare never dedupes against the one-way that shares its outbound")
    void a_return_is_a_different_product_from_its_outbound() {
        Journey trip = Journey.roundTrip(out(), back());

        assertNotEquals(Journey.oneWay(out()).key(), trip.key());
        assertTrue(trip.isReturn());
    }

    @Test
    void the_same_pair_from_two_suppliers_shares_a_key() {
        assertEquals(Journey.roundTrip(out(), back()).key(),
                Journey.roundTrip(out(), back()).key());
    }

    @Test
    void a_different_return_flight_is_a_different_product() {
        Itinerary later = Itinerary.of(leg("FE", "413", "LIS", "DUS", A_WEEK + 300, 200));

        assertNotEquals(Journey.roundTrip(out(), back()).key(),
                Journey.roundTrip(out(), later).key());
    }

    @Test
    void refuses_a_return_that_starts_somewhere_the_outbound_never_landed() {
        Itinerary wrongOrigin = Itinerary.of(leg("FE", "411", "OPO", "DUS", A_WEEK, 200));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Journey.roundTrip(out(), wrongOrigin));
        assertTrue(e.getMessage().contains("OPO"));
    }

    @Test
    void refuses_a_return_that_lands_somewhere_the_trip_never_started() {
        Itinerary wrongHome = Itinerary.of(leg("FE", "411", "LIS", "CGN", A_WEEK, 200));

        assertThrows(IllegalArgumentException.class, () -> Journey.roundTrip(out(), wrongHome));
    }

    @Test
    @DisplayName("a pair sorted the wrong way round would price and render as a time machine")
    void refuses_a_return_that_leaves_before_the_outbound_lands() {
        Itinerary tooEarly = Itinerary.of(leg("FE", "411", "LIS", "DUS", 60, 200));

        assertThrows(IllegalArgumentException.class, () -> Journey.roundTrip(out(), tooEarly));
    }

    @Test
    void a_fare_quoted_for_one_direction_is_not_a_return() {
        Fare oneWay = Fare.inclusive("openfare", out(), Money.of(8900, "EUR"), NOON.plusSeconds(600));

        assertFalse(oneWay.isReturn());
        assertEquals(out(), oneWay.itinerary());
    }

    @Test
    @DisplayName("a through fare prices as one product, and itinerary() still answers with the outbound")
    void a_through_fare_carries_both_directions() {
        Journey trip = Journey.roundTrip(out(), back());
        Fare through = new Fare("openfare", trip, Money.of(14900, "EUR"),
                java.util.List.of(Ancillary.at(Ancillary.Kind.CABIN_BAG, Money.of(1200, "EUR"))),
                NOON.plusSeconds(600));

        assertTrue(through.isReturn());
        assertEquals(out(), through.itinerary());
        assertEquals(back(), through.journey().inbound());
        assertEquals(Money.of(16100, "EUR"), through.totalFor(Basket.HAND_LUGGAGE_ONLY));
    }
}
