package io.github.arsalanrc.fanout.core;

import java.util.Objects;

/**
 * What is being bought: one direction, or both of them as one product.
 *
 * <p>This type exists because a return is not a longer itinerary. {@link
 * Itinerary} holds legs in order and counts the gaps between them as
 * connections, so folding an inbound onto the end of an outbound would report a
 * fortnight in Lisbon as a stop, and {@link Itinerary#total()} would call the
 * whole holiday the journey time. The pair needs its own shape.
 *
 * <p>It also exists because <b>a journey has no single price</b>, in the same
 * way a fare has no single total. Two one-way tickets and one return ticket are
 * two different products for the same trip, and which of them is cheaper is a
 * real question rather than arithmetic. Only some carriers sell the second one,
 * so a metasearch has to ask and then compare.
 *
 * <p>{@code inbound} is null on a one-way, following {@link Leg#aircraft()}:
 * absent means the supplier is not selling one, which is different from selling
 * an empty one. Ask {@link #isReturn()} rather than testing the field.
 */
public record Journey(Itinerary outbound, Itinerary inbound) {

    public Journey {
        Objects.requireNonNull(outbound, "outbound");

        if (inbound != null) {
            // A return that does not come back from where the outbound landed is
            // not a return, it is two unrelated flights that a connector has
            // paired by accident. Cheaper to refuse here than to render.
            if (!inbound.origin().equals(outbound.destination())) {
                throw new IllegalArgumentException(
                        "The return leaves from " + inbound.origin() + " but the outbound landed at "
                                + outbound.destination());
            }
            if (!inbound.destination().equals(outbound.origin())) {
                throw new IllegalArgumentException(
                        "The return lands at " + inbound.destination() + " but the trip started at "
                                + outbound.origin());
            }

            // Suppliers do return legs in whatever order their backend held them,
            // and a pair sorted wrongly prices and renders as a time machine.
            if (inbound.departure().isBefore(outbound.arrival())) {
                throw new IllegalArgumentException(
                        "The return leaves at " + inbound.departure() + ", before the outbound lands at "
                                + outbound.arrival());
            }
        }
    }

    public static Journey oneWay(Itinerary outbound) {
        return new Journey(outbound, null);
    }

    public static Journey roundTrip(Itinerary outbound, Itinerary inbound) {
        return new Journey(outbound, Objects.requireNonNull(inbound, "inbound"));
    }

    public boolean isReturn() {
        return inbound != null;
    }

    /**
     * The identity of this product, independent of who is selling it.
     *
     * <p>Same job as {@link Itinerary#key()} and for the same reason: three
     * suppliers selling the same pair of flights as one return are selling one
     * product at three prices. Built from both directions, so a through fare
     * never dedupes against the one-way that shares its outbound. They are
     * different things to buy.
     */
    public String key() {
        return isReturn() ? outbound.key() + ">" + inbound.key() : outbound.key();
    }

    public String origin() {
        return outbound.origin();
    }

    public String destination() {
        return outbound.destination();
    }
}
