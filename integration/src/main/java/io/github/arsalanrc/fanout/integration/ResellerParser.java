package io.github.arsalanrc.fanout.integration;

import io.github.arsalanrc.fanout.core.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

/**
 * An online travel agent reselling other people's seats.
 *
 * <p>This connector exists to make deduplication real. A reseller does not
 * operate anything: it lists the same physical flights the airlines sell direct,
 * at its own price. So the results contain Fineair flight 1108 twice, once from
 * Fineair and once from here, and collapsing those into one row with two prices
 * is the job. Three rows for one aircraft looks like a full page and hides the
 * only thing worth knowing, which is the spread.
 *
 * <p>Its payload disagrees with both the others on every convention, and that is
 * the point of having a third:
 *
 * <ol>
 *   <li><b>Times are epoch milliseconds.</b> No timezone question at all, which
 *       is the one convention that cannot be got wrong. Worth having beside the
 *       shape that sends local time with no offset, because the contrast is what
 *       shows why that one is dangerous.
 *   <li><b>Amounts are already integer minor units.</b> No decimal parsing, no
 *       rounding, and no chance of a double. A connector that assumed decimals
 *       everywhere would read 2450 as 2450 euros.
 *   <li><b>Baggage is two lists</b>, included and buyable, rather than a price
 *       per item or a quantity. Same information, third encoding.
 *   <li><b>Fees are a list of kinds</b>, and an empty list is a real answer
 *       meaning this seller charges none.
 * </ol>
 */
public final class ResellerParser implements SupplierParser {

    private final String supplier;

    public ResellerParser(String supplier) {
        this.supplier = supplier;
    }

    @Override
    public String supplier() {
        return supplier;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code receivedAt} is ignored: this seller sends
     * {@code holdsUntilEpochMs}, so the hold is stated rather than guessed.
     */
    @Override
    public List<Fare> parse(String body, Query query, Instant receivedAt) {
        Json root = Json.parse(body);
        List<Fare> fares = new ArrayList<>();

        for (Json result : root.get("results").arrayOrEmpty()) {
            Currency currency = Currency.getInstance(result.path("pricing", "currencyCode").text());

            Leg leg = new Leg(
                    result.get("operatedBy").text(),
                    result.get("flightNumber").text(),
                    result.path("route", "from").text(),
                    result.path("route", "to").text(),
                    Instant.ofEpochMilli(result.get("departEpochMs").minorUnits(0)),
                    Instant.ofEpochMilli(result.get("arriveEpochMs").minorUnits(0)),
                    // The agent passes through whatever the carrier told it,
                    // already spelled out.
                    result.get("equipment").isMissing() ? null : result.get("equipment").text());

            /*
             * Already minor units, so no decimal handling. Per passenger, like
             * the low-cost shape and unlike the GDS one, which is exactly the
             * sort of thing that has to be settled per connector rather than
             * guessed once for everybody.
             */
            Money each = new Money(result.path("pricing", "totalMinor").minorUnits(0), currency);
            boolean perPassenger = result.path("pricing", "perPassenger").bool();
            Money base = perPassenger ? each.times(query.passengers()) : each;

            fares.add(new Fare(supplier, new Itinerary(List.of(leg)), base,
                    baggage(result, currency, query.passengers()),
                    Instant.ofEpochMilli(result.get("holdsUntilEpochMs").minorUnits(0))));
        }

        return fares;
    }

    private static List<Ancillary> baggage(Json result, Currency currency, int passengers) {
        List<Ancillary> out = new ArrayList<>();

        for (Json included : result.path("baggage", "included").arrayOrEmpty()) {
            kindOf(included.text()).ifPresent(kind -> out.add(Ancillary.included(kind, currency)));
        }

        for (Json buyable : result.path("baggage", "buyable").arrayOrEmpty()) {
            kindOf(buyable.get("type").text()).ifPresent(kind -> out.add(Ancillary.at(kind,
                    new Money(buyable.get("priceMinor").minorUnits(0), currency).times(passengers))));
        }

        for (Json fee : result.get("fees").arrayOrEmpty()) {
            if (!"PAYMENT".equals(fee.get("kind").text())) continue;

            Money amount = new Money(fee.get("priceMinor").minorUnits(0), currency);
            // Charged once on the booking, so it does not scale with passengers.
            // A zero fee is recorded as included, which is true and reads better
            // than a payment fee sold for nothing.
            out.add(amount.isZero()
                    ? Ancillary.included(Ancillary.Kind.PAYMENT_FEE, currency)
                    : Ancillary.at(Ancillary.Kind.PAYMENT_FEE, amount));
        }

        return out;
    }

    /**
     * This seller's baggage vocabulary, mapped to ours.
     *
     * <p>An unknown type is skipped rather than guessed at. A seller adding
     * "PET" or "SKI" tomorrow should not become a mystery checked bag, and a
     * basket that needs something unmapped will refuse the fare rather than
     * price it wrongly.
     */
    private static java.util.Optional<Ancillary.Kind> kindOf(String type) {
        return switch (type) {
            case "CABIN" -> java.util.Optional.of(Ancillary.Kind.CABIN_BAG);
            case "CHECKED" -> java.util.Optional.of(Ancillary.Kind.CHECKED_BAG);
            case "SEAT" -> java.util.Optional.of(Ancillary.Kind.SEAT_SELECTION);
            default -> java.util.Optional.empty();
        };
    }
}
