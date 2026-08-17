package io.github.arsalanrc.fanout.integration;

import io.github.arsalanrc.fanout.core.Deadline;
import io.github.arsalanrc.fanout.core.Fare;
import io.github.arsalanrc.fanout.core.Query;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A supplier reached through the integration service, over HTTP.
 *
 * <p>The seam that makes the two-service split cost nothing is
 * {@link Connector}. {@link io.github.arsalanrc.fanout.search} never learns
 * whether a supplier is a file on disk, a live API or another process, and the
 * fan-out is the same code either way. Swapping this in for
 * {@link FixtureConnector} is the entire difference between running the whole
 * thing in one JVM and running it as two services.
 *
 * <p><b>Late and broken stay different across the network.</b> A {@code 504}
 * from the integration service becomes {@link InterruptedException}, which the
 * fan-out records as a timeout and the breaker ignores. Everything else becomes
 * {@link IOException}, which counts. Losing that distinction at the hop is the
 * classic way a circuit breaker turns a slow afternoon into an outage: a busy
 * supplier keeps missing deadlines, every miss reads as a failure, the breaker
 * opens, and a working supplier is dropped from the results.
 *
 * <p><b>The socket timeout comes from the deadline, not from a constant.</b>
 * Any fixed number here would be a second budget competing with the real one,
 * which is the mistake the whole repository is written against.
 *
 * <p>This class lives beside the server it talks to on purpose. The two halves
 * of one protocol drift the moment they are kept in different modules, and the
 * drift shows up as a field that one side stopped sending and the other never
 * noticed was missing.
 */
public final class HttpConnector implements Connector {

    private final String id;
    private final String base;
    private final HttpClient http;

    public HttpConnector(String id, String base) {
        this(id, base, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    public HttpConnector(String id, String base, HttpClient http) {
        this.id = Objects.requireNonNull(id, "id");
        this.base = trimTrailingSlash(Objects.requireNonNull(base, "base"));
        this.http = Objects.requireNonNull(http, "http");
    }

    /**
     * Every supplier the integration service is carrying.
     *
     * <p>Asked once at startup rather than on every search. The list changes
     * when somebody deploys, not while a traveller is waiting, and spending part
     * of a search budget rediscovering it would be paying a per-request cost for
     * a per-deployment fact.
     *
     * @throws IOException when the service cannot be reached, and deliberately
     *         so. A search service that started anyway would serve empty results
     *         that look exactly like a route nobody flies.
     */
    public static List<Connector> discover(String base, Duration budget)
            throws IOException, InterruptedException {

        String root = trimTrailingSlash(base);
        HttpClient client = HttpClient.newBuilder().connectTimeout(budget).build();

        HttpRequest request = HttpRequest.newBuilder(URI.create(root + "/suppliers"))
                .timeout(budget)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("The integration service at " + root + " answered "
                    + response.statusCode() + " when asked for its suppliers");
        }

        List<Connector> connectors = new ArrayList<>();
        for (Json id : Json.parse(response.body()).get("suppliers").arrayOrEmpty()) {
            connectors.add(new HttpConnector(id.text(), root));
        }

        if (connectors.isEmpty()) {
            throw new IOException("The integration service at " + root + " is carrying no suppliers");
        }

        return List.copyOf(connectors);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<Fare> search(Query query, Deadline deadline) throws Exception {
        long left = deadline.remainingMillis();

        // Nothing left, so nothing is sent. Opening a socket for an answer that
        // arrives after everybody stopped waiting spends the supplier's capacity
        // as well as ours.
        if (left == 0) {
            throw new InterruptedException(id + " was not called: the deadline was already gone");
        }

        HttpRequest request = HttpRequest.newBuilder(uri(query))
                .header(Wire.DEADLINE_HEADER, Long.toString(left))
                .timeout(Duration.ofMillis(left))
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            // The socket gave up before the service answered. Late, not broken,
            // so it maps to the same exception a slow in-process connector uses.
            throw new InterruptedException(id + " ran past the deadline: " + e.getMessage());
        }

        if (response.statusCode() == 504) {
            throw new InterruptedException(id + " did not answer inside the deadline");
        }
        if (response.statusCode() != 200) {
            throw new IOException(id + " answered " + response.statusCode() + ": " + detail(response.body()));
        }

        return Wire.fares(response.body());
    }

    private URI uri(Query query) {
        return URI.create(base + "/suppliers/" + encode(id) + "/fares"
                + "?origin=" + encode(query.origin())
                + "&destination=" + encode(query.destination())
                + "&date=" + query.departure()
                + "&passengers=" + query.passengers());
    }

    /**
     * The service's own error message, when it sent one.
     *
     * <p>Truncated, because a proxy or a misrouted request can answer with a
     * page of HTML, and the whole of it would end up in a supplier outcome that
     * something eventually renders.
     */
    private static String detail(String body) {
        if (body == null || body.isBlank()) return "no detail";

        try {
            Json error = Json.parse(body).get("error");
            if (!error.isMissing()) return error.text();
        } catch (RuntimeException e) {
            // Not our JSON, so fall through to the raw body.
        }

        return body.substring(0, Math.min(200, body.length()));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
