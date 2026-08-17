package io.github.arsalanrc.fanout.integration;

import io.github.arsalanrc.fanout.core.Ancillary;
import io.github.arsalanrc.fanout.core.Fare;
import io.github.arsalanrc.fanout.core.Itinerary;
import io.github.arsalanrc.fanout.core.Leg;
import io.github.arsalanrc.fanout.core.Money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

/**
 * The internal fare model on the wire, between the two services.
 *
 * <p>Once {@code search} and {@code integration} are separate processes, the
 * normalised fare has to cross a network, and that crossing is a second place a
 * price can be quietly corrupted. So the format is defined here rather than
 * improvised at each end, and both ends use this class.
 *
 * <p><b>Money travels as minor units and a currency code, never as a decimal.</b>
 * {@code "amount": 21.49} in JSON is a double on arrival in most readers, and
 * {@code 21.49 * 100} is {@code 2148.9999...}. Every guard in {@link Money}
 * would then be defending a number that lost a cent before it got there. So the
 * wire carries {@code 2149} and {@code "EUR"}, and there is no decimal point
 * anywhere in this format.
 *
 * <p><b>Times travel as instants in UTC.</b> Suppliers send local times, offset
 * times and epoch milliseconds, and reconciling those is the connector's job.
 * By the time a fare reaches this format that work is finished, so re-admitting
 * an ambiguous time here would undo it.
 *
 * <p><b>Reading goes back through the real constructors.</b> This class builds
 * {@link Leg}, {@link Fare} and {@link Ancillary} rather than assembling
 * structures behind their backs, so every invariant they enforce holds on the
 * receiving side too. A payload claiming a leg that lands before it leaves, or
 * a fare mixing two currencies, is refused on arrival exactly as it would be in
 * process.
 *
 * <h2>The format</h2>
 *
 * <pre>
 * {
 *   "supplier": "fineair",
 *   "fares": [{
 *     "supplier": "fineair",
 *     "base": { "minor": 1499, "currency": "EUR" },
 *     "expiresAt": "2026-09-01T06:15:00Z",
 *     "legs": [{ "carrier": "FE", "number": "1108",
 *                "origin": "DUS", "destination": "STN",
 *                "departure": "2026-09-01T04:35:00Z",
 *                "arrival": "2026-09-01T05:55:00Z" }],
 *     "ancillaries": [{ "kind": "CABIN_BAG", "minor": 0,
 *                       "currency": "EUR", "included": true }]
 *   }]
 * }
 * </pre>
 */
public final class Wire {

    /**
     * How much of the caller's budget is left, in milliseconds.
     *
     * <p>This header is the whole reason the two services are worth separating.
     * A deadline that stops at a process boundary is not a deadline, it is a
     * timeout with extra steps, and the downstream service starts a fresh
     * allowance of its own. gRPC carries {@code grpc-timeout} for the same
     * reason; over plain HTTP the header has to be named and honoured by hand.
     */
    public static final String DEADLINE_HEADER = "X-Fanout-Deadline-Ms";

    /**
     * What the downstream service actually granted, in milliseconds.
     *
     * <p>Sent back on every answer, because a caller asking for two seconds and
     * silently receiving five hundred milliseconds has no way to tell. The
     * ceiling is visible in the response rather than only in the source.
     */
    public static final String GRANTED_HEADER = "X-Fanout-Budget-Ms";

    private Wire() {
    }

    // ------------------------------------------------------------- writing

    /** One supplier's answer, as the integration service sends it. */
    public static String fares(String supplier, List<Fare> fares) {
        JsonWriter out = new JsonWriter().object();
        out.field("supplier", supplier);
        out.array("fares");

        for (Fare fare : fares) write(out, fare);

        return out.end().end().done();
    }

    private static void write(JsonWriter out, Fare fare) {
        out.object();
        out.field("supplier", fare.supplier());

        money(out, "base", fare.base());
        out.field("expiresAt", fare.expiresAt().toString());

        out.array("legs");
        for (Leg leg : fare.itinerary().legs()) {
            out.object()
                    .field("carrier", leg.carrier())
                    .field("number", leg.number())
                    .field("origin", leg.origin())
                    .field("destination", leg.destination())
                    .field("departure", leg.departure().toString())
                    .field("arrival", leg.arrival().toString())
                    .end();
        }
        out.end();

        out.array("ancillaries");
        for (Ancillary extra : fare.ancillaries()) {
            out.object()
                    .field("kind", extra.kind().name())
                    .field("minor", extra.price().minorUnits())
                    .field("currency", extra.price().currency().getCurrencyCode())
                    .field("included", extra.included())
                    .end();
        }
        out.end();

        out.end();
    }

    /** An amount as minor units and a code, which is the only shape allowed here. */
    public static void money(JsonWriter out, String field, Money amount) {
        out.object(field)
                .field("minor", amount.minorUnits())
                .field("currency", amount.currency().getCurrencyCode())
                .end();
    }

    // ------------------------------------------------------------- reading

    /** The fares out of one supplier's answer. */
    public static List<Fare> fares(String body) {
        Json document = Json.parse(body);
        List<Fare> fares = new ArrayList<>();

        for (Json fare : document.get("fares").arrayOrEmpty()) fares.add(fare(fare));

        return List.copyOf(fares);
    }

    private static Fare fare(Json json) {
        List<Leg> legs = new ArrayList<>();
        for (Json leg : json.get("legs").arrayOrEmpty()) {
            legs.add(new Leg(
                    leg.get("carrier").text(),
                    leg.get("number").text(),
                    leg.get("origin").text(),
                    leg.get("destination").text(),
                    Instant.parse(leg.get("departure").text()),
                    Instant.parse(leg.get("arrival").text())));
        }

        List<Ancillary> ancillaries = new ArrayList<>();
        for (Json extra : json.get("ancillaries").arrayOrEmpty()) {
            Money price = money(extra);
            ancillaries.add(extra.get("included").bool()
                    ? Ancillary.included(Ancillary.Kind.valueOf(extra.get("kind").text()), price.currency())
                    : Ancillary.at(Ancillary.Kind.valueOf(extra.get("kind").text()), price));
        }

        return new Fare(
                json.get("supplier").text(),
                new Itinerary(legs),
                money(json.get("base")),
                ancillaries,
                Instant.parse(json.get("expiresAt").text()));
    }

    /**
     * An amount, read without a {@code double} in the path.
     *
     * <p>{@code minorUnits(0)} refuses anything with a decimal point, which is
     * the point: a sender that started writing {@code 21.49} has changed the
     * format, and being told so at the first message is better than a cent
     * going missing from every fare for a month.
     */
    private static Money money(Json json) {
        return new Money(
                json.get("minor").minorUnits(0),
                Currency.getInstance(json.get("currency").text()));
    }
}
