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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The live Amadeus connector.
 *
 * <p>The same {@link AmadeusParser} the fixture connector uses, pointed at the
 * network instead of a file. That is the entire difference, and it is what
 * makes the published demo trustworthy: the parsing, the currency handling and
 * the timezone resolution are one code path whether the bytes came from Amadeus
 * or from disk.
 *
 * <p><b>Credentials come from the environment and nowhere else.</b> Nothing here
 * reads a key from a file this repository controls, and no key is ever written
 * into a fixture. Same rule as the plinth deploy scripts.
 *
 * <pre>
 *   export AMADEUS_CLIENT_ID=...
 *   export AMADEUS_CLIENT_SECRET=...
 * </pre>
 *
 * <p>The token is fetched once and reused until it is nearly expired. Amadeus
 * issues one valid for about half an hour, and asking for a fresh token on every
 * search would spend a large part of the deadline on authentication.
 */
public final class AmadeusClient implements Connector {

    /** The test environment. Production is `api.amadeus.com`, and it is not free. */
    private static final String HOST = "https://test.api.amadeus.com";

    private final AmadeusParser parser;
    private final String clientId;
    private final String clientSecret;
    private final HttpClient http;
    private final String host;

    private volatile String token;
    private volatile Instant tokenExpires = Instant.EPOCH;

    public AmadeusClient(String supplier, String clientId, String clientSecret) {
        this(supplier, clientId, clientSecret, HOST);
    }

    /** Package private so the tests can point it at a local server. */
    AmadeusClient(String supplier, String clientId, String clientSecret, String host) {
        this.parser = new AmadeusParser(supplier);
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
        this.host = Objects.requireNonNull(host, "host");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * Built from the environment, or empty when no credentials are set.
     *
     * <p>Returns empty rather than throwing, because running without a key is
     * the normal case: the published demo has none, and every test runs without
     * one. A constructor that threw would make "no key" an error condition
     * instead of a supported mode.
     */
    public static java.util.Optional<AmadeusClient> fromEnvironment(String supplier) {
        String id = System.getenv("AMADEUS_CLIENT_ID");
        String secret = System.getenv("AMADEUS_CLIENT_SECRET");

        if (id == null || id.isBlank() || secret == null || secret.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new AmadeusClient(supplier, id, secret));
    }

    @Override
    public String id() {
        return parser.supplier();
    }

    @Override
    public List<Fare> search(Query query, Deadline deadline) throws Exception {
        return parser.parse(fetch(query, deadline), query);
    }

    /** The raw response, which is exactly what the recorder writes to a fixture. */
    public String fetch(Query query, Deadline deadline) throws IOException, InterruptedException {
        String url = host + "/v2/shopping/flight-offers"
                + "?originLocationCode=" + encode(query.origin())
                + "&destinationLocationCode=" + encode(query.destination())
                + "&departureDate=" + query.departure()
                + "&adults=" + query.passengers()
                + "&currencyCode=EUR"
                + "&max=20";

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken(deadline))
                .timeout(Duration.ofMillis(Math.max(1, deadline.remainingMillis())))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            // The body carries Amadeus's own error detail, which is worth far
            // more than the status code alone. It is truncated because a
            // failing endpoint can return a great deal of HTML.
            throw new IOException("Amadeus answered " + response.statusCode() + ": "
                    + response.body().substring(0, Math.min(300, response.body().length())));
        }

        return response.body();
    }

    /**
     * A bearer token, cached until shortly before it lapses.
     *
     * <p>The refresh happens a minute early on purpose. A token that expires
     * between this check and the request landing produces a 401 that looks like
     * bad credentials, and that is a confusing thing to debug at the point
     * somebody is trying the project for the first time.
     */
    private String accessToken(Deadline deadline) throws IOException, InterruptedException {
        if (token != null && Instant.now().isBefore(tokenExpires)) return token;

        String form = "grant_type=client_credentials"
                + "&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret);

        HttpRequest request = HttpRequest.newBuilder(URI.create(host + "/v1/security/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofMillis(Math.max(1, deadline.remainingMillis())))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            // Deliberately does not echo the body. An auth failure response can
            // repeat parts of what was sent, and this is the one place in the
            // codebase where that would put a secret in a log.
            throw new IOException("Amadeus refused the credentials with " + response.statusCode()
                    + ". Check AMADEUS_CLIENT_ID and AMADEUS_CLIENT_SECRET.");
        }

        Json body = Json.parse(response.body());
        token = body.get("access_token").text();
        tokenExpires = Instant.now().plusSeconds(Math.max(60, body.get("expires_in").minorUnits(0)) - 60);

        return token;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
