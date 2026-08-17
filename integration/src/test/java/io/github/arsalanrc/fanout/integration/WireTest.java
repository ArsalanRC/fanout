package io.github.arsalanrc.fanout.integration;

import io.github.arsalanrc.fanout.core.Ancillary;
import io.github.arsalanrc.fanout.core.Basket;
import io.github.arsalanrc.fanout.core.Fare;
import io.github.arsalanrc.fanout.core.Itinerary;
import io.github.arsalanrc.fanout.core.Leg;
import io.github.arsalanrc.fanout.core.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The wire between the two services.
 *
 * <p>Everything here is about one question: does a fare that leaves the
 * integration service arrive at the search service as the same fare? A format
 * that loses a cent, a currency or an invariant does it quietly, and the search
 * still returns a full page of plausible prices.
 */
class WireTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Instant EXPIRES = Instant.parse("2026-09-01T06:15:00Z");

    private static Fare fineair() {
        Leg leg = new Leg("FE", "1108", "DUS", "STN",
                Instant.parse("2026-09-01T04:35:00Z"), Instant.parse("2026-09-01T05:55:00Z"));

        return new Fare("fineair", new Itinerary(List.of(leg)), Money.of(1499, "EUR"),
                List.of(Ancillary.included(Ancillary.Kind.CABIN_BAG, EUR),
                        Ancillary.at(Ancillary.Kind.CHECKED_BAG, Money.of(4650, "EUR")),
                        Ancillary.at(Ancillary.Kind.PAYMENT_FEE, Money.of(650, "EUR"))),
                EXPIRES);
    }

    @Nested
    @DisplayName("A fare survives the crossing")
    class RoundTrip {

        @Test
        void arrives_as_the_same_fare_it_left_as() {
            Fare sent = fineair();
            Fare back = Wire.fares(Wire.fares("fineair", List.of(sent))).getFirst();

            assertEquals(sent, back, """
                    A fare changed shape crossing the wire. Records compare by value, so this \
                    covers the price, the currency, every ancillary and the expiry at once.""");
        }

        @Test
        @DisplayName("and it is still worth the same to a traveller")
        void prices_the_same_basket_to_the_same_number() {
            Fare back = Wire.fares(Wire.fares("fineair", List.of(fineair()))).getFirst();

            // 14.99 seat, cabin bag included, and the payment fee charged
            // whether or not anybody asked for it.
            assertEquals(Money.of(2149, "EUR"), back.totalFor(Basket.HAND_LUGGAGE_ONLY));
            assertEquals(Money.of(6799, "EUR"), back.totalFor(Basket.WITH_CHECKED_BAG));
        }

        @Test
        void an_empty_answer_is_a_real_answer_rather_than_an_error() {
            // A supplier with nothing on this route is not a failure, and the
            // difference matters: one is a quiet route, the other is an outage.
            assertEquals(List.of(), Wire.fares(Wire.fares("bizzair", List.of())));
        }
    }

    @Nested
    @DisplayName("The format refuses what it cannot carry honestly")
    class Refusing {

        @Test
        void money_never_appears_as_a_decimal() {
            String document = Wire.fares("fineair", List.of(fineair()));

            assertTrue(document.contains("\"minor\":1499"),
                    "Amounts travel as minor units: " + document);
            assertFalse(document.contains("14.99"), """
                    A decimal amount reached the wire. Read back as a double, 14.99 * 100 is \
                    1498.9999..., so every guard in Money would be defending a number that \
                    already lost a cent.""");
        }

        @Test
        void a_decimal_amount_is_refused_on_arrival() {
            String tampered = Wire.fares("fineair", List.of(fineair()))
                    .replace("\"minor\":1499", "\"minor\":14.99");

            assertThrows(Json.Malformed.class, () -> Wire.fares(tampered), """
                    A sender that started writing decimals has changed the format. Being told \
                    at the first message beats losing a cent a fare for a month.""");
        }

        @Test
        void an_impossible_leg_is_refused_on_arrival_too() {
            // Reading goes back through the real constructors, so the invariants
            // hold on the receiving side and not only on the sending one.
            String tampered = Wire.fares("fineair", List.of(fineair()))
                    .replace("\"arrival\":\"2026-09-01T05:55:00Z\"",
                            "\"arrival\":\"2026-09-01T03:55:00Z\"");

            assertThrows(IllegalArgumentException.class, () -> Wire.fares(tampered));
        }

        @Test
        void a_fare_mixing_two_currencies_is_refused_on_arrival() {
            String tampered = Wire.fares("fineair", List.of(fineair()))
                    .replace("\"minor\":4650,\"currency\":\"EUR\"",
                            "\"minor\":4650,\"currency\":\"GBP\"");

            assertThrows(IllegalArgumentException.class, () -> Wire.fares(tampered), """
                    Two currencies inside one fare means the connector did not finish \
                    normalising, and the wire is not the place to start tolerating it.""");
        }
    }

    @Nested
    @DisplayName("The writer")
    class Writing {

        @Test
        void refuses_to_hand_back_an_unbalanced_document() {
            JsonWriter out = new JsonWriter().object().array("fares");

            assertThrows(IllegalStateException.class, out::done, """
                    Unbalanced JSON is still a String. It travels all the way to the far side \
                    before anything notices, and then the parser gets the blame.""");
        }

        @Test
        void escapes_a_control_character_that_has_no_shorthand() {
            /*
             * Deliberately not a newline, tab or quote. Each of those has its
             * own branch, so a test using them never exercises the general
             * "below 0x20" rule and passes while that rule is broken. This is
             * the mistake the telemetry escaping test made first time round.
             */
            String raw = "bell" + (char) 7 + "here";
            String written = new JsonWriter().object().field("error", raw).end().done();

            assertTrue(written.contains("\\u0007"), "Raw control character in: " + written);
            assertEquals(raw, Json.parse(written).get("error").text());
        }

        @Test
        void writes_a_missing_detail_as_null_rather_than_the_word() {
            String written = new JsonWriter().object().field("detail", null).end().done();

            assertEquals("{\"detail\":null}", written);
            assertTrue(Json.parse(written).get("detail").isMissing());
        }
    }
}
