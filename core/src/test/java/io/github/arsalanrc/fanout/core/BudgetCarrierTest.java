package io.github.arsalanrc.fanout.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;

import static io.github.arsalanrc.fanout.core.Ancillary.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The case this repository exists for.
 *
 * <p>A budget carrier and a legacy carrier quote two different products. Sort
 * on the headline number and the answer is wrong, on the most common search
 * anybody runs, and it looks entirely normal on the page.
 */
class BudgetCarrierTest {

    private static final Instant NOON = Instant.parse("2026-09-01T12:00:00Z");
    private static final Currency EUR = Currency.getInstance("EUR");

    private static final Itinerary DUS_STN = Itinerary.of(
            new Leg("FR", "8542", "DUS", "STN", NOON, NOON.plus(Duration.ofMinutes(95))));

    /** Seat only, everything else sold beside it. The low-cost model. */
    private static Fare budget(String supplier, long baseCents, long cabinCents,
                               long checkedCents, long feeCents) {
        return new Fare(supplier, DUS_STN, Money.of(baseCents, "EUR"), List.of(
                Ancillary.at(CABIN_BAG, Money.of(cabinCents, "EUR")),
                Ancillary.at(CHECKED_BAG, Money.of(checkedCents, "EUR")),
                Ancillary.at(PAYMENT_FEE, Money.of(feeCents, "EUR"))
        ), NOON.plusSeconds(900));
    }

    /** Bags in the fare. The legacy model. */
    private static Fare legacy(String supplier, long baseCents) {
        return new Fare(supplier, DUS_STN, Money.of(baseCents, "EUR"), List.of(
                Ancillary.included(CABIN_BAG, EUR),
                Ancillary.included(CHECKED_BAG, EUR)
        ), NOON.plusSeconds(900));
    }

    @Test
    @DisplayName("the headline number puts the budget carrier first, and it is wrong")
    void the_naive_comparison_ranks_the_wrong_carrier_first() {
        // 19.99 base, then 12 cabin, 25 checked, 6 card fee.
        Fare cheapLooking = budget("skyhop", 1_999, 1_200, 2_500, 600);
        // 89.00, bags in the fare.
        Fare inclusive = legacy("altair", 8_900);

        // What a metasearch sorting on the quoted price would do.
        PricedItinerary naive = PricedItinerary.of(
                DUS_STN, List.of(cheapLooking, inclusive), Basket.SEAT_ONLY);
        assertEquals("skyhop", naive.best().supplier());

        // What a traveller with a suitcase actually pays:
        //   skyhop 19.99 + 12.00 + 25.00 + 6.00 = 62.99
        //   altair 89.00
        // so on this basket the budget carrier really is cheaper, and the
        // margin is 26 euros rather than the 69 the headline implied.
        PricedItinerary honest = PricedItinerary.of(
                DUS_STN, List.of(cheapLooking, inclusive), Basket.WITH_CHECKED_BAG);

        assertEquals(Money.of(6_299, "EUR"), cheapLooking.totalFor(Basket.WITH_CHECKED_BAG));
        assertEquals(Money.of(8_900, "EUR"), inclusive.totalFor(Basket.WITH_CHECKED_BAG));
        assertEquals("skyhop", honest.best().supplier());
    }

    @Test
    @DisplayName("with enough bags the order flips, which the headline never shows")
    void the_order_flips_once_the_extras_are_counted() {
        // A dearer bag policy: 19.99 base, 15 cabin, 55 checked, 8 fee = 97.99.
        Fare cheapLooking = budget("skyhop", 1_999, 1_500, 5_500, 800);
        Fare inclusive = legacy("altair", 8_900);

        // Seat only: the budget carrier wins by a mile.
        assertEquals("skyhop", PricedItinerary.of(
                DUS_STN, List.of(cheapLooking, inclusive), Basket.SEAT_ONLY).best().supplier());

        // With a suitcase: the legacy carrier is cheaper, and a metasearch
        // sorting on 19.99 against 89.00 would never say so.
        PricedItinerary honest = PricedItinerary.of(
                DUS_STN, List.of(cheapLooking, inclusive), Basket.WITH_CHECKED_BAG);

        assertEquals("altair", honest.best().supplier());
        assertEquals(Money.of(9_799, "EUR"), cheapLooking.totalFor(Basket.WITH_CHECKED_BAG));
    }

    @Test
    void the_payment_fee_is_charged_whether_or_not_anybody_asked_for_it() {
        Fare withFee = budget("skyhop", 1_999, 1_200, 2_500, 600);

        // Seat only still pays the card fee: 19.99 + 6.00. A fare that drops it
        // looks cheapest right up to the payment screen.
        assertEquals(Money.of(2_599, "EUR"), withFee.totalFor(Basket.SEAT_ONLY));
    }

    @Test
    void an_included_bag_adds_nothing() {
        Fare inclusive = legacy("altair", 8_900);

        assertEquals(Money.of(8_900, "EUR"), inclusive.totalFor(Basket.WITH_CHECKED_BAG));
        assertEquals(Money.of(8_900, "EUR"), inclusive.totalFor(Basket.HAND_LUGGAGE_ONLY));
    }

    @Test
    @DisplayName("a fare that cannot sell what the basket needs is refused, not priced as free")
    void refuses_to_price_an_ancillary_the_fare_does_not_sell() {
        Fare handLuggageOnly = new Fare("skyhop", DUS_STN, Money.of(1_999, "EUR"),
                List.of(Ancillary.at(CABIN_BAG, Money.of(1_200, "EUR"))), NOON.plusSeconds(900));

        assertFalse(handLuggageOnly.satisfies(Basket.WITH_CHECKED_BAG));

        // Treating the absence as zero would put a fare the traveller cannot
        // use at the top of the list, at a price that does not exist.
        Fare.MissingAncillary thrown = assertThrows(Fare.MissingAncillary.class,
                () -> handLuggageOnly.totalFor(Basket.WITH_CHECKED_BAG));
        assertTrue(thrown.getMessage().contains("Refusing to treat it as free"));

        // It is perfectly usable for the basket it does cover.
        assertTrue(handLuggageOnly.satisfies(Basket.HAND_LUGGAGE_ONLY));
        assertEquals(Money.of(3_199, "EUR"), handLuggageOnly.totalFor(Basket.HAND_LUGGAGE_ONLY));
    }

    @Test
    void refuses_an_ancillary_that_is_included_and_priced_at_the_same_time() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new Ancillary(CHECKED_BAG, Money.of(2_500, "EUR"), true));

        assertTrue(thrown.getMessage().contains("normalised wrongly"));
    }

    @Test
    void refuses_a_fare_whose_extras_are_in_another_currency() {
        // A connector that leaves two currencies in one fare has not finished
        // its job, and the failure would otherwise surface inside a sort.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new Fare("skyhop", DUS_STN, Money.of(1_999, "EUR"),
                        List.of(Ancillary.at(CHECKED_BAG, Money.of(2_500, "GBP"))),
                        NOON.plusSeconds(900)));

        assertTrue(thrown.getMessage().contains("convert before it gets here"));
    }

    @Test
    void the_spread_says_how_much_shopping_around_is_worth() {
        PricedItinerary row = PricedItinerary.of(DUS_STN,
                List.of(legacy("altair", 8_900), legacy("borealis", 12_400), legacy("cirrus", 9_500)),
                Basket.WITH_CHECKED_BAG);

        assertEquals("altair", row.best().supplier());
        assertEquals(3, row.sellers());
        // 124.00 against 89.00. Thirty-five euros for using a different site.
        assertEquals(Money.of(3_500, "EUR"), row.spread());
    }
}
