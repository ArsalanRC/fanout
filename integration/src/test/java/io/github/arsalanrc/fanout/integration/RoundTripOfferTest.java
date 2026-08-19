package io.github.arsalanrc.fanout.integration;

import io.github.arsalanrc.fanout.core.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A round trip in the Amadeus shape, which is two itineraries under one price.
 *
 * <p>Built inline rather than read from a fixture because no recorded payload
 * has ever carried two, which is exactly why the defect these tests pin survived
 * unnoticed: the loop that produced it was never given a list longer than one.
 */
class RoundTripOfferTest {

    private static final Query CGN_STN = new Query("CGN", "STN", LocalDate.of(2026, 9, 1), 1);
    private static final Instant RECEIVED = Instant.parse("2026-09-01T04:00:00Z");

    private static String offer(String itineraries) {
        return """
                {"meta":{"count":1},"data":[{
                  "type":"flight-offer","id":"1","source":"GDS",
                  "itineraries":[%s],
                  "price":{"currency":"EUR","total":"149.00","grandTotal":"149.00","base":"104.30"},
                  "travelerPricings":[{
                    "travelerId":"1","fareOption":"STANDARD","travelerType":"ADULT",
                    "price":{"currency":"EUR","total":"149.00","base":"104.30"},
                    "fareDetailsBySegment":[{"segmentId":"1","cabin":"ECONOMY","class":"M",
                      "includedCheckedBags":{"quantity":1}}]}]
                }]}""".formatted(itineraries);
    }

    private static final String OUTBOUND = """
            {"duration":"PT1H20M","segments":[{
              "departure":{"iataCode":"CGN","at":"2026-09-01T07:00:00"},
              "arrival":{"iataCode":"STN","at":"2026-09-01T07:20:00"},
              "carrierCode":"AL","number":"812","id":"1","numberOfStops":0,
              "aircraft":{"code":"32N"}}]}""";

    private static final String INBOUND = """
            {"duration":"PT1H20M","segments":[{
              "departure":{"iataCode":"STN","at":"2026-09-08T18:00:00"},
              "arrival":{"iataCode":"CGN","at":"2026-09-08T20:20:00"},
              "carrierCode":"AL","number":"813","id":"2","numberOfStops":0,
              "aircraft":{"code":"32N"}}]}""";

    private static List<Fare> parse(String body) {
        return new AmadeusParser("openfare").parse(body, CGN_STN, RECEIVED);
    }

    @Test
    @DisplayName("a return offer is one fare at one price, not two fares that each cost the whole trip")
    void a_return_offer_is_a_single_product() {
        List<Fare> fares = parse(offer(OUTBOUND + "," + INBOUND));

        // The defect this pins: looping the itineraries emitted one fare per
        // direction and hung grandTotal on both. Two rows, 149.00 each, 298.00
        // of apparent inventory where the supplier quoted one ticket at 149.00.
        // Nothing throws and no row is missing, so it reads as a working search.
        assertEquals(1, fares.size());

        Fare fare = fares.getFirst();
        assertTrue(fare.isReturn());
        assertEquals(Money.of(14900, "EUR"), fare.totalFor(Basket.SEAT_ONLY));
    }

    @Test
    void both_directions_survive_onto_the_journey() {
        Fare fare = parse(offer(OUTBOUND + "," + INBOUND)).getFirst();

        assertEquals("CGN", fare.itinerary().origin());
        assertEquals("STN", fare.itinerary().destination());
        assertEquals("STN", fare.journey().inbound().origin());
        assertEquals("CGN", fare.journey().inbound().destination());
        assertEquals("813", fare.journey().inbound().legs().getFirst().number());
    }

    @Test
    @DisplayName("the outbound is still read through the airport timezone, offsets absent as ever")
    void the_return_leg_is_normalised_the_same_way() {
        Fare fare = parse(offer(OUTBOUND + "," + INBOUND)).getFirst();

        // 18:00 at Stansted is 17:00Z in September, and 20:20 at Cologne is
        // 18:20Z. Read as written it is a two hour twenty flight rather than
        // eighty minutes, the same trap as the outbound and just as invisible.
        assertEquals("2026-09-08T17:00:00Z", fare.journey().inbound().departure().toString());
        assertEquals("2026-09-08T18:20:00Z", fare.journey().inbound().arrival().toString());
    }

    @Test
    void a_one_way_offer_is_unchanged() {
        List<Fare> fares = parse(offer(OUTBOUND));

        assertEquals(1, fares.size());
        assertFalse(fares.getFirst().isReturn());
        assertEquals(Money.of(14900, "EUR"), fares.getFirst().totalFor(Basket.SEAT_ONLY));
    }

    @Test
    @DisplayName("a multi-city offer is refused rather than priced as the first two directions")
    void three_itineraries_are_not_a_return() {
        String third = """
                {"duration":"PT2H","segments":[{
                  "departure":{"iataCode":"CGN","at":"2026-09-15T09:00:00"},
                  "arrival":{"iataCode":"STN","at":"2026-09-15T09:20:00"},
                  "carrierCode":"AL","number":"814","id":"3","numberOfStops":0,
                  "aircraft":{"code":"32N"}}]}""";

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parse(offer(OUTBOUND + "," + INBOUND + "," + third)));
        assertTrue(e.getMessage().contains("multi-city"));
    }

    @Test
    void a_return_that_leaves_before_the_outbound_lands_is_refused() {
        String backwards = """
                {"duration":"PT1H20M","segments":[{
                  "departure":{"iataCode":"STN","at":"2026-08-20T18:00:00"},
                  "arrival":{"iataCode":"CGN","at":"2026-08-20T20:20:00"},
                  "carrierCode":"AL","number":"813","id":"2","numberOfStops":0,
                  "aircraft":{"code":"32N"}}]}""";

        assertThrows(IllegalArgumentException.class, () -> parse(offer(OUTBOUND + "," + backwards)));
    }
}
