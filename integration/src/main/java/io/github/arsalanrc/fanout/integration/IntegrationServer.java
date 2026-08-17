package io.github.arsalanrc.fanout.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.arsalanrc.fanout.core.Deadline;
import io.github.arsalanrc.fanout.core.Fare;
import io.github.arsalanrc.fanout.core.Query;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The integration service: one HTTP endpoint per supplier.
 *
 * <p>This is one of the two processes the repository is about. Everything
 * supplier-shaped lives behind it, so the search service never learns that
 * {@code fineair} quotes decimals and {@code voyago} quotes epoch milliseconds.
 * What leaves here is always the internal model, written by {@link Wire}.
 *
 * <p><b>The deadline arrives in a header and is honoured.</b> That is the point
 * of putting a network between the two. A budget that stops at a process
 * boundary is not a budget, and the usual way this goes wrong is that the
 * downstream service starts a fresh timeout of its own. Then the caller's two
 * seconds becomes two plus five, and every configuration file involved looks
 * right.
 *
 * <p><b>A caller can shorten the budget and can never extend it.</b> The
 * service starts from its own ceiling and applies {@link Deadline#within} with
 * whatever the caller asked for, so the sooner of the two wins. A client asking
 * for ten minutes gets the ceiling. This is not defensive decoration: a service
 * that trusts a client's deadline has handed a stranger the right to hold its
 * threads open, and one buggy caller then costs everybody.
 *
 * <p><b>Late and broken are answered with different statuses.</b> A supplier
 * that ran out of budget produces {@code 504}, and a supplier that failed
 * produces {@code 502}. That distinction has to survive the hop, because the
 * circuit breaker on the far side counts one and ignores the other. Collapsing
 * them into "error" would trip breakers on a slow afternoon and drop suppliers
 * that were working.
 *
 * <h2>Endpoints</h2>
 *
 * <pre>
 *   GET /health
 *   GET /suppliers
 *   GET /suppliers/{id}/fares?origin=DUS&amp;destination=STN&amp;date=2026-09-01&amp;passengers=1
 * </pre>
 */
public final class IntegrationServer implements AutoCloseable {

    /**
     * The longest this service will ever hold a request, whatever a caller asks.
     *
     * <p>Five seconds is longer than the slowest fixture and shorter than any
     * client's patience. Its job is to bound the damage a caller can do, not to
     * be the normal case: in a healthy search the caller's own budget is far
     * tighter and wins.
     */
    public static final Duration CEILING = Duration.ofSeconds(5);

    private final HttpServer server;
    private final Map<String, Connector> suppliers = new LinkedHashMap<>();
    private final Duration ceiling;

    public IntegrationServer(int port, List<Connector> suppliers) throws IOException {
        this(port, suppliers, CEILING);
    }

    public IntegrationServer(int port, List<Connector> suppliers, Duration ceiling) throws IOException {
        for (Connector connector : suppliers) this.suppliers.put(connector.id(), connector);
        this.ceiling = ceiling;

        this.server = HttpServer.create(new InetSocketAddress(port), 0);

        /*
         * A virtual thread per request, for the same reason the fan-out uses
         * them: every one of these spends its life waiting on a supplier rather
         * than computing. A fixed pool here would put the ninth caller in a
         * queue behind eight sockets doing nothing, and the queueing would show
         * up as latency nobody can find in a profiler.
         */
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/health", this::health);
        this.server.createContext("/suppliers", this::route);
    }

    public void start() {
        server.start();
    }

    /** The port actually bound, which matters when the port asked for was 0. */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------- routing

    private void health(HttpExchange exchange) throws IOException {
        respond(exchange, 200, new JsonWriter().object()
                .field("status", "ok")
                .field("suppliers", suppliers.size())
                .end().done(), null);
    }

    private void route(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            error(exchange, 405, "Only GET is served here");
            return;
        }

        String path = exchange.getRequestURI().getPath();

        if (path.equals("/suppliers") || path.equals("/suppliers/")) {
            list(exchange);
            return;
        }

        // /suppliers/{id}/fares
        String[] parts = path.split("/");
        if (parts.length != 4 || !parts[3].equals("fares")) {
            error(exchange, 404, "No route for " + path
                    + ". Try /suppliers or /suppliers/{id}/fares");
            return;
        }

        fares(exchange, parts[2]);
    }

    private void list(HttpExchange exchange) throws IOException {
        JsonWriter out = new JsonWriter().object().array("suppliers");
        for (String id : suppliers.keySet()) out.value(id);

        respond(exchange, 200, out.end().end().done(), null);
    }

    // ------------------------------------------------------------- searching

    private void fares(HttpExchange exchange, String id) throws IOException {
        Connector connector = suppliers.get(id);
        if (connector == null) {
            error(exchange, 404, "No supplier called " + id + ". Ask /suppliers for the list.");
            return;
        }

        Query query;
        Deadline deadline;
        try {
            Map<String, String> params = parameters(exchange.getRequestURI().getRawQuery());
            query = query(params);
            deadline = deadline(exchange);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            error(exchange, 400, e.getMessage());
            return;
        }

        long granted = deadline.remainingMillis();

        /*
         * Refused before the connector is touched. A request that arrives with
         * nothing left is not a failure of the supplier, and calling it anyway
         * would spend a socket on an answer nobody is still waiting for.
         */
        if (deadline.expired()) {
            error(exchange, 504, "The deadline was already spent when this request arrived");
            return;
        }

        try {
            List<Fare> fares = connector.search(query, deadline);
            respond(exchange, 200, Wire.fares(id, fares), granted);
        } catch (InterruptedException e) {
            /*
             * Answer first, restore the flag afterwards, and the order is not
             * cosmetic. The response body goes out over an interruptible
             * channel, so writing it with the interrupt flag already set throws
             * ClosedByInterruptException and hangs up on the caller instead.
             *
             * The caller then sees a dropped connection rather than a 504, and
             * a dropped connection is indistinguishable from a broken service.
             * Its breaker counts that as a failure, and the one distinction
             * this endpoint exists to preserve is lost at the last line.
             */
            // 504 rather than 502, and the difference is load bearing: the
            // caller's breaker counts failures and must not count lateness.
            error(exchange, 504, id + " did not answer inside the deadline");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            error(exchange, 502, id + " failed: " + describe(e));
        }
    }

    /**
     * The budget for this request: the service ceiling, shortened by the caller.
     *
     * <p>A missing header is legal and means "as long as you like", which this
     * service reads as its own ceiling rather than as no limit at all. That
     * keeps {@code curl} usable by hand without leaving a hole a client could
     * drive a ten-minute request through.
     */
    private Deadline deadline(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst(Wire.DEADLINE_HEADER);
        Deadline budget = Deadline.in(ceiling);

        if (header == null || header.isBlank()) return budget;

        long millis;
        try {
            millis = Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    Wire.DEADLINE_HEADER + " must be a whole number of milliseconds, got: " + header);
        }
        if (millis < 0) {
            throw new IllegalArgumentException(Wire.DEADLINE_HEADER + " cannot be negative, got: " + millis);
        }

        return budget.within(Duration.ofMillis(millis));
    }

    private static Query query(Map<String, String> params) {
        return new Query(
                required(params, "origin"),
                required(params, "destination"),
                LocalDate.parse(required(params, "date")),
                Integer.parseInt(params.getOrDefault("passengers", "1")));
    }

    private static String required(Map<String, String> params, String name) {
        String value = params.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing query parameter: " + name);
        }
        return value;
    }

    /** Splits a raw query string, decoding each half separately. */
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
        respond(exchange, status, new JsonWriter().object().field("error", message).end().done(), null);
    }

    private static void respond(HttpExchange exchange, int status, String body, Long granted)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (granted != null) exchange.getResponseHeaders().set(Wire.GRANTED_HEADER, granted.toString());

        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message;
    }

    // ------------------------------------------------------------- running

    /**
     * Runs the service on the modelled market.
     *
     * <pre>
     *   mvn -q -pl integration exec:java \
     *     -Dexec.mainClass=io.github.arsalanrc.fanout.integration.IntegrationServer
     * </pre>
     */
    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0])
                : Integer.parseInt(System.getenv().getOrDefault("FANOUT_INTEGRATION_PORT", "8081"));

        IntegrationServer service = new IntegrationServer(port, Market.suppliers());
        service.start();

        System.out.println("integration listening on http://127.0.0.1:" + service.port());
        System.out.println("suppliers: " + String.join(", ", service.suppliers.keySet()));
        System.out.println("fares are modelled, not recorded. See fixtures/README.md");
    }
}
