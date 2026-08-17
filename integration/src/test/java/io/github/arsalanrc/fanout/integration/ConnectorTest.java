package io.github.arsalanrc.fanout.integration;

import io.github.arsalanrc.fanout.core.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Both connectors read a real payload off disk through the same parser the live
 * one would use. What is asserted is the normalisation, because that is where a
 * metasearch produces a wrong answer that looks right.
 */
class ConnectorTest {

    private static final Query ONE_PASSENGER = new Query("DUS", "STN", LocalDate.of(2026, 9, 1), 1);
    private static final Query THREE_PASSENGERS = new Query("DUS", "STN", LocalDate.of(2026, 9, 1), 3);

    private static Connector altair() {
        return new FixtureConnector(new AmadeusParser("altair"), "/fixtures/altair-dus-stn.json");
    }

    private static Connector skyhop() {
        return new FixtureConnector(new LowCostParser("skyhop"), "/fixtures/skyhop-dus-stn.json");
    }

    private static List<Fare> fares(Connector connector, Query query) throws Exception {
        return connector.search(query, Deadline.in(Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("a local departure time becomes the right instant, not a twenty minute flight")
    void resolves_local_times_through_the_airport_timezone() throws Exception {
        Leg leg = fares(altair(), ONE_PASSENGER).getFirst().itinerary().legs().getFirst();

        // The payload says 07:00 at DUS and 07:20 at STN, both without an
        // offset. Read as written that is a twenty minute flight, and it would
        // sort to the top of any "fastest" list in Europe. Germany is an hour
        // ahead of the UK, so it is eighty minutes.
        assertEquals(Duration.ofMinutes(80), Duration.between(leg.departure(), leg.arrival()));
        assertEquals("2026-09-01T05:00:00Z", leg.departure().toString());
    }

    @Test
    void the_two_shapes_agree_on_the_same_physical_flight() throws Exception {
        Leg legacy = fares(altair(), ONE_PASSENGER).getFirst().itinerary().legs().getFirst();
        Leg budget = fares(skyhop(), ONE_PASSENGER).getFirst().itinerary().legs().getFirst();

        // One sends local times and the other sends offsets. Both mean 07:00 in
        // Düsseldorf, and after normalisation both say so.
        assertEquals(legacy.departure(), budget.departure());
        assertEquals(legacy.arrival(), budget.arrival());
    }

    @Test
    @DisplayName("the booking-priced supplier is not multiplied by the passenger count")
    void a_booking_price_stays_a_booking_price() throws Exception {
        Money one = fares(altair(), ONE_PASSENGER).getFirst().base();
        Money three = fares(altair(), THREE_PASSENGERS).getFirst().base();

        // Amadeus quotes the whole booking. Multiplying here would quadruple a
        // fare that was already right for a family of four.
        assertEquals(Money.of(8_900, "EUR"), one);
        assertEquals(one, three);
    }

    @Test
    @DisplayName("the per-passenger supplier is multiplied, and the payment fee is not")
    void a_per_passenger_price_scales_but_the_booking_fee_does_not() throws Exception {
        Fare one = fares(skyhop(), ONE_PASSENGER).getFirst();
        Fare three = fares(skyhop(), THREE_PASSENGERS).getFirst();

        assertEquals(Money.of(1_999, "EUR"), one.base());
        assertEquals(Money.of(5_997, "EUR"), three.base());

        // A bag is per passenger: 25.50 becomes 76.50.
        assertEquals(Money.of(7_650, "EUR"),
                three.find(Ancillary.Kind.CHECKED_BAG).orElseThrow().price());

        // The card fee is charged once on the booking. Scaling it with the
        // others is a small plausible overcharge that makes this carrier look
        // worse than it is, and nothing downstream could tell.
        assertEquals(Money.of(600, "EUR"),
                three.find(Ancillary.Kind.PAYMENT_FEE).orElseThrow().price());
    }

    @Test
    void a_free_extra_is_recorded_as_included_rather_than_sold_for_nothing() throws Exception {
        Ancillary cabin = fares(skyhop(), ONE_PASSENGER).getFirst()
                .find(Ancillary.Kind.CABIN_BAG).orElseThrow();

        assertTrue(cabin.included());
        assertTrue(cabin.cost().isZero());
    }

    @Test
    @DisplayName("no checked bag in the fare is recorded as absent, not as included")
    void a_zero_bag_allowance_is_not_an_included_bag() throws Exception {
        List<Fare> offers = fares(altair(), ONE_PASSENGER);

        // The first offer includes one checked bag. The second says quantity 0,
        // which is a real answer meaning "none", and it must not become an
        // included bag worth nothing.
        assertTrue(offers.getFirst().find(Ancillary.Kind.CHECKED_BAG).isPresent());
        assertTrue(offers.get(1).find(Ancillary.Kind.CHECKED_BAG).isEmpty());

        assertTrue(offers.getFirst().satisfies(Basket.WITH_CHECKED_BAG));
        assertFalse(offers.get(1).satisfies(Basket.WITH_CHECKED_BAG));
    }

    @Test
    @DisplayName("the two carriers compare correctly once the basket is applied")
    void the_comparison_that_the_headline_gets_wrong() throws Exception {
        Fare budget = fares(skyhop(), ONE_PASSENGER).getFirst();
        Fare legacy = fares(altair(), ONE_PASSENGER).getFirst();

        // Headline: 19.99 against 89.00.
        assertEquals(Money.of(1_999, "EUR"), budget.base());
        assertEquals(Money.of(8_900, "EUR"), legacy.base());

        // Hand luggage: 19.99 + 0 cabin + 6.00 card fee = 25.99. The budget
        // carrier wins properly.
        assertEquals(Money.of(2_599, "EUR"), budget.totalFor(Basket.HAND_LUGGAGE_ONLY));
        assertEquals(Money.of(8_900, "EUR"), legacy.totalFor(Basket.HAND_LUGGAGE_ONLY));

        // With a suitcase: 25.99 + 25.50 = 51.49. Still cheaper, but the gap is
        // 37 euros rather than the 69 the headline implied.
        assertEquals(Money.of(5_149, "EUR"), budget.totalFor(Basket.WITH_CHECKED_BAG));
    }

    @Test
    void the_evening_budget_flight_loses_once_the_bag_is_counted() throws Exception {
        // 14.99 base looks like the cheapest thing on the page, and its checked
        // bag is 55 euros: 14.99 + 55 + 6 = 75.99 against the morning flight at
        // 51.49. Cheapest base, dearest journey, same airline.
        Fare evening = fares(skyhop(), ONE_PASSENGER).get(1);
        Fare morning = fares(skyhop(), ONE_PASSENGER).getFirst();

        assertTrue(evening.base().compareTo(morning.base()) < 0);
        assertEquals(Money.of(7_599, "EUR"), evening.totalFor(Basket.WITH_CHECKED_BAG));
        assertTrue(evening.totalFor(Basket.WITH_CHECKED_BAG)
                .compareTo(morning.totalFor(Basket.WITH_CHECKED_BAG)) > 0);
    }

    @Test
    void an_unknown_airport_is_refused_rather_than_assumed_to_be_utc() {
        AirportZones.UnknownAirport thrown =
                assertThrows(AirportZones.UnknownAirport.class, () -> AirportZones.of("ZZZ"));

        assertTrue(thrown.getMessage().contains("Refusing to assume UTC"));
    }

    @Test
    void a_connector_gives_up_when_the_deadline_does() {
        Connector slow = new FixtureConnector(
                new LowCostParser("skyhop"), "/fixtures/skyhop-dus-stn.json", Duration.ofSeconds(5));

        /*
         * A fixture that answers slower than the budget allows must not quietly
         * overrun it. The fan-out is entitled to assume a connector respects
         * the deadline it was handed.
         *
         * This case measures real time, so treat it as a behavioural check
         * rather than the guard. It once passed on a laptop and failed in CI,
         * because the connector truncated the remaining budget to whole
         * milliseconds and woke a fraction before expiry. Put that truncation
         * back and this test only notices about two runs in ten.
         *
         * The deterministic guard is `DeadlineTest.rounds_a_sliver_up_rather
         * _than_down`, which pins the rounding with a clock driven by hand.
         * Fix that one first if this ever starts flickering again.
         */
        assertThrows(InterruptedException.class,
                () -> slow.search(ONE_PASSENGER, Deadline.in(Duration.ofMillis(40))));
    }
}
