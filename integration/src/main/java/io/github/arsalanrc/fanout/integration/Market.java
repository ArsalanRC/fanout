package io.github.arsalanrc.fanout.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The invented European market this project searches.
 *
 * <p><b>Every airline here is fictional, and that is deliberate rather than
 * lazy.</b> Modelling a real carrier's fare structure under its own name would
 * put invented prices next to a real brand, and somebody would eventually read
 * them as that airline's actual charges. Inventing the airlines removes the
 * question entirely, and costs nothing: the argument is about how differently
 * carriers price, not about who they are.
 *
 * <p>It also means the fixtures can be built to show the phenomenon properly.
 * Real data is whatever it happens to be that Tuesday; this market is arranged
 * so the ranking genuinely flips between a cabin bag and a suitcase, which is
 * the thing worth demonstrating.
 *
 * <h2>The carriers</h2>
 *
 * <table>
 *   <tr><td>{@code FE}</td><td>Fineair</td><td>Deep discount. Tiny base, dear bags, a payment fee</td></tr>
 *   <tr><td>{@code BZ}</td><td>Bizzair</td><td>Budget. Low base, moderate bags, no payment fee</td></tr>
 *   <tr><td>{@code AL}</td><td>Altair</td><td>Full service. Cabin and checked bag in the fare</td></tr>
 *   <tr><td>{@code HY}</td><td>Halcyon</td><td>Premium. Dearest headline, two bags included</td></tr>
 * </table>
 *
 * <h2>The suppliers, which are not the carriers</h2>
 *
 * <p>A supplier sells seats; a carrier flies them. Keeping them apart is what
 * makes deduplication meaningful, and it is how the market actually works:
 * budget airlines sell direct and stay out of the aggregators, full-service
 * carriers distribute through them, and an online travel agent resells whatever
 * it can get.
 *
 * <table>
 *   <tr><td>{@code openfare}</td><td>A GDS aggregator carrying Altair and Halcyon. Amadeus-shaped payload</td></tr>
 *   <tr><td>{@code fineair}</td><td>Fineair direct. Low-cost payload</td></tr>
 *   <tr><td>{@code bizzair}</td><td>Bizzair direct. Low-cost payload</td></tr>
 *   <tr><td>{@code voyago}</td><td>An agent reselling all of them at a markup. Third payload shape</td></tr>
 * </table>
 *
 * <p>So Fineair flight 1108 appears twice, from {@code fineair} and from
 * {@code voyago}, at different prices. That is the case deduplication exists
 * for, and it is the case a page gets wrong by showing two rows.
 *
 * <p>Each carrier has a mark and a livery colour in {@code site/logos}, which
 * the results page uses. They are invented along with the airlines, and the
 * README there says so.
 */
public final class Market {

    private Market() {
    }

    /**
     * Every supplier, answering at a different speed.
     *
     * <p>The latencies are not decoration. A fan-out where everything returns
     * instantly proves nothing about deadlines, and the whole architecture is
     * built around one supplier always being slower than the rest. `voyago`
     * takes the longest because an agent has to ask everybody else first, which
     * is exactly why real ones are slow.
     */
    public static List<Connector> suppliers() {
        return List.of(
                new FixtureConnector(new AmadeusParser("openfare"),
                        "/fixtures/openfare-dus-stn.json", Duration.ofMillis(120), asOf()),
                new FixtureConnector(new LowCostParser("fineair"),
                        "/fixtures/fineair-dus-stn.json", Duration.ofMillis(80), asOf()),
                new FixtureConnector(new LowCostParser("bizzair"),
                        "/fixtures/bizzair-dus-stn.json", Duration.ofMillis(200), asOf()),
                new FixtureConnector(new ResellerParser("voyago"),
                        "/fixtures/voyago-dus-stn.json", Duration.ofMillis(650), asOf()));
    }

    /**
     * The moment this market is quoted at.
     *
     * <p>Every fare in the fixtures is held until {@code 2026-09-01T06:15:00Z},
     * because a real quote expires and pretending otherwise would throw away
     * the freshness rule the whole model is built on. Judged against the wall
     * clock, the demo therefore returns nothing at all from that date onward:
     * the fares are still parsed, still merged, and then all dropped as lapsed.
     *
     * <p>An empty page is the worst possible way to demonstrate that, because
     * it looks exactly like a broken search. So anything running on fixtures
     * asks the market when it was quoted and judges freshness against that.
     * This instant sits before the earliest departure and before every hold
     * lapses, so the whole modelled market is live.
     *
     * <p><b>Only fixtures get this.</b> A connector reaching a real supplier
     * uses the real clock, which is the only place the question means anything.
     * The alternative, rewriting the timestamps in the payloads at load time,
     * was rejected: it would put a step in front of the parser that the live
     * path does not have, and the fixtures would stop being what a connector
     * actually receives.
     */
    public static Instant asOf() {
        return Instant.parse("2026-09-01T04:00:00Z");
    }

    /** The airline behind a two-letter code, for anything that shows one. */
    public static String carrier(String code) {
        return switch (code) {
            case "FE" -> "Fineair";
            case "BZ" -> "Bizzair";
            case "AL" -> "Altair";
            case "HY" -> "Halcyon";
            default -> code;
        };
    }
}
