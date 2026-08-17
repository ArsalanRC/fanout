package io.github.arsalanrc.fanout.search;

import io.github.arsalanrc.fanout.core.*;
import io.github.arsalanrc.fanout.integration.Connector;
import io.github.arsalanrc.fanout.telemetry.OtlpJson;
import io.github.arsalanrc.fanout.telemetry.Span;
import io.github.arsalanrc.fanout.telemetry.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fan-out, against connectors that behave badly on purpose.
 *
 * <p>Every case here is about what happens when a supplier does not cooperate,
 * because a metasearch where everything answers is not a metasearch, it is a
 * loop.
 */
class FanoutTest {

    private static final Query QUERY = new Query("DUS", "STN", LocalDate.of(2026, 9, 1), 1);
    private static final Instant NOON = Instant.parse("2026-09-01T12:00:00Z");

    private static final Itinerary MORNING = Itinerary.of(
            new Leg("SH", "8542", "DUS", "STN", NOON, NOON.plus(Duration.ofMinutes(80))));
    private static final Itinerary EVENING = Itinerary.of(
            new Leg("SH", "8560", "DUS", "STN", NOON.plusSeconds(36_000),
                    NOON.plusSeconds(36_000).plus(Duration.ofMinutes(80))));

    private static Fare fare(String supplier, Itinerary itinerary, long cents) {
        return Fare.inclusive(supplier, itinerary, Money.of(cents, "EUR"), NOON.plusSeconds(900));
    }

    /** Answers immediately with whatever it was given. */
    private record Fixed(String id, List<Fare> fares) implements Connector {
        @Override
        public List<Fare> search(Query query, Deadline deadline) {
            return fares;
        }
    }

    /** Never answers inside any realistic budget. */
    private record Hangs(String id) implements Connector {
        @Override
        public List<Fare> search(Query query, Deadline deadline) throws InterruptedException {
            Thread.sleep(Duration.ofSeconds(30));
            return List.of();
        }
    }

    /**
     * Ignores interruption, the way a blocking socket read can.
     *
     * <p>Cancelling a task only asks. A connector stuck in native I/O, or one
     * that swallows {@link InterruptedException} in a retry loop, keeps going
     * regardless, and it is the case that decides whether the executor is shut
     * down or merely closed.
     */
    private record Stubborn(String id, Duration runsFor) implements Connector {
        @Override
        public List<Fare> search(Query query, Deadline deadline) {
            long until = System.nanoTime() + runsFor.toNanos();
            while (System.nanoTime() < until) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    // Deliberately swallowed. That is the point of this mock.
                }
            }
            return List.of();
        }
    }

    /** Throws, the way a supplier with an expired certificate would. */
    private record Broken(String id) implements Connector {
        @Override
        public List<Fare> search(Query query, Deadline deadline) {
            throw new IllegalStateException("certificate expired");
        }
    }

    @Test
    @DisplayName("a supplier that hangs costs the deadline once and not the search")
    void a_hanging_supplier_does_not_fail_the_search() {
        Fanout fanout = new Fanout(List.of(
                new Fixed("altair", List.of(fare("altair", MORNING, 8_900))),
                new Hangs("glacier")));

        long started = System.nanoTime();
        SearchResult result = fanout.search(QUERY, Basket.SEAT_ONLY,
                Deadline.in(Duration.ofMillis(300)), NOON);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        // The working supplier's fares are still returned.
        assertEquals(1, result.itineraries().size());
        assertEquals("altair", result.cheapest().orElseThrow().best().supplier());

        // And the search says plainly that it is incomplete.
        assertFalse(result.complete());
        assertEquals(List.of("glacier"),
                result.missing().stream().map(SupplierOutcome::supplier).toList());

        // It came back on time rather than waiting the full thirty seconds.
        assertTrue(took.toMillis() < 3_000, "the search took " + took.toMillis() + "ms");
    }

    @Test
    @DisplayName("a supplier that ignores cancellation still does not hold the search open")
    void the_search_returns_even_when_a_connector_refuses_to_stop() {
        // Cancelling a task only asks it to stop. This one says no, which is
        // what a blocking socket read does. The executor is shut down rather
        // than closed, because closing waits for every task to finish and this
        // task will not finish for five seconds.
        Fanout fanout = new Fanout(List.of(
                new Fixed("altair", List.of(fare("altair", MORNING, 8_900))),
                new Stubborn("glacier", Duration.ofSeconds(5))));

        long started = System.nanoTime();
        SearchResult result = fanout.search(QUERY, Basket.SEAT_ONLY,
                Deadline.in(Duration.ofMillis(200)), NOON);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertEquals(1, result.itineraries().size());
        assertFalse(result.complete());
        assertTrue(took.toMillis() < 2_000,
                "the search waited " + took.toMillis() + "ms for a connector that ignores cancellation");
    }

    @Test
    @DisplayName("and it is reported as having taken the budget, not as having taken no time")
    void a_cancelled_supplier_is_timed_from_the_fan_out() {
        /*
         * This is the one path where the supplier's own timing is unavailable:
         * the task was still running when the budget went, so it never got to
         * report anything. `Stubborn` is what forces it, because a connector
         * that honours interruption reports its own elapsed time on the way out.
         *
         * Reporting zero here is not a harmless placeholder. It renders as the
         * fastest supplier in the list, directly beside the word TIMED_OUT.
         */
        Fanout fanout = new Fanout(List.of(new Stubborn("glacier", Duration.ofSeconds(5))));

        long started = System.nanoTime();
        SearchResult result = fanout.search(QUERY, Basket.SEAT_ONLY,
                Deadline.in(Duration.ofMillis(200)), NOON);
        long searchTook = Duration.ofNanos(System.nanoTime() - started).toMillis();

        SupplierOutcome glacier = result.outcomes().getFirst();
        assertEquals(SupplierOutcome.Status.TIMED_OUT, glacier.status());

        /*
         * Measured against the search rather than against the budget, and that
         * is deliberate. A fixed floor near 200ms fails on a cold JVM, where
         * class loading spends a third of the budget before the fan-out even
         * starts: this assertion read 143ms the first time it ran alone. That
         * is the same shape of test that made PR #4 pass here and fail in CI.
         *
         * Half of whatever the search actually took is immune to that, because
         * a slow start makes both numbers slow together.
         */
        assertTrue(glacier.took().toMillis() >= searchTook / 2,
                "a supplier that ran for the whole " + searchTook + "ms search reported "
                        + glacier.took().toMillis() + "ms");
    }

    @Test
    void a_broken_supplier_is_reported_rather_than_raised() {
        SearchResult result = new Fanout(List.of(
                new Fixed("altair", List.of(fare("altair", MORNING, 8_900))),
                new Broken("glacier")))
                .search(QUERY, Basket.SEAT_ONLY, Deadline.in(Duration.ofSeconds(5)), NOON);

        SupplierOutcome broken = result.outcomes().stream()
                .filter(o -> o.supplier().equals("glacier")).findFirst().orElseThrow();

        assertEquals(SupplierOutcome.Status.FAILED, broken.status());
        assertTrue(broken.detail().contains("certificate expired"));
        assertEquals(1, result.itineraries().size());
    }

    @Test
    @DisplayName("the same flight from three suppliers is one row with three prices")
    void identical_journeys_collapse_into_one_row() {
        SearchResult result = new Fanout(List.of(
                new Fixed("altair", List.of(fare("altair", MORNING, 9_500))),
                new Fixed("borealis", List.of(fare("borealis", MORNING, 12_400))),
                new Fixed("cirrus", List.of(fare("cirrus", MORNING, 8_900)))))
                .search(QUERY, Basket.SEAT_ONLY, Deadline.in(Duration.ofSeconds(5)), NOON);

        assertEquals(1, result.itineraries().size());

        PricedItinerary row = result.itineraries().getFirst();
        assertEquals(3, row.sellers());
        assertEquals("cirrus", row.best().supplier());
        // 124.00 against 89.00. The spread is the reason to shop around, and
        // three separate rows would have hidden it.
        assertEquals(Money.of(3_500, "EUR"), row.spread());
    }

    @Test
    void rows_are_ordered_by_what_the_traveller_actually_pays() {
        SearchResult result = new Fanout(List.of(
                new Fixed("altair", List.of(fare("altair", EVENING, 6_500), fare("altair", MORNING, 8_900)))))
                .search(QUERY, Basket.SEAT_ONLY, Deadline.in(Duration.ofSeconds(5)), NOON);

        assertEquals(2, result.itineraries().size());
        assertEquals(Money.of(6_500, "EUR"), result.itineraries().getFirst().bestPrice());
        assertEquals(Money.of(8_900, "EUR"), result.itineraries().get(1).bestPrice());
    }

    @Test
    @DisplayName("a lapsed fare is dropped rather than sorted to the top")
    void expired_fares_never_reach_the_results() {
        Fare lapsed = Fare.inclusive("glacier", MORNING, Money.of(4_900, "EUR"), NOON.minusSeconds(1));
        Fare live = fare("altair", MORNING, 8_900);

        SearchResult result = new Fanout(List.of(
                new Fixed("glacier", List.of(lapsed)), new Fixed("altair", List.of(live))))
                .search(QUERY, Basket.SEAT_ONLY, Deadline.in(Duration.ofSeconds(5)), NOON);

        // The lapsed one is the cheapest by a mile, which is exactly why it
        // would have been clicked and exactly why it must not be shown.
        assertEquals(1, result.itineraries().getFirst().sellers());
        assertEquals("altair", result.itineraries().getFirst().best().supplier());
    }

    @Test
    @DisplayName("a fare that cannot cover the basket is left out rather than mispriced")
    void fares_that_cannot_meet_the_basket_are_dropped() {
        Fare handLuggageOnly = new Fare("glacier", MORNING, Money.of(4_900, "EUR"),
                List.of(Ancillary.at(Ancillary.Kind.CABIN_BAG, Money.of(1_000, "EUR"))),
                NOON.plusSeconds(900));
        Fare full = new Fare("altair", MORNING, Money.of(8_900, "EUR"),
                List.of(Ancillary.included(Ancillary.Kind.CABIN_BAG, java.util.Currency.getInstance("EUR")),
                        Ancillary.included(Ancillary.Kind.CHECKED_BAG, java.util.Currency.getInstance("EUR"))),
                NOON.plusSeconds(900));

        SearchResult result = new Fanout(List.of(
                new Fixed("glacier", List.of(handLuggageOnly)), new Fixed("altair", List.of(full))))
                .search(QUERY, Basket.WITH_CHECKED_BAG, Deadline.in(Duration.ofSeconds(5)), NOON);

        assertEquals(1, result.itineraries().getFirst().sellers());
        assertEquals("altair", result.itineraries().getFirst().best().supplier());
    }

    @Test
    @DisplayName("a supplier that keeps failing stops being called at all")
    void the_breaker_drops_a_dead_supplier() {
        AtomicInteger calls = new AtomicInteger();
        Connector counting = new Connector() {
            @Override
            public String id() {
                return "glacier";
            }

            @Override
            public List<Fare> search(Query query, Deadline deadline) {
                calls.incrementAndGet();
                throw new IllegalStateException("down");
            }
        };

        Fanout fanout = new Fanout(
                List.of(new Fixed("altair", List.of(fare("altair", MORNING, 8_900))), counting),
                new Breaker(2, Duration.ofSeconds(30)));

        for (int i = 0; i < 4; i++) {
            fanout.search(QUERY, Basket.SEAT_ONLY, Deadline.in(Duration.ofSeconds(5)), NOON);
        }

        // Two failures trip it, so it is never called a third time. Without
        // this, a dead supplier costs the deadline on every single search and
        // every other supplier gets slower because of it.
        assertEquals(2, calls.get());

        SearchResult last = fanout.search(QUERY, Basket.SEAT_ONLY, Deadline.in(Duration.ofSeconds(5)), NOON);
        SupplierOutcome skipped = last.outcomes().stream()
                .filter(o -> o.supplier().equals("glacier")).findFirst().orElseThrow();

        assertEquals(SupplierOutcome.Status.SKIPPED, skipped.status());
        assertTrue(skipped.detail().contains("breaker open"));
    }

    @Test
    void a_timeout_does_not_count_against_the_breaker() {
        // Lateness is not breakage. Counting a slow day as a failure drops a
        // supplier that works, and it would happen every time the network was
        // busy.
        Fanout fanout = new Fanout(List.of(new Hangs("glacier")), new Breaker(2, Duration.ofSeconds(30)));

        for (int i = 0; i < 3; i++) {
            fanout.search(QUERY, Basket.SEAT_ONLY, Deadline.in(Duration.ofMillis(80)), NOON);
        }

        SearchResult result = fanout.search(QUERY, Basket.SEAT_ONLY,
                Deadline.in(Duration.ofMillis(80)), NOON);
        SupplierOutcome outcome = result.outcomes().getFirst();

        assertEquals(SupplierOutcome.Status.TIMED_OUT, outcome.status());
    }

    @Test
    void every_supplier_answering_is_reported_as_complete() {
        SearchResult result = new Fanout(List.of(
                new Fixed("altair", List.of(fare("altair", MORNING, 8_900))),
                new Fixed("borealis", List.of(fare("borealis", EVENING, 7_400)))))
                .search(QUERY, Basket.SEAT_ONLY, Deadline.in(Duration.ofSeconds(5)), NOON);

        assertTrue(result.complete());
        assertTrue(result.missing().isEmpty());
        assertEquals(2, result.itineraries().size());
    }

    @Test
    @DisplayName("the trace says which supplier ate the deadline")
    void the_trace_records_every_supplier_including_the_ones_never_called() {
        List<List<Span>> traces = new java.util.ArrayList<>();

        Fanout fanout = new Fanout(
                List.of(new Fixed("altair", List.of(fare("altair", MORNING, 8_900))),
                        new Broken("glacier"),
                        new Hangs("borealis")),
                new Breaker(1, Duration.ofSeconds(30)),
                new Tracer(traces::add));

        fanout.search(QUERY, Basket.SEAT_ONLY, Deadline.in(Duration.ofMillis(200)), NOON);

        List<Span> first = traces.getFirst();
        // One root plus one per supplier called.
        assertEquals(4, first.size());

        Span root = first.stream().filter(s -> s.parentSpanId() == null).findFirst().orElseThrow();
        assertEquals("metasearch", root.name());
        assertEquals("DUS-STN", root.attributes().get("route"));
        assertEquals(3, root.attributes().get("suppliers.asked"));

        // The broken one carries the reason, which is the whole point of
        // looking at a trace rather than a counter.
        Span broken = named(first, "glacier");
        assertEquals(Span.Status.ERROR, broken.status());
        assertTrue(broken.statusMessage().contains("certificate expired"));

        // The slow one is an error on the span even though it is not a breaker
        // failure, because "who spent the budget" is what a reader wants.
        assertEquals(Span.Status.ERROR, named(first, "borealis").status());
        assertEquals(Span.Status.OK, named(first, "altair").status());

        // Second search: glacier tripped a breaker of one, so it is skipped and
        // still gets a span. A trace showing nothing for it would read as
        // "never configured" rather than "deliberately not called".
        fanout.search(QUERY, Basket.SEAT_ONLY, Deadline.in(Duration.ofMillis(200)), NOON);
        Span skipped = named(traces.get(1), "glacier");
        assertEquals("supplier.skipped", skipped.name());
        assertEquals("breaker open", skipped.attributes().get("reason"));

        // And the whole thing serialises to something a Collector accepts.
        String json = OtlpJson.document(first);
        assertTrue(json.startsWith("{\"resourceSpans\":["), json);
        assertTrue(json.contains("\"stringValue\":\"fanout\""), json);
    }

    private static Span named(List<Span> spans, String supplier) {
        return spans.stream()
                .filter(s -> supplier.equals(s.attributes().get("supplier")))
                .findFirst().orElseThrow(() -> new AssertionError("no span for " + supplier));
    }

    @Test
    void a_search_with_nothing_to_ask_is_empty_rather_than_broken() {
        SearchResult result = new Fanout(List.of())
                .search(QUERY, Basket.SEAT_ONLY, Deadline.in(Duration.ofSeconds(1)), NOON);

        assertTrue(result.itineraries().isEmpty());
        assertTrue(result.complete());
    }
}
