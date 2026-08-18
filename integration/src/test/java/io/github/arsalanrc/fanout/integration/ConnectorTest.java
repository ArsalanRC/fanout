package io.github.arsalanrc.fanout.integration;

import io.github.arsalanrc.fanout.core.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Four suppliers, three payload shapes, four invented airlines.
 *
 * <p>Every connector reads a real payload off disk through the same parser the
 * live one would use. What is asserted is the normalisation, because that is
 * where a metasearch produces a wrong answer that looks entirely right.
 */
class ConnectorTest {

    private static final Query ONE = new Query("DUS", "STN", LocalDate.of(2026, 9, 1), 1);
    private static final Query THREE = new Query("DUS", "STN", LocalDate.of(2026, 9, 1), 3);

    private static Connector openfare() {
        return new FixtureConnector(new AmadeusParser("openfare"), "/fixtures/openfare-dus-stn.json");
    }

    private static Connector fineair() {
        return new FixtureConnector(new LowCostParser("fineair"), "/fixtures/fineair-dus-stn.json");
    }

    private static Connector bizzair() {
        return new FixtureConnector(new LowCostParser("bizzair"), "/fixtures/bizzair-dus-stn.json");
    }

    private static Connector voyago() {
        return new FixtureConnector(new ResellerParser("voyago"), "/fixtures/voyago-dus-stn.json");
    }

    private static List<Fare> fares(Connector connector, Query query) throws Exception {
        return connector.search(query, Deadline.in(Duration.ofSeconds(10)));
    }

    // ------------------------------------------------------------------ times

    @Test
    @DisplayName("a local departure time becomes the right instant, not a twenty minute flight")
    void resolves_local_times_through_the_airport_timezone() throws Exception {
        Leg leg = fares(openfare(), ONE).getFirst().itinerary().legs().getFirst();

        // The payload says 07:00 at DUS and 07:20 at STN, both without an
        // offset. Read as written that is a twenty minute flight, and it would
        // sort to the top of any fastest-first list in Europe. Germany is an
        // hour ahead of the UK, so it is eighty minutes.
        assertEquals(Duration.ofMinutes(80), Duration.between(leg.departure(), leg.arrival()));
        assertEquals("2026-09-01T05:00:00Z", leg.departure().toString());
    }

    @Test
    @DisplayName("three encodings of a departure time, one instant")
    void the_shapes_agree_on_the_same_physical_flight() throws Exception {
        // fineair sends an offset, voyago sends epoch milliseconds, and openfare
        // sends local time with no offset at all. All three are real
        // conventions, and the two that share a flight agree once normalised.
        Leg direct = fares(fineair(), ONE).getFirst().itinerary().legs().getFirst();
        Leg resold = fares(voyago(), ONE).getFirst().itinerary().legs().getFirst();

        assertEquals("FE", direct.carrier());
        assertEquals("1108", direct.number());
        assertEquals(direct.departure(), resold.departure());
        assertEquals(direct.arrival(), resold.arrival());
        assertEquals("2026-09-01T04:35:00Z", direct.departure().toString());
    }

    // ------------------------------------------------------------- passengers

    @Test
    @DisplayName("the booking-priced supplier is not multiplied by the passenger count")
    void a_booking_price_stays_a_booking_price() throws Exception {
        // The GDS shape quotes the whole booking. Multiplying here would
        // quadruple a fare that was already right for a family of four.
        assertEquals(Money.of(8_900, "EUR"), fares(openfare(), ONE).getFirst().base());
        assertEquals(Money.of(8_900, "EUR"), fares(openfare(), THREE).getFirst().base());
    }

    @Test
    @DisplayName("the per-passenger supplier is multiplied, and the payment fee is not")
    void a_per_passenger_price_scales_but_the_booking_fee_does_not() throws Exception {
        Fare one = fares(fineair(), ONE).getFirst();
        Fare three = fares(fineair(), THREE).getFirst();

        assertEquals(Money.of(1_299, "EUR"), one.base());
        assertEquals(Money.of(3_897, "EUR"), three.base());

        // A bag is per passenger: 46.50 becomes 139.50.
        assertEquals(Money.of(13_950, "EUR"),
                three.find(Ancillary.Kind.CHECKED_BAG).orElseThrow().price());

        // The card fee is charged once on the booking. Scaling it with the rest
        // is a small plausible overcharge that makes this carrier look worse
        // than it is, and nothing downstream could tell.
        assertEquals(Money.of(650, "EUR"),
                three.find(Ancillary.Kind.PAYMENT_FEE).orElseThrow().price());
    }

    @Test
    void the_reseller_prices_per_passenger_but_in_minor_units_already() throws Exception {
        // Third shape, third convention: amounts arrive as integers, so there is
        // no decimal to parse and no chance of a double. A parser assuming
        // decimals everywhere would read 2450 as 2450 euros.
        assertEquals(Money.of(2_450, "EUR"), fares(voyago(), ONE).getFirst().base());
        assertEquals(Money.of(7_350, "EUR"), fares(voyago(), THREE).getFirst().base());
    }

    // ------------------------------------------------------------------- bags

    @Test
    void a_free_extra_is_recorded_as_included_rather_than_sold_for_nothing() throws Exception {
        Ancillary cabin = fares(fineair(), ONE).getFirst()
                .find(Ancillary.Kind.CABIN_BAG).orElseThrow();

        assertTrue(cabin.included());
        assertTrue(cabin.cost().isZero());
    }

    @Test
    void bizzair_charges_no_payment_fee_and_that_is_recorded_rather_than_dropped() throws Exception {
        Ancillary fee = fares(bizzair(), ONE).getFirst()
                .find(Ancillary.Kind.PAYMENT_FEE).orElseThrow();

        // Zero is a real answer and it is not the same as the field being
        // absent. Absent means the supplier did not say.
        assertTrue(fee.included());
    }

    @Test
    @DisplayName("no checked bag in the fare is recorded as absent, not as included")
    void a_zero_bag_allowance_is_not_an_included_bag() throws Exception {
        List<Fare> offers = fares(openfare(), ONE);

        Fare halcyon = offers.stream()
                .filter(f -> f.itinerary().legs().getFirst().carrier().equals("HY"))
                .findFirst().orElseThrow();
        Fare light = offers.stream()
                .filter(f -> f.itinerary().legs().getFirst().number().equals("486"))
                .findFirst().orElseThrow();

        // Halcyon includes two checked bags. Altair's evening light fare
        // includes none, and `quantity: 0` must not become a bag worth nothing.
        assertTrue(halcyon.satisfies(Basket.WITH_CHECKED_BAG));
        assertFalse(light.satisfies(Basket.WITH_CHECKED_BAG));
        assertTrue(light.satisfies(Basket.HAND_LUGGAGE_ONLY));
    }

    // --------------------------------------------------------- the whole point

    @Test
    @DisplayName("the cheapest headline is not the cheapest journey")
    void the_comparison_that_the_headline_gets_wrong() throws Exception {
        Fare fine = fares(fineair(), ONE).getFirst();
        Fare bizz = fares(bizzair(), ONE).getFirst();
        Fare altair = fares(openfare(), ONE).getFirst();

        // Headlines: Fineair 12.99, Bizzair 19.99, Altair 89.00. Fineair looks
        // seven euros cheaper than Bizzair and seventy-six cheaper than Altair.
        assertEquals(Money.of(1_299, "EUR"), fine.base());
        assertEquals(Money.of(1_999, "EUR"), bizz.base());
        assertEquals(Money.of(8_900, "EUR"), altair.base());

        // Hand luggage: Fineair 12.99 plus a 6.50 card fee is 19.49, Bizzair
        // 19.99 with no fee at all. Fineair still wins, by fifty cents rather
        // than by seven euros. The headline overstated the gap fourteen times.
        assertEquals(Money.of(1_949, "EUR"), fine.totalFor(Basket.HAND_LUGGAGE_ONLY));
        assertEquals(Money.of(1_999, "EUR"), bizz.totalFor(Basket.HAND_LUGGAGE_ONLY));
        assertTrue(fine.totalFor(Basket.HAND_LUGGAGE_ONLY)
                .compareTo(bizz.totalFor(Basket.HAND_LUGGAGE_ONLY)) < 0);

        // With a suitcase the order flips outright: Fineair 65.99, Bizzair
        // 43.99. The airline with the cheapest headline is now twenty-two euros
        // dearer, and no headline comparison would ever say so.
        assertEquals(Money.of(6_599, "EUR"), fine.totalFor(Basket.WITH_CHECKED_BAG));
        assertEquals(Money.of(4_399, "EUR"), bizz.totalFor(Basket.WITH_CHECKED_BAG));
        assertTrue(bizz.totalFor(Basket.WITH_CHECKED_BAG)
                .compareTo(fine.totalFor(Basket.WITH_CHECKED_BAG)) < 0);
    }

    @Test
    @DisplayName("and the winner is a different airline depending on the bag")
    void the_cheapest_carrier_changes_with_the_basket() throws Exception {
        /*
         * The guard for the claim the whole pricing model rests on. Three
         * answers to what looks like one question, and a metasearch that never
         * asks which one is being put to it has to pick one and be wrong twice.
         *
         * This is asserted on the fixtures rather than on hand-built fares,
         * because the shipped market is what the page shows. A version of this
         * repository once claimed the flip in its notes while the fixtures
         * quietly had one carrier winning both baskets.
         */
        Fare fine = fares(fineair(), ONE).getFirst();
        Fare bizz = fares(bizzair(), ONE).getFirst();

        assertTrue(fine.base().compareTo(bizz.base()) < 0, "Fineair should lead on the headline");
        assertTrue(fine.totalFor(Basket.HAND_LUGGAGE_ONLY)
                .compareTo(bizz.totalFor(Basket.HAND_LUGGAGE_ONLY)) < 0,
                "Fineair should still lead with hand luggage only");
        assertTrue(bizz.totalFor(Basket.WITH_CHECKED_BAG)
                .compareTo(fine.totalFor(Basket.WITH_CHECKED_BAG)) < 0,
                "Bizzair should lead once a suitcase is in the basket");
    }

    @Test
    void the_cheaper_fineair_flight_loses_to_its_own_dearer_one() throws Exception {
        List<Fare> offers = fares(fineair(), ONE);
        Fare morning = offers.getFirst();
        Fare afternoon = offers.get(1);

        // 12.99 against 22.49 on the headline, so the morning looks cheaper.
        assertTrue(morning.base().compareTo(afternoon.base()) < 0);

        // With a bag: 65.99 against 60.99. Same airline, same route, same day,
        // and the cheaper-looking flight costs five euros more.
        assertEquals(Money.of(6_599, "EUR"), morning.totalFor(Basket.WITH_CHECKED_BAG));
        assertEquals(Money.of(6_099, "EUR"), afternoon.totalFor(Basket.WITH_CHECKED_BAG));
    }

    @Test
    @DisplayName("the reseller sells the same seat dearer, which is what dedup is for")
    void the_same_flight_from_two_sellers_is_priced_differently() throws Exception {
        Fare direct = fares(fineair(), ONE).getFirst();
        Fare resold = fares(voyago(), ONE).getFirst();

        // Same aircraft, same departure, so the same key. One row, two prices.
        assertEquals(direct.itinerary().key(), resold.itinerary().key());

        // 65.99 direct against 76.50 through the agent. Two rows would make the
        // page look full and hide the only useful fact on it.
        assertEquals(Money.of(6_599, "EUR"), direct.totalFor(Basket.WITH_CHECKED_BAG));
        assertEquals(Money.of(7_650, "EUR"), resold.totalFor(Basket.WITH_CHECKED_BAG));
    }

    // ---------------------------------------------------------------- refusals

    @Test
    void an_unknown_airport_is_refused_rather_than_assumed_to_be_utc() {
        AirportZones.UnknownAirport thrown =
                assertThrows(AirportZones.UnknownAirport.class, () -> AirportZones.of("ZZZ"));

        assertTrue(thrown.getMessage().contains("Refusing to assume UTC"));
    }

    @Test
    void a_connector_gives_up_when_the_deadline_does() {
        Connector slow = new FixtureConnector(
                new LowCostParser("fineair"), "/fixtures/fineair-dus-stn.json", Duration.ofSeconds(5));

        /*
         * A fixture answering slower than the budget allows must not quietly
         * overrun it. The fan-out is entitled to assume a connector respects the
         * deadline it was handed.
         *
         * This case measures real time, so treat it as a behavioural check
         * rather than the guard. It once passed on a laptop and failed in CI,
         * because the connector truncated the remaining budget to whole
         * milliseconds and woke a fraction before expiry. The deterministic
         * guard is `DeadlineTest.rounds_a_sliver_up_rather_than_down`.
         */
        assertThrows(InterruptedException.class,
                () -> slow.search(ONE, Deadline.in(Duration.ofMillis(40))));
    }

    @Test
    void the_market_is_four_suppliers_across_three_shapes() {
        assertEquals(List.of("openfare", "fineair", "bizzair", "voyago"),
                Market.suppliers().stream().map(Connector::id).toList());
        assertEquals("Fineair", Market.carrier("FE"));
        assertEquals("Bizzair", Market.carrier("BZ"));
        assertEquals("Halcyon", Market.carrier("HY"));
    }
}
