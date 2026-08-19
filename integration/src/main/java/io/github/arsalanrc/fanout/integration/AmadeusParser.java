package io.github.arsalanrc.fanout.integration;

import io.github.arsalanrc.fanout.core.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

/**
 * The Amadeus Flight Offers Search shape: a full-service carrier's quote.
 *
 * <p>Three things about this payload decide how it has to be read, and getting
 * any of them wrong produces a fare that looks fine.
 *
 * <p><b>Times are local, with no offset.</b> {@code "2026-09-01T07:00:00"} means
 * seven in the morning at that airport. Reading it as UTC makes a Düsseldorf to
 * Heathrow hop look twenty minutes long. {@link AirportZones} resolves it and
 * refuses airports it does not know.
 *
 * <p><b>The price is for the whole booking, not per passenger.</b> Amadeus
 * quotes {@code price.total} for everybody in {@code travelerPricings}.
 * Multiplying by the passenger count double-counts, and on a search for four it
 * quadruples a fare that was already right.
 *
 * <p><b>One offer is one product, and {@code itineraries} is a list because a
 * round trip is two of them.</b> The total covers the pair, so reading the list
 * as a loop and giving each entry the full price invents a second ticket at
 * double the fare. See {@link #journeyOf(Json)}.
 *
 * <p><b>Bags are in the fare.</b> This is a legacy carrier, so a cabin bag and
 * usually a checked bag are included, and they are recorded as included rather
 * than left out. An absent ancillary and an included one mean opposite things
 * to {@link Fare#totalFor(Basket)}: one is refused, the other costs nothing.
 */
public final class AmadeusParser implements SupplierParser {

    private final String supplier;

    public AmadeusParser(String supplier) {
        this.supplier = supplier;
    }

    @Override
    public String supplier() {
        return supplier;
    }

    @Override
    public List<Fare> parse(String body, Query query, Instant receivedAt) {
        Json root = Json.parse(body);
        List<Fare> fares = new ArrayList<>();

        for (Json offer : root.get("data").arrayOrEmpty()) {
            Json price = offer.get("price");
            Currency currency = Currency.getInstance(price.get("currency").text());

            Money total = new Money(
                    price.get("grandTotal").isMissing()
                            ? price.get("total").minorUnits(currency.getDefaultFractionDigits())
                            : price.get("grandTotal").minorUnits(currency.getDefaultFractionDigits()),
                    currency);

            fares.add(new Fare(supplier, journeyOf(offer), total,
                    included(offer, currency), expiry(receivedAt)));
        }

        return fares;
    }

    /**
     * One offer, one product, however many directions it covers.
     *
     * <p><b>{@code itineraries} is a list because a round trip is two of them</b>,
     * and {@code price.grandTotal} covers the pair. Reading the list as a loop
     * and hanging the same total on each entry produced two one-ways that each
     * cost the whole return, which is the failure this repository keeps choosing
     * to hunt: no error, no missing row, just a price that is quietly double.
     *
     * <p>Three or more is a multi-city or open-jaw offer. It is refused rather
     * than folded into a return, because pricing the first two directions and
     * dropping the third would be the same defect wearing a different hat.
     */
    private static Journey journeyOf(Json offer) {
        List<Json> itineraries = offer.get("itineraries").arrayOrEmpty();

        if (itineraries.isEmpty()) {
            throw new IllegalArgumentException("A flight offer with no itineraries has nothing to sell");
        }
        if (itineraries.size() > 2) {
            throw new IllegalArgumentException(
                    "This offer carries " + itineraries.size() + " itineraries, so it is a multi-city "
                            + "booking rather than a return. Refusing to price it as one.");
        }

        Itinerary outbound = legsOf(itineraries.getFirst());
        return itineraries.size() == 1
                ? Journey.oneWay(outbound)
                : Journey.roundTrip(outbound, legsOf(itineraries.get(1)));
    }

    private static Itinerary legsOf(Json itinerary) {
        List<Leg> legs = new ArrayList<>();

        for (Json segment : itinerary.get("segments").arrayOrEmpty()) {
            String from = segment.path("departure", "iataCode").text();
            String to = segment.path("arrival", "iataCode").text();

            legs.add(new Leg(
                    segment.get("carrierCode").text(),
                    segment.get("number").text(),
                    from,
                    to,
                    AirportZones.instantAt(LocalDateTime.parse(segment.path("departure", "at").text()), from),
                    AirportZones.instantAt(LocalDateTime.parse(segment.path("arrival", "at").text()), to),
                    // Amadeus sends an equipment code, not a name.
                    Aircraft.name(segment.path("aircraft", "code"))));
        }

        return new Itinerary(legs);
    }

    /**
     * What the fare already covers.
     *
     * <p>Read from the traveller's fare details rather than assumed. A carrier
     * selling a hand-luggage-only fare is now common even outside the low-cost
     * airlines, and assuming a checked bag on every legacy fare is the same
     * class of error as assuming none.
     */
    private static List<Ancillary> included(Json offer, Currency currency) {
        List<Ancillary> extras = new ArrayList<>();
        extras.add(Ancillary.included(Ancillary.Kind.CABIN_BAG, currency));

        Json first = offer.get("travelerPricings").arrayOrEmpty().stream().findFirst().orElse(Json.MISSING);
        Json bags = first.path("fareDetailsBySegment").arrayOrEmpty().stream()
                .findFirst().orElse(Json.MISSING)
                .get("includedCheckedBags");

        // `quantity: 0` is a real answer meaning "none", and it is not the same
        // as the field being absent. Absent means this supplier did not say,
        // and that is refused later rather than guessed at here.
        if (!bags.isMissing() && !bags.get("quantity").isMissing()
                && bags.get("quantity").minorUnits(0) > 0) {
            extras.add(Ancillary.included(Ancillary.Kind.CHECKED_BAG, currency));
        }

        return extras;
    }

    /**
     * When this quote stops being usable.
     *
     * <p>Amadeus sends {@code lastTicketingDate}, which is a date rather than a
     * moment and is about ticketing rather than about the price holding. It is
     * not an expiry. A short fixed window is the honest reading of a search
     * result, and it is stated here rather than pretended to come from the API.
     *
     * <p>Measured from when the response arrived, never from the wall clock.
     * The two are the same thing for a live call and nothing like each other for
     * a recorded one, and this is the only supplier where the distinction bites:
     * the other two payload shapes carry an absolute hold, so their fares are
     * dated by the supplier rather than by us.
     */
    private static Instant expiry(Instant receivedAt) {
        return receivedAt.plus(HOLD);
    }

    /** How long a search result is worth acting on. Stated, not received. */
    private static final Duration HOLD = Duration.ofMinutes(15);
}
