package io.github.arsalanrc.fanout.search;

import io.github.arsalanrc.fanout.core.Deadline;
import io.github.arsalanrc.fanout.core.Fare;
import io.github.arsalanrc.fanout.core.Query;
import io.github.arsalanrc.fanout.integration.Connector;
import io.github.arsalanrc.fanout.integration.HttpConnector;
import io.github.arsalanrc.fanout.integration.IntegrationServer;
import io.github.arsalanrc.fanout.integration.Json;
import io.github.arsalanrc.fanout.integration.Market;
import io.github.arsalanrc.fanout.telemetry.SpanSink;
import io.github.arsalanrc.fanout.telemetry.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Both services, two real HTTP servers, one search.
 *
 * <p>This is the test the repository is for. Everything below the edge already
 * had unit coverage in one JVM, and running in one JVM is precisely what hides
 * the failures worth showing: a deadline that stops at the process boundary, a
 * timeout arriving as a failure and tripping a breaker, a supplier that drops
 * out of the results while still reporting that it answered.
 */
class TwoServicesTest {

    private IntegrationServer suppliers;
    private SearchServer edge;
    private HttpClient http;

    @AfterEach
    void stop() {
        if (edge != null) edge.close();
        if (suppliers != null) suppliers.close();
    }

    @BeforeEach
    void client() {
        http = HttpClient.newHttpClient();
    }

    @Nested
    @DisplayName("The modelled market, end to end")
    class Demo {

        @BeforeEach
        void bothServices() throws Exception {
            startWith(Market.suppliers());
        }

        @Test
        void answers_with_every_supplier_accounted_for() throws Exception {
            Json result = search("basket=cabin&budget=3000");

            assertTrue(result.get("complete").bool(), "A supplier did not answer: " + result);
            assertEquals("distributed", result.get("mode").text());
            assertEquals(4, result.get("suppliers").array().size());
        }

        @Test
        @DisplayName("and every one of them reaches the results")
        void no_supplier_answers_and_then_vanishes() throws Exception {
            /*
             * The guard for a defect that shipped in this branch and looked
             * completely fine. `AmadeusParser` dated its expiry from
             * Instant.now(), so a recorded response was fresh for fifteen
             * minutes starting whenever it was read. Judged against the moment
             * the market is actually quoted, every one of those fares was
             * lapsed, and the merge dropped all of them.
             *
             * Nothing failed. The supplier reported ANSWERED with three fares,
             * the search reported complete, and one of four suppliers silently
             * contributed nothing to the page. A count of rows would not have
             * caught it either, because the other three still had plenty.
             */
            Json result = search("basket=cabin&budget=3000");

            Set<String> selling = result.get("itineraries").array().stream()
                    .flatMap(row -> row.get("offers").array().stream())
                    .map(offer -> offer.get("supplier").text())
                    .collect(Collectors.toSet());

            for (Json outcome : result.get("suppliers").array()) {
                String id = outcome.get("supplier").text();
                assertTrue(selling.contains(id), id + """
                         answered and then appeared in no row at all. Either its fares are being \
                        dropped as lapsed or the merge is losing them, and neither shows up as a \
                        failure anywhere.""");
            }
        }

        @Test
        void one_journey_sold_twice_is_one_row_with_two_prices() throws Exception {
            Json result = search("basket=cabin&budget=3000");

            Json shared = result.get("itineraries").array().stream()
                    .filter(row -> row.get("sellers").minorUnits(0) > 1)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("""
                            Nothing deduplicated. The market is built so that a reseller carries \
                            the same flights as the carriers do, and collapsing those is the \
                            whole reason a dedup key exists."""));

            List<String> sellers = shared.get("offers").array().stream()
                    .map(offer -> offer.get("supplier").text()).toList();

            assertEquals(sellers.size(), Set.copyOf(sellers).size(), "The same seller twice on one row");
            assertTrue(shared.get("spread").get("minor").minorUnits(0) > 0,
                    "Two sellers at exactly the same price hides what shopping around is worth");
        }

        @Test
        void the_cheapest_row_is_cheapest_for_the_basket_and_not_for_the_headline() throws Exception {
            Json result = search("basket=cabin&budget=3000");
            List<Json> rows = result.get("itineraries").array();

            long previous = Long.MIN_VALUE;
            for (Json row : rows) {
                long best = row.get("best").get("minor").minorUnits(0);
                assertTrue(best >= previous, "Rows came back out of order: " + best + " after " + previous);
                previous = best;
            }

            // Inside a row the offers are sorted too, so the first is the one a
            // page would show and it cannot disagree with `best`.
            for (Json row : rows) {
                assertEquals(row.get("best").get("minor").minorUnits(0),
                        row.get("offers").array().getFirst().get("total").get("minor").minorUnits(0));
            }
        }

        @Test
        @DisplayName("a lapsed fare is dropped, counted, and would have been the cheapest")
        void the_market_carries_one_expired_quote() throws Exception {
            /*
             * voyago sells BZ508 at 17.99, which is cheaper than anything else
             * on the route, and its hold ran out half an hour before the market
             * is quoted. So it never reaches the page.
             *
             * That is the correct behaviour and it is completely invisible,
             * which is why the count exists. A lapsed quote sorts to the top,
             * gets clicked, and fails at the moment somebody is paying.
             */
            Json result = search("basket=cabin&budget=3000");

            assertEquals(1, result.path("dropped", "lapsed").minorUnits(0),
                    "The expired offer should have been dropped and counted: " + result);

            long cheapest = result.get("itineraries").array().getFirst()
                    .get("best").get("minor").minorUnits(0);
            assertTrue(cheapest > 1799,
                    "A fare that had already lapsed is being shown at " + cheapest);
        }

        @Test
        @DisplayName("the cheapest carrier is a different one once a suitcase is in the basket")
        void the_winner_changes_with_the_basket() throws Exception {
            String cabin = winner("basket=cabin&budget=3000");
            String checked = winner("basket=checked&budget=3000");

            assertEquals("Fineair", cabin, "Fineair should lead on hand luggage");
            assertEquals("Bizzair", checked, "Bizzair should lead once a bag is counted");
        }

        @Test
        void a_budget_tighter_than_the_slowest_supplier_still_answers() throws Exception {
            long started = System.nanoTime();
            Json result = search("basket=cabin&budget=300");
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertFalse(result.get("complete").bool(),
                    "voyago takes 650ms behind a 300ms budget, so this cannot be complete");

            // Partial results beat no results. The rows that did arrive are
            // real and worth showing.
            assertFalse(result.get("itineraries").array().isEmpty(),
                    "A slow supplier emptied the whole page instead of being reported beside it");

            assertTrue(took.toMillis() < 2000,
                    "The search cost " + took.toMillis() + "ms of a 300ms budget");
        }

        @Test
        void a_supplier_that_missed_the_deadline_is_not_reported_as_instant() throws Exception {
            Json result = search("basket=cabin&budget=300");

            for (Json outcome : result.get("suppliers").array()) {
                if (!outcome.get("status").text().equals("TIMED_OUT")) continue;

                assertTrue(outcome.get("took_ms").minorUnits(0) > 0, """
                        A supplier that spent the whole budget and was then cancelled reported \
                        0ms, which reads as the fastest one in the list. A zero is an invented \
                        number as much as any other.""");
                return;
            }

            fail("Nothing timed out at 300ms, so this test proved nothing");
        }
    }

    @Nested
    @DisplayName("Late and broken stay different across the network")
    class Breakers {

        @Test
        void a_timeout_does_not_count_against_the_supplier() throws Exception {
            Breaker breaker = new Breaker(2, Duration.ofSeconds(30));
            startWith(List.of(slow("sluggish")), breaker);

            // Twice, which is the threshold. A breaker counting lateness would
            // be open by now.
            search("budget=200");
            search("budget=200");

            assertFalse(breaker.isOpen("sluggish"), """
                    Lateness tripped the breaker. On a busy afternoon that drops a supplier \
                    which is working, and the results quietly lose a whole market.""");
        }

        @Test
        @DisplayName("and a 504 sent back over the wire is read as lateness too")
        void a_downstream_504_is_not_a_failure() throws Exception {
            /*
             * The test above never reached this. With a 200ms budget on both
             * sides the caller's own socket gives up first, so it produces a
             * timeout locally and the 504 mapping is never exercised. A
             * mutation swapping that mapping for an IOException survived.
             *
             * Here the downstream ceiling is far tighter than the caller's
             * budget, which is the ordinary arrangement: the service protects
             * itself at 120ms while the caller is happy to wait three seconds.
             * So a real 504 arrives with most of the budget still unspent, and
             * how the caller reads it is the only thing that decides whether a
             * slow supplier gets dropped from every future search.
             */
            Breaker breaker = new Breaker(2, Duration.ofSeconds(30));
            startWith(List.of(slow("sluggish")), breaker, Duration.ofMillis(120));

            for (int attempt = 0; attempt < 2; attempt++) {
                Json result = search("budget=3000");
                assertEquals("TIMED_OUT",
                        result.get("suppliers").array().getFirst().get("status").text(),
                        "A 504 arrived and was recorded as something other than lateness");
            }

            assertFalse(breaker.isOpen("sluggish"), """
                    A downstream 504 counted as a failure and opened the breaker. The supplier \
                    is merely slower than its own ceiling, and it has now been dropped.""");
        }

        @Test
        void a_real_failure_does() throws Exception {
            Breaker breaker = new Breaker(2, Duration.ofSeconds(30));
            startWith(List.of(broken("brokenly")), breaker);

            search("budget=2000");
            search("budget=2000");

            assertTrue(breaker.isOpen("brokenly"), """
                    A supplier answering with rubbish twice should stop being called. Otherwise \
                    it costs the deadline on every search from now on.""");
        }

        @Test
        void a_skipped_supplier_still_appears_in_the_answer() throws Exception {
            Breaker breaker = new Breaker(1, Duration.ofSeconds(30));
            startWith(List.of(broken("brokenly")), breaker);

            search("budget=2000");
            Json result = search("budget=2000");

            Json outcome = result.get("suppliers").array().getFirst();
            assertEquals("SKIPPED", outcome.get("status").text(), """
                    A supplier the breaker skipped has to be named. Leaving it out reads as \
                    never configured rather than as deliberately not called.""");
            assertFalse(result.get("complete").bool());
        }
    }

    @Nested
    @DisplayName("The edge refuses what it cannot answer")
    class BadRequests {

        @BeforeEach
        void bothServices() throws Exception {
            startWith(Market.suppliers());
        }

        @Test
        void a_missing_parameter_is_a_400() throws Exception {
            assertEquals(400, raw("/search?origin=DUS").statusCode());
        }

        @Test
        void an_unknown_basket_is_a_400_rather_than_a_default() throws Exception {
            HttpResponse<String> response = raw("/search?" + route() + "&basket=hovercraft");

            assertEquals(400, response.statusCode());
            assertTrue(Json.parse(response.body()).get("error").text().contains("cabin"));
        }

        @Test
        void a_budget_past_the_cap_is_refused_rather_than_quietly_clamped() throws Exception {
            HttpResponse<String> response = raw("/search?" + route() + "&budget=600000");

            assertEquals(400, response.statusCode(), """
                    Silently serving ten seconds to somebody who asked for six hundred leaves \
                    them timing their client against a number that was never true.""");
        }
    }

    @Test
    void the_edge_refuses_to_start_when_the_other_service_is_not_there() {
        // Better than starting anyway. A search service with no suppliers
        // returns empty results, and empty results look exactly like a route
        // nobody flies.
        assertThrows(IOException.class,
                () -> HttpConnector.discover("http://127.0.0.1:1", Duration.ofMillis(500)));
    }

    // ------------------------------------------------------------- harness

    private void startWith(List<Connector> market) throws Exception {
        startWith(market, new Breaker(3, Duration.ofSeconds(30)));
    }

    private void startWith(List<Connector> market, Breaker breaker) throws Exception {
        startWith(market, breaker, IntegrationServer.CEILING);
    }

    /** Two processes' worth of wiring, in one JVM but over real sockets. */
    private void startWith(List<Connector> market, Breaker breaker, Duration ceiling) throws Exception {
        suppliers = new IntegrationServer(0, market, ceiling);
        suppliers.start();

        String base = "http://127.0.0.1:" + suppliers.port();
        List<Connector> remote = HttpConnector.discover(base, Duration.ofSeconds(2));

        assertEquals(market.stream().map(Connector::id).toList(),
                remote.stream().map(Connector::id).toList(),
                "Discovery lost a supplier between the two services");

        Fanout fanout = new Fanout(remote, breaker, new Tracer(SpanSink.NONE));
        edge = new SearchServer(0, fanout, Market::asOf, "distributed");
        edge.start();
    }

    private static String route() {
        return "origin=DUS&destination=STN&date=2026-09-01";
    }

    /** The carrier on the cheapest row, which is the only number a visitor reads. */
    private String winner(String extra) throws Exception {
        return search(extra).get("itineraries").array().getFirst().get("carrier").text();
    }

    private Json search(String extra) throws Exception {
        HttpResponse<String> response = raw("/search?" + route() + "&" + extra);
        assertEquals(200, response.statusCode(), response.body());
        return Json.parse(response.body());
    }

    private HttpResponse<String> raw(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + edge.port() + path)).GET().build();

        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Answers far too late for any budget a test would set. */
    private static Connector slow(String id) {
        return new Connector() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<Fare> search(Query query, Deadline deadline) throws InterruptedException {
                Thread.sleep(Math.max(1, deadline.remainingMillis()));
                throw new InterruptedException(id + " ran past the deadline");
            }
        };
    }

    /** Answers, but with something unusable. */
    private static Connector broken(String id) {
        return new Connector() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<Fare> search(Query query, Deadline deadline) {
                throw new IllegalStateException("upstream returned a login page");
            }
        };
    }
}
