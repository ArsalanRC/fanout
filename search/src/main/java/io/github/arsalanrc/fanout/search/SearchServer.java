package io.github.arsalanrc.fanout.search;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.arsalanrc.fanout.core.Ancillary;
import io.github.arsalanrc.fanout.core.Basket;
import io.github.arsalanrc.fanout.core.Deadline;
import io.github.arsalanrc.fanout.core.Fare;
import io.github.arsalanrc.fanout.core.Leg;
import io.github.arsalanrc.fanout.core.PricedItinerary;
import io.github.arsalanrc.fanout.core.Query;
import io.github.arsalanrc.fanout.core.SearchResult;
import io.github.arsalanrc.fanout.core.SupplierOutcome;
import io.github.arsalanrc.fanout.integration.Connector;
import io.github.arsalanrc.fanout.integration.HttpConnector;
import io.github.arsalanrc.fanout.integration.IntegrationServer;
import io.github.arsalanrc.fanout.integration.JsonWriter;
import io.github.arsalanrc.fanout.integration.Market;
import io.github.arsalanrc.fanout.integration.Wire;
import io.github.arsalanrc.fanout.telemetry.OtlpJson;
import io.github.arsalanrc.fanout.telemetry.SpanSink;
import io.github.arsalanrc.fanout.telemetry.Tracer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The search service: the edge of the system, and the only part a traveller
 * would ever see.
 *
 * <p>It owns one decision that nothing downstream is allowed to make, which is
 * how long the whole search may take. The budget starts here, at the edge,
 * because this is the only place that knows somebody is waiting. Everything
 * below gets a share of it and nobody gets a fresh one.
 *
 * <p><b>The response says what it is missing.</b> Six suppliers out of eight is
 * a real answer and worth returning, and presenting it as the whole market is a
 * lie by omission: the cheapest fare shown may not be the cheapest available.
 * So {@code complete} is a field, and every supplier appears in the response
 * whether it answered, was late, failed or was never called.
 *
 * <p><b>Every timing is measured.</b> {@code took_ms} on a supplier is what
 * that supplier actually took on this request. Nothing here is a nominal
 * number, because a page built on invented latencies is a drawing of a system
 * rather than a system.
 *
 * <h2>Endpoints</h2>
 *
 * <pre>
 *   GET /health
 *   GET /search?origin=DUS&amp;destination=STN&amp;date=2026-09-01
 *              &amp;passengers=1&amp;basket=cabin&amp;budget=1500
 * </pre>
 *
 * <p>{@code basket} is {@code cabin}, {@code checked} or {@code seat}, and it
 * changes the ranking rather than filtering it. That is the point of asking:
 * the cheapest fare for hand luggage is often not the cheapest fare with a
 * suitcase, and a metasearch that never asks is answering a question nobody
 * put to it.
 */
public final class SearchServer implements AutoCloseable {

    /** What a search costs when the caller does not say. */
    public static final Duration DEFAULT_BUDGET = Duration.ofMillis(2000);

    /**
     * The most a caller may ask for.
     *
     * <p>Same reasoning as the ceiling on the integration service, one layer
     * out: a budget is a promise about how long threads stay held, and a
     * promise a stranger can rewrite is not one.
     */
    public static final Duration MAX_BUDGET = Duration.ofSeconds(10);

    private final HttpServer server;
    private final Fanout fanout;
    private final Supplier<Instant> clock;
    private final String mode;

    public SearchServer(int port, List<Connector> connectors, Supplier<Instant> clock, String mode)
            throws IOException {
        this(port, new Fanout(connectors, new Breaker(3, Duration.ofSeconds(30)),
                new Tracer(SpanSink.NONE)), clock, mode);
    }

    public SearchServer(int port, Fanout fanout, Supplier<Instant> clock, String mode) throws IOException {
        this.fanout = Objects.requireNonNull(fanout, "fanout");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mode = Objects.requireNonNull(mode, "mode");

        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/health", exchange -> respond(exchange, 200,
                new JsonWriter().object().field("status", "ok").field("mode", mode).end().done()));
        this.server.createContext("/search", this::search);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------- searching

    private void search(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            error(exchange, 405, "Only GET is served here");
            return;
        }

        Query query;
        Basket basket;
        Duration budget;
        try {
            Map<String, String> params = parameters(exchange.getRequestURI().getRawQuery());
            query = query(params);
            basket = basket(params.getOrDefault("basket", "cabin"));
            budget = budget(params.get("budget"));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            error(exchange, 400, e.getMessage());
            return;
        }

        Instant now = clock.get();
        long started = System.nanoTime();
        SearchResult result = fanout.search(query, basket, Deadline.in(budget), now);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        respond(exchange, 200, document(result, basket, budget, took, now));
    }

    private String document(SearchResult result, Basket basket, Duration budget,
                            Duration took, Instant now) {
        JsonWriter out = new JsonWriter().object();

        out.object("query")
                .field("origin", result.query().origin())
                .field("destination", result.query().destination())
                .field("date", result.query().departure().toString())
                .field("passengers", result.query().passengers())
                .end();

        out.field("basket", basket.toString());
        out.field("budget_ms", budget.toMillis());
        out.field("took_ms", took.toMillis());
        out.field("mode", mode);

        // The instant freshness was judged against, stated rather than implied.
        // A reader who cannot see it has no way to tell a live search from one
        // replaying a modelled market.
        out.field("as_of", now.toString());
        out.field("complete", result.complete());

        // What arrived and was not shown. A supplier can answer, be recorded as
        // having answered, and put nothing on the page, and this is the only
        // number that admits it.
        out.object("dropped")
                .field("lapsed", result.dropped().lapsed())
                .field("unpriceable", result.dropped().unpriceable())
                .end();

        out.array("itineraries");
        for (PricedItinerary priced : result.itineraries()) itinerary(out, priced);
        out.end();

        out.array("suppliers");
        for (SupplierOutcome outcome : result.outcomes()) supplier(out, outcome);
        out.end();

        return out.end().done();
    }

    private void itinerary(JsonWriter out, PricedItinerary priced) {
        out.object();
        out.field("key", priced.itinerary().key());
        out.field("origin", priced.itinerary().origin());
        out.field("destination", priced.itinerary().destination());
        out.field("duration_min", priced.itinerary().total().toMinutes());
        out.field("stops", priced.itinerary().stops());
        out.field("sellers", priced.sellers());
        out.field("carrier", Market.carrier(priced.itinerary().legs().getFirst().carrier()));

        Wire.money(out, "best", priced.bestPrice());

        // What shopping around is worth on this row. Two euros means the market
        // agrees and the choice does not matter; ninety means it does.
        Wire.money(out, "spread", priced.spread());

        out.array("legs");
        for (Leg leg : priced.itinerary().legs()) {
            out.object()
                    .field("carrier", leg.carrier())
                    .field("number", leg.number())
                    .field("origin", leg.origin())
                    .field("destination", leg.destination())
                    .field("departure", leg.departure().toString())
                    .field("arrival", leg.arrival().toString())
                    .field("aircraft", leg.aircraft())
                    .end();
        }
        out.end();

        out.array("offers");
        for (Fare fare : priced.offers()) {
            out.object();
            out.field("supplier", fare.supplier());
            Wire.money(out, "total", fare.totalFor(priced.basket()));

            // The headline beside the real total, because the gap between them
            // is the argument. A fare cheapest on `base` and dearest on `total`
            // is exactly what a naive metasearch puts first.
            Wire.money(out, "base", fare.base());

            out.array("extras");
            for (Ancillary extra : fare.ancillaries()) {
                out.object()
                        .field("kind", extra.kind().name())
                        .field("included", extra.included())
                        .field("minor", extra.price().minorUnits())
                        .end();
            }
            out.end();

            out.end();
        }
        out.end();

        out.end();
    }

    private static void supplier(JsonWriter out, SupplierOutcome outcome) {
        out.object()
                .field("supplier", outcome.supplier())
                .field("status", outcome.status().name())
                .field("took_ms", outcome.took().toMillis())
                .field("fares", outcome.fares().size())
                .field("detail", outcome.detail())
                .end();
    }

    // ------------------------------------------------------------- parameters

    private static Query query(Map<String, String> params) {
        return new Query(
                required(params, "origin"),
                required(params, "destination"),
                LocalDate.parse(required(params, "date")),
                Integer.parseInt(params.getOrDefault("passengers", "1")));
    }

    private static Basket basket(String name) {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "cabin" -> Basket.HAND_LUGGAGE_ONLY;
            case "checked" -> Basket.WITH_CHECKED_BAG;
            case "seat" -> Basket.SEAT_ONLY;
            default -> throw new IllegalArgumentException(
                    "basket must be cabin, checked or seat, got: " + name);
        };
    }

    /**
     * The budget for this search, capped.
     *
     * <p>Rejected rather than clamped when it is too large. Quietly serving a
     * ten second search to somebody who asked for sixty leaves them timing their
     * own client against a number that was never true.
     */
    private static Duration budget(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_BUDGET;

        long millis;
        try {
            millis = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("budget must be a whole number of milliseconds, got: " + raw);
        }

        if (millis <= 0) throw new IllegalArgumentException("budget must be positive, got: " + millis);
        if (millis > MAX_BUDGET.toMillis()) {
            throw new IllegalArgumentException(
                    "budget is capped at " + MAX_BUDGET.toMillis() + "ms, got: " + millis);
        }

        return Duration.ofMillis(millis);
    }

    private static String required(Map<String, String> params, String name) {
        String value = params.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing query parameter: " + name);
        }
        return value;
    }

    static Map<String, String> parameters(String raw) {
        Map<String, String> params = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return params;

        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                params.put(decode(pair), "");
                continue;
            }
            params.put(decode(pair.substring(0, equals)), decode(pair.substring(equals + 1)));
        }

        return params;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------- responding

    private static void error(HttpExchange exchange, int status, String message) throws IOException {
        respond(exchange, status, new JsonWriter().object().field("error", message).end().done());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ------------------------------------------------------------- running

    /**
     * Runs the edge service.
     *
     * <p>With {@code FANOUT_INTEGRATION_URL} set it talks to the other service
     * over HTTP, which is the arrangement the repository is about. Without it,
     * the same connectors run in this process, so the whole thing is one command
     * for somebody who has just cloned it.
     *
     * <pre>
     *   mvn -q install -DskipTests
     *   FANOUT_INTEGRATION_URL=http://127.0.0.1:8081 mvn -q -pl search exec:java \
     *     -Dexec.mainClass=io.github.arsalanrc.fanout.search.SearchServer
     * </pre>
     *
     * <p>The install is not optional. {@code -pl} resolves the sibling modules
     * from the local repository rather than from the reactor, so without it this
     * starts against whatever was installed last and fails on a class that was
     * added since.
     */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0])
                : Integer.parseInt(System.getenv().getOrDefault("FANOUT_SEARCH_PORT", "8080"));

        String integration = System.getenv("FANOUT_INTEGRATION_URL");
        List<Connector> connectors;
        String mode;

        if (integration == null || integration.isBlank()) {
            connectors = Market.suppliers();
            mode = "in-process";
        } else {
            // Fails here rather than serving empty results, which would be
            // indistinguishable from a route nobody flies.
            connectors = HttpConnector.discover(integration, Duration.ofSeconds(2));
            mode = "distributed";
        }

        /*
         * The modelled market is quoted at a fixed moment, so freshness is
         * judged against that rather than against today. Set FANOUT_CLOCK=system
         * when the suppliers behind this are real, because then the question is
         * a real one.
         */
        boolean live = System.getenv().getOrDefault("FANOUT_CLOCK", "fixture").equals("system");
        Supplier<Instant> clock = live ? Instant::now : Market::asOf;

        SpanSink sink = System.getenv().getOrDefault("FANOUT_OTLP", "").equals("stdout")
                ? OtlpJson.toStdout()
                : SpanSink.NONE;

        Fanout fanout = new Fanout(connectors, new Breaker(3, Duration.ofSeconds(30)), new Tracer(sink));
        SearchServer service = new SearchServer(port, fanout, clock, mode);
        service.start();

        System.out.println("search listening on http://127.0.0.1:" + service.port() + " (" + mode + ")");
        System.out.println("suppliers: " + connectors.stream().map(Connector::id).toList());
        System.out.println("clock: " + (live ? "system" : "fixture, " + Market.asOf()));
        System.out.println();
        System.out.println("  curl 'http://127.0.0.1:" + service.port()
                + "/search?origin=DUS&destination=STN&date=2026-09-01&basket=cabin'");
        System.out.println("  curl 'http://127.0.0.1:" + service.port()
                + "/search?origin=DUS&destination=STN&date=2026-09-01&basket=cabin&budget=300'");
        System.out.println();
        System.out.println("The second one is tighter than the slowest supplier answers, so it comes");
        System.out.println("back incomplete on purpose. That is the behaviour, not a failure.");
        System.out.println("Fares are modelled. Integration ceiling is "
                + IntegrationServer.CEILING.toMillis() + "ms.");
    }
}
