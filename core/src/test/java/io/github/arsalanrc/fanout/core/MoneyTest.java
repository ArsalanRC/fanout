package io.github.arsalanrc.fanout.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {

    @Test
    void adds_without_the_error_a_double_would_carry() {
        // The classic: 0.1 + 0.2 is 0.30000000000000004 in binary floating
        // point. In minor units it is 10 + 20 = 30, exactly, every time.
        Money base = Money.of(10, "EUR");
        Money tax = Money.of(20, "EUR");

        assertEquals(Money.of(30, "EUR"), base.plus(tax));
    }

    @Test
    void survives_a_fare_built_from_many_parts() {
        // A real fare is base plus taxes plus surcharges, and the error a
        // double accumulates grows with the number of additions.
        Money total = Money.of(0, "EUR");
        for (int i = 0; i < 1000; i++) {
            total = total.plus(Money.of(1, "EUR"));
        }

        assertEquals(Money.of(1000, "EUR"), total);
    }

    @Test
    @DisplayName("refuses to compare two currencies, which is the bug that picks the wrong fare")
    void refuses_to_compare_across_currencies() {
        Money pounds = Money.of(21_000, "GBP");
        Money euros = Money.of(24_000, "EUR");

        // 210 GBP is more expensive than 240 EUR at any plausible rate, so
        // comparing the raw numbers picks the wrong one and sorts the results
        // list into an order nothing downstream can question.
        Money.CurrencyMismatch thrown =
                assertThrows(Money.CurrencyMismatch.class, () -> pounds.compareTo(euros));

        assertTrue(thrown.getMessage().contains("Convert to one currency first"));
    }

    @Test
    void refuses_to_add_across_currencies() {
        assertThrows(Money.CurrencyMismatch.class,
                () -> Money.of(100, "EUR").plus(Money.of(100, "USD")));
    }

    @Test
    void sorting_a_mixed_currency_list_fails_loudly_rather_than_quietly() {
        List<Money> mixed = List.of(Money.of(21_000, "GBP"), Money.of(24_000, "EUR"));

        // The important half: it throws. A comparator returning a plausible
        // number here is how a wrong "cheapest" reaches the top of a page.
        assertThrows(Money.CurrencyMismatch.class, () -> mixed.stream().sorted().toList());
    }

    @Test
    void multiplies_for_per_passenger_pricing() {
        // Some suppliers quote per passenger and some quote the booking total.
        // The connector has to know which; this is the arithmetic it uses.
        assertEquals(Money.of(29_997, "EUR"), Money.of(9_999, "EUR").times(3));
    }

    @Test
    void refuses_to_multiply_past_what_a_long_holds() {
        assertThrows(ArithmeticException.class, () -> Money.of(Long.MAX_VALUE / 2, "EUR").times(3));
    }

    @Test
    void prints_with_the_fraction_digits_the_currency_actually_uses() {
        assertEquals("123.45 EUR", Money.of(12_345, "EUR").toString());
        // Yen has no minor unit. Hardcoding two decimal places, which almost
        // every money type does, prints 12345 JPY as "123.45 JPY".
        assertEquals("12345 JPY", Money.of(12_345, "JPY").toString());
    }

    @Test
    void prints_a_negative_amount_without_losing_the_sign_off_the_fraction() {
        assertEquals("-12.30 EUR", Money.of(-1_230, "EUR").toString());
    }
}
