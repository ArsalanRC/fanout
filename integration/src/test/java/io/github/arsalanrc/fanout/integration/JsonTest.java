package io.github.arsalanrc.fanout.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A wrong parser does not throw. It returns something plausible, and the fare
 * built from it looks like every other fare, so these cases are mostly about
 * refusing input rather than accepting it.
 */
class JsonTest {

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        void reads_a_nested_document() {
            Json doc = Json.parse("""
                    {"data": [{"price": {"total": "212.40", "currency": "EUR"}}]}
                    """);

            assertEquals("212.40", doc.path("data").array().getFirst().path("price", "total").text());
            assertEquals("EUR", doc.get("data").array().getFirst().path("price", "currency").text());
        }

        @Test
        void a_missing_field_is_missing_rather_than_null() {
            Json doc = Json.parse("{\"a\": 1}");

            // Chaining through absent fields has to be safe, because supplier
            // payloads are full of optional structure. Returning null here
            // would put a NullPointerException in every connector instead.
            assertTrue(doc.get("nope").isMissing());
            assertTrue(doc.path("nope", "deeper", "still").isMissing());
            assertEquals(List.of(), doc.get("nope").arrayOrEmpty());
        }

        @Test
        void an_explicit_null_counts_as_missing() {
            assertTrue(Json.parse("{\"a\": null}").get("a").isMissing());
        }

        @Test
        void reads_escapes_including_unicode() {
            Json doc = Json.parse("{\"name\": \"D\\u00fcsseldorf\", \"note\": \"a\\\"b\\\\c\\nd\"}");

            assertEquals("Düsseldorf", doc.get("name").text());
            assertEquals("a\"b\\c\nd", doc.get("note").text());
        }

        @Test
        void reads_empty_containers() {
            assertEquals(List.of(), Json.parse("[]").array());
            assertTrue(Json.parse("{}").get("anything").isMissing());
        }

        @Test
        void reads_a_quoted_number_as_readily_as_a_bare_one() {
            // Suppliers disagree about whether prices are strings. Both mean
            // the same thing and a connector should not have to care.
            assertEquals("212.40", Json.parse("{\"t\": \"212.40\"}").get("t").text());
            assertEquals("212.40", Json.parse("{\"t\": 212.40}").get("t").text());
        }
    }

    @Nested
    @DisplayName("refusing")
    class Refusing {

        @Test
        void refuses_trailing_content_after_a_valid_value() {
            // Two concatenated documents, or a truncated read that stopped on
            // something valid. Both leave a usable first object, which is
            // exactly why they otherwise go unnoticed.
            assertThrows(Json.Malformed.class, () -> Json.parse("{\"a\":1} {\"b\":2}"));
        }

        @Test
        void refuses_an_unterminated_string() {
            assertThrows(Json.Malformed.class, () -> Json.parse("{\"a\": \"open"));
        }

        @Test
        void refuses_a_truncated_document() {
            assertThrows(Json.Malformed.class, () -> Json.parse("{\"a\": [1, 2"));
        }

        @Test
        void refuses_a_missing_comma() {
            assertThrows(Json.Malformed.class, () -> Json.parse("{\"a\": 1 \"b\": 2}"));
        }

        @Test
        void refuses_a_number_that_is_not_one() {
            assertThrows(Json.Malformed.class, () -> Json.parse("{\"a\": 12.34.56}"));
        }

        @Test
        void refuses_to_read_an_object_as_an_array() {
            assertThrows(Json.WrongType.class, () -> Json.parse("{\"a\": 1}").array());
        }

        @Test
        void refuses_to_read_an_array_as_text() {
            assertThrows(Json.WrongType.class, () -> Json.parse("[1]").text());
        }
    }

    @Nested
    @DisplayName("amounts, which never go through a double")
    class Amounts {

        @Test
        void reads_a_two_decimal_price_as_minor_units() {
            assertEquals(21_240, Json.parse("{\"t\": \"212.40\"}").get("t").minorUnits(2));
            assertEquals(21_240, Json.parse("{\"t\": 212.40}").get("t").minorUnits(2));
        }

        @Test
        void pads_a_short_fraction_rather_than_misreading_it() {
            // "212.4" is 212 euros 40 cents, not 212 euros 4 cents. Reading the
            // fraction as written is a hundredfold error on every such price.
            assertEquals(21_240, Json.parse("{\"t\": \"212.4\"}").get("t").minorUnits(2));
        }

        @Test
        void reads_a_whole_number_with_no_point_at_all() {
            assertEquals(21_200, Json.parse("{\"t\": \"212\"}").get("t").minorUnits(2));
            assertEquals(12_345, Json.parse("{\"t\": \"12345\"}").get("t").minorUnits(0));
        }

        @Test
        @DisplayName("keeps the precision a double would have lost")
        void keeps_precision_a_double_would_lose() {
            // 8.20 is not representable in binary floating point. Parsing it as
            // a double and multiplying by 100 gives 819.9999999999999, which
            // truncates to 819: one cent short, silently, on every fare.
            assertEquals(820, Json.parse("{\"t\": \"8.20\"}").get("t").minorUnits(2));
            assertEquals(819, (long) (Double.parseDouble("8.20") * 100),
                    "if this ever passes as 820 the double route stopped being wrong, "
                            + "and this test can go");
        }

        @Test
        void refuses_more_decimal_places_than_the_currency_has() {
            // A supplier sending three decimals to a two-decimal currency has
            // said something this code does not understand. Rounding it away is
            // how a price ends up a cent out for a month before anyone notices.
            Json.Malformed thrown = assertThrows(Json.Malformed.class,
                    () -> Json.parse("{\"t\": \"212.404\"}").get("t").minorUnits(2));

            assertTrue(thrown.getMessage().contains("Refusing to round"));
        }

        @Test
        void reads_a_negative_amount_without_losing_the_sign() {
            assertEquals(-21_240, Json.parse("{\"t\": \"-212.40\"}").get("t").minorUnits(2));
        }

        @Test
        void refuses_something_that_is_not_an_amount() {
            assertThrows(Json.Malformed.class,
                    () -> Json.parse("{\"t\": \"free\"}").get("t").minorUnits(2));
        }
    }
}
