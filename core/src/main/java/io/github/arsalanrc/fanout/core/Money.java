package io.github.arsalanrc.fanout.core;

import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money, held in minor units.
 *
 * <p>Two rules, and both exist because breaking them produces a wrong answer
 * that looks entirely reasonable.
 *
 * <p><b>Never a floating point number.</b> {@code 0.1 + 0.2} is
 * {@code 0.30000000000000004}, and a fare built by adding a base, three taxes
 * and a surcharge accumulates that error until the cheapest of two fares a cent
 * apart is decided by binary rounding. Amounts are longs in minor units:
 * 12345 EUR-cents, never 123.45.
 *
 * <p><b>Never compare across currencies.</b> This is the one that actually
 * bites a metasearch. Supplier A quotes 210 GBP and supplier B quotes 240 EUR.
 * Comparing the numbers picks A, and A is the more expensive fare. Nothing
 * throws, nothing logs, and the headline price on the page is simply wrong.
 * So comparison across currencies is refused outright and conversion has to be
 * asked for by name.
 */
public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(long minorUnits, String currencyCode) {
        return new Money(minorUnits, Currency.getInstance(currencyCode));
    }

    /** Zero in a given currency, which still carries the currency. */
    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other, "add");
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other, "subtract");
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    /**
     * Multiply by a whole number, which is what per-passenger pricing needs.
     *
     * <p>Deliberately takes an int rather than a rate. A percentage surcharge
     * belongs in the connector that knows the supplier's rounding rule, not in
     * a general money type that would have to guess it.
     */
    public Money times(int factor) {
        return new Money(Math.multiplyExact(minorUnits, (long) factor), currency);
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Throws rather than returning a number when the currencies differ.
     * A comparator that silently compares 210 GBP with 240 EUR sorts a result
     * list into the wrong order and nothing downstream can tell.
     */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other, "compare");
        return Long.compare(minorUnits, other.minorUnits);
    }

    private void requireSameCurrency(Money other, String verb) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatch(
                    "Cannot " + verb + " " + this + " and " + other
                            + ". Convert to one currency first, deliberately.");
        }
    }

    /** Raised rather than guessing an exchange rate. */
    public static final class CurrencyMismatch extends RuntimeException {
        public CurrencyMismatch(String message) {
            super(message);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Formatted from the currency's own fraction digits, so JPY prints with
     * none and EUR with two. Hardcoding two is wrong in about a fifth of the
     * world's currencies.
     */
    @Override
    public String toString() {
        int digits = currency.getDefaultFractionDigits();
        if (digits <= 0) return minorUnits + " " + currency.getCurrencyCode();

        long scale = (long) Math.pow(10, digits);
        long whole = minorUnits / scale;
        long fraction = Math.abs(minorUnits % scale);

        return String.format("%d.%0" + digits + "d %s", whole, fraction, currency.getCurrencyCode());
    }
}
