package io.github.arsalanrc.fanout.integration;

import io.github.arsalanrc.fanout.core.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

/**
 * A low-cost carrier's shape: the seat, and a price list for everything else.
 *
 * <p><b>This shape is modelled, not recorded, and the reason matters.</b>
 * Ryanair, Wizz Air and the other European budget airlines do not publish an
 * API. Staying out of third-party channels is their distribution strategy
 * rather than an oversight, and the routes that do carry them are commercial
 * contracts. So this parser reads a payload shaped the way those carriers
 * actually price: a low base, everything else itemised, and a fee on the
 * payment itself. Saying so plainly is better than implying a source that does
 * not exist.
 *
 * <p>Four differences from the full-service shape, and every one of them is a
 * way a metasearch gets the answer wrong:
 *
 * <ol>
 *   <li><b>The price is per passenger</b>, so a search for three costs three
 *       times what the payload says. The other shape quotes the booking. Get
 *       this backwards in either direction and the cheapest fare is wrong by a
 *       factor.
 *   <li><b>Bags are sold, not included.</b> They arrive as a price list and
 *       become priced ancillaries rather than being dropped.
 *   <li><b>Times carry an explicit offset</b>, which is easier than the local
 *       times the other shape sends, and worth handling separately rather than
 *       forcing one code path to guess which it is looking at.
 *   <li><b>Amounts are numbers, not strings</b>, and in major units with a
 *       currency beside them. Read through a double they lose a cent.
 * </ol>
 */
public final class LowCostParser implements SupplierParser {

    private final String supplier;

    public LowCostParser(String supplier) {
        this.supplier = supplier;
    }

    @Override
    public String supplier() {
        return supplier;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code receivedAt} is ignored here, and that is the good case. This
     * supplier states {@code holdsUntil} in the payload, so the expiry is its
     * own answer rather than something inferred on this side.
     */
    @Override
    public List<Fare> parse(String body, Query query, Instant receivedAt) {
        Json root = Json.parse(body);
        List<Fare> fares = new ArrayList<>();

        for (Json flight : root.get("flights").arrayOrEmpty()) {
            Currency currency = Currency.getInstance(flight.get("currency").text());
            int digits = currency.getDefaultFractionDigits();

            /*
             * Per passenger, times the passengers. The multiply happens here,
             * inside the connector that knows this supplier quotes that way,
             * and never in the ranking code, which must not have to care.
             */
            Money perPerson = new Money(flight.path("fare", "amount").minorUnits(digits), currency);
            Money base = perPerson.times(query.passengers());

            fares.add(new Fare(supplier, itineraryOf(flight), base,
                    extras(flight.get("extras"), currency, query.passengers(), digits),
                    Instant.parse(flight.get("holdsUntil").text())));
        }

        return fares;
    }

    private static Itinerary itineraryOf(Json flight) {
        List<Leg> legs = new ArrayList<>();

        for (Json hop : flight.get("legs").arrayOrEmpty()) {
            legs.add(new Leg(
                    hop.get("airline").text(),
                    hop.get("flightNo").text(),
                    hop.get("from").text(),
                    hop.get("to").text(),
                    // An offset is present, so this is a real instant already.
                    // No airport table needed and no assumption made.
                    java.time.OffsetDateTime.parse(hop.get("departsAt").text()).toInstant(),
                    java.time.OffsetDateTime.parse(hop.get("arrivesAt").text()).toInstant(),
                    // A carrier selling direct knows its own fleet and says so.
                    hop.get("aircraft").isMissing() ? null : hop.get("aircraft").text()));
        }

        return new Itinerary(legs);
    }

    /**
     * The price list beside the seat.
     *
     * <p>Bags are per passenger like the fare. The payment fee is not: it is
     * charged once on the booking, and multiplying it by the passenger count is
     * a small, plausible overcharge that would make this carrier look worse
     * than it is. Different things scale differently, which is exactly why this
     * cannot be one blanket multiply at the end.
     */
    private static List<Ancillary> extras(Json extras, Currency currency, int passengers, int digits) {
        List<Ancillary> out = new ArrayList<>();

        add(out, extras, "cabinBag", Ancillary.Kind.CABIN_BAG, currency, passengers, digits);
        add(out, extras, "checkedBag", Ancillary.Kind.CHECKED_BAG, currency, passengers, digits);
        add(out, extras, "seat", Ancillary.Kind.SEAT_SELECTION, currency, passengers, digits);

        Json fee = extras.get("paymentFee");
        if (!fee.isMissing()) {
            Money amount = new Money(fee.minorUnits(digits), currency);
            // Zero is a real answer meaning this carrier charges nothing to pay,
            // and recording it as a sale worth nothing would be true and would
            // read worse everywhere it is shown. Same rule as the bags above.
            out.add(amount.isZero()
                    ? Ancillary.included(Ancillary.Kind.PAYMENT_FEE, currency)
                    : Ancillary.at(Ancillary.Kind.PAYMENT_FEE, amount));
        }

        return out;
    }

    private static void add(List<Ancillary> out, Json extras, String field, Ancillary.Kind kind,
                            Currency currency, int passengers, int digits) {
        Json value = extras.get(field);
        if (value.isMissing()) return;

        Money each = new Money(value.minorUnits(digits), currency);

        // Free is expressed as zero here, and zero is genuinely included rather
        // than sold. Recording it as a zero-priced sale would be true and would
        // read worse everywhere it is shown.
        out.add(each.isZero()
                ? Ancillary.included(kind, currency)
                : Ancillary.at(kind, each.times(passengers)));
    }
}
