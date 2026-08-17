package io.github.arsalanrc.fanout.integration;

import io.github.arsalanrc.fanout.core.Deadline;
import io.github.arsalanrc.fanout.core.Fare;
import io.github.arsalanrc.fanout.core.Query;
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
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The integration service, over a real socket.
 *
 * <p>Nothing here is mocked, because the thing under test is the protocol. A
 * service tested through its own Java methods proves that the handler works and
 * says nothing about whether the deadline header is read, whether a late
 * supplier answers 504 rather than 502, or whether the response is valid JSON.
 * Those are the parts the other service depends on.
 */
class IntegrationServerTest {

    private static final Query QUERY = new Query("DUS", "STN", LocalDate.parse("2026-09-01"), 1);

    private IntegrationServer service;
    private HttpClient http;

    /** What the connector was actually handed, captured on the way past. */
    private final AtomicReference<Long> budgetSeen = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        http = HttpClient.newHttpClient();
        service = new IntegrationServer(0, List.of(
                new FixtureConnector(new LowCostParser("fineair"), "/fixtures/fineair-dus-stn.json"),
                watching("watcher"),
                slow("sluggish", Duration.ofSeconds(30)),
                broken("brokenly")));
        service.start();
    }

    @AfterEach
    void stop() {
        service.close();
    }

    @Nested
    @DisplayName("The catalogue")
    class Catalogue {

        @Test
        void health_says_how_many_suppliers_are_behind_it() throws Exception {
            HttpResponse<String> response = get("/health");

            assertEquals(200, response.statusCode());
            assertEquals(4, Json.parse(response.body()).get("suppliers").minorUnits(0));
        }

        @Test
        void lists_its_suppliers_so_the_other_service_need_not_be_told() throws Exception {
            HttpResponse<String> response = get("/suppliers");

            List<String> ids = Json.parse(response.body()).get("suppliers").array()
                    .stream().map(Json::text).toList();

            assertEquals(List.of("fineair", "watcher", "sluggish", "brokenly"), ids);
        }

        @Test
        void an_unknown_supplier_is_a_404_that_says_where_to_look() throws Exception {
            HttpResponse<String> response = get("/suppliers/ryanair/fares?" + params());

            assertEquals(404, response.statusCode());
            assertTrue(Json.parse(response.body()).get("error").text().contains("/suppliers"));
        }

        @Test
        void a_missing_parameter_is_a_400_rather_than_a_guess() throws Exception {
            HttpResponse<String> response = get("/suppliers/fineair/fares?origin=DUS");

            assertEquals(400, response.statusCode());
            assertTrue(Json.parse(response.body()).get("error").text().contains("destination"));
        }
    }

    @Nested
    @DisplayName("The deadline crosses the boundary")
    class Budget {

        @Test
        void the_caller_can_shorten_it() throws Exception {
            HttpResponse<String> response = get("/suppliers/watcher/fares?" + params(), 250);

            assertEquals(200, response.statusCode());
            assertTrue(budgetSeen.get() <= 250,
                    "The connector was given " + budgetSeen.get() + "ms of a 250ms request");
        }

        @Test
        @DisplayName("and can never lengthen it past the service ceiling")
        void the_caller_can_never_lengthen_it() throws Exception {
            // A client asking for ten minutes is either confused or hostile, and
            // either way it must not get to hold this service's threads open
            // for ten minutes.
            HttpResponse<String> response = get("/suppliers/watcher/fares?" + params(), 600_000);

            assertEquals(200, response.statusCode());
            assertTrue(budgetSeen.get() <= IntegrationServer.CEILING.toMillis(),
                    "A caller extended the budget to " + budgetSeen.get() + "ms");
        }

        @Test
        void no_header_at_all_means_the_ceiling_and_not_forever() throws Exception {
            HttpResponse<String> response = get("/suppliers/watcher/fares?" + params());

            assertEquals(200, response.statusCode());
            assertTrue(budgetSeen.get() <= IntegrationServer.CEILING.toMillis(),
                    "A request with no deadline got " + budgetSeen.get() + "ms");
            assertNotNull(budgetSeen.get());
        }

        @Test
        void the_answer_says_what_was_granted() throws Exception {
            HttpResponse<String> response = get("/suppliers/fineair/fares?" + params(), 250);

            long granted = Long.parseLong(response.headers()
                    .firstValue(Wire.GRANTED_HEADER).orElseThrow());

            assertTrue(granted <= 250, """
                    A caller asking for 250ms and silently receiving something else has no way \
                    to tell. The ceiling belongs in the response, not only in the source.""");
        }

        @Test
        void nothing_left_is_refused_before_the_supplier_is_touched() throws Exception {
            HttpResponse<String> response = get("/suppliers/watcher/fares?" + params(), 0);

            assertEquals(504, response.statusCode());
            assertNull(budgetSeen.get(), """
                    A supplier was called for an answer nobody was still waiting for. That \
                    spends its capacity as well as ours.""");
        }

        @Test
        void a_nonsense_header_is_a_400_rather_than_an_assumption() throws Exception {
            HttpResponse<String> response = get("/suppliers/fineair/fares?" + params(), "soon");

            assertEquals(400, response.statusCode());
            assertTrue(Json.parse(response.body()).get("error").text().contains(Wire.DEADLINE_HEADER));
        }
    }

    @Nested
    @DisplayName("Late and broken are answered differently")
    class LateVersusBroken {

        @Test
        void a_supplier_that_runs_past_the_deadline_is_a_504() throws Exception {
            HttpResponse<String> response = get("/suppliers/sluggish/fares?" + params(), 150);

            assertEquals(504, response.statusCode(), """
                    Lateness has to arrive as 504. The breaker on the far side counts failures \
                    and ignores timeouts, so collapsing the two would open a breaker on a busy \
                    afternoon and drop a supplier that works.""");
        }

        @Test
        void a_supplier_that_actually_failed_is_a_502() throws Exception {
            HttpResponse<String> response = get("/suppliers/brokenly/fares?" + params(), 1000);

            assertEquals(502, response.statusCode());
            assertTrue(Json.parse(response.body()).get("error").text().contains("brokenly"));
        }
    }

    @Test
    void a_real_supplier_answers_with_fares_the_other_service_can_read() throws Exception {
        HttpResponse<String> response = get("/suppliers/fineair/fares?" + params(), 1000);

        assertEquals(200, response.statusCode());
        assertEquals("application/json; charset=utf-8",
                response.headers().firstValue("Content-Type").orElseThrow());

        List<Fare> fares = Wire.fares(response.body());
        assertEquals(2, fares.size());
        assertEquals("fineair", fares.getFirst().supplier());
    }

    // ------------------------------------------------------------- helpers

    private static String params() {
        return "origin=DUS&destination=STN&date=2026-09-01&passengers=1";
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(request(path).build());
    }

    private HttpResponse<String> get(String path, long deadlineMs) throws Exception {
        return get(path, Long.toString(deadlineMs));
    }

    private HttpResponse<String> get(String path, String deadline) throws Exception {
        return send(request(path).header(Wire.DEADLINE_HEADER, deadline).build());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port() + path)).GET();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Records the budget it was handed, then answers instantly. */
    private Connector watching(String id) {
        return new Connector() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<Fare> search(Query query, Deadline deadline) {
                budgetSeen.set(deadline.remainingMillis());
                return List.of();
            }
        };
    }

    /** Sleeps past any sensible budget, the way a supplier having a bad day does. */
    private static Connector slow(String id, Duration nap) {
        return new Connector() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<Fare> search(Query query, Deadline deadline) throws InterruptedException {
                Thread.sleep(Math.min(nap.toMillis(), deadline.remainingMillis()));
                if (deadline.expired()) throw new InterruptedException(id + " ran past the deadline");
                return List.of();
            }
        };
    }

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
