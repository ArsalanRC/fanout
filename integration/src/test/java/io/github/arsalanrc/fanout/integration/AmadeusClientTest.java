package io.github.arsalanrc.fanout.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.arsalanrc.fanout.core.Deadline;
import io.github.arsalanrc.fanout.core.Fare;
import io.github.arsalanrc.fanout.core.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The live client, against a real HTTP server.
 *
 * <p>The server is {@code com.sun.net.httpserver}, which ships with the JDK, so
 * testing the network path costs no dependency. That matters more than it
 * sounds: a client tested only against a mocked interface never proves it built
 * a valid request, and the request is most of what a client is.
 */
class AmadeusClientTest {

    private HttpServer server;
    private final List<String> paths = new ArrayList<>();
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private int tokenStatus = 200;
    private String searchBody = "{\"data\":[]}";
    private int searchStatus = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/v1/security/oauth2/token", exchange -> {
            tokenRequests.incrementAndGet();
            paths.add(read(exchange));
            respond(exchange, tokenStatus, tokenStatus == 200
                    ? "{\"access_token\":\"tok-123\",\"expires_in\":1799}"
                    : "{\"error\":\"invalid_client\",\"client_secret\":\"hunter2-echoed-back\"}");
        });

        server.createContext("/v2/shopping/flight-offers", exchange -> {
            paths.add(exchange.getRequestURI().getQuery()
                    + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, searchStatus, searchBody);
        });

        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AmadeusClient client() {
        return new AmadeusClient("amadeus", "id-abc", "secret-hunter2",
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static String read(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final Query QUERY = new Query("DUS", "STN", LocalDate.of(2026, 9, 1), 2);

    @Test
    void builds_the_request_the_api_actually_expects() throws Exception {
        client().fetch(QUERY, Deadline.in(Duration.ofSeconds(10)));

        String search = paths.getLast();
        assertTrue(search.contains("originLocationCode=DUS"), search);
        assertTrue(search.contains("destinationLocationCode=STN"), search);
        assertTrue(search.contains("departureDate=2026-09-01"), search);
        assertTrue(search.contains("adults=2"), search);
        // The token from the auth call is actually carried, rather than the
        // request being sent unauthenticated and failing later.
        assertTrue(search.contains("auth=Bearer tok-123"), search);
    }

    @Test
    @DisplayName("the token is fetched once and reused, not on every search")
    void the_token_is_cached() throws Exception {
        AmadeusClient client = client();

        client.fetch(QUERY, Deadline.in(Duration.ofSeconds(10)));
        client.fetch(QUERY, Deadline.in(Duration.ofSeconds(10)));
        client.fetch(QUERY, Deadline.in(Duration.ofSeconds(10)));

        // Three searches, one authentication. Re-authenticating each time spends
        // a large slice of the deadline on something that has not changed.
        assertEquals(1, tokenRequests.get());
    }

    @Test
    @DisplayName("an auth failure never echoes the credentials back into the message")
    void the_secret_never_reaches_the_error_message() {
        tokenStatus = 401;

        IOException thrown = assertThrows(IOException.class,
                () -> client().fetch(QUERY, Deadline.in(Duration.ofSeconds(10))));

        // The server deliberately echoes the secret in its error body, which is
        // a thing real auth endpoints do. This is the one place in the codebase
        // where repeating a response body would put a credential in a log.
        assertFalse(thrown.getMessage().contains("hunter2"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("AMADEUS_CLIENT_ID"), thrown.getMessage());
    }

    @Test
    void a_search_failure_does_carry_the_detail_because_it_holds_no_secret() {
        searchStatus = 400;
        searchBody = "{\"errors\":[{\"detail\":\"Date/Time is in the past\"}]}";

        IOException thrown = assertThrows(IOException.class,
                () -> client().fetch(QUERY, Deadline.in(Duration.ofSeconds(10))));

        // Unlike the auth response, this body is Amadeus explaining what was
        // wrong with the query, and it is worth far more than the status code.
        assertTrue(thrown.getMessage().contains("400"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Date/Time is in the past"), thrown.getMessage());
    }

    @Test
    void the_live_client_and_the_fixture_produce_the_same_fares() throws Exception {
        searchBody = new String(getClass().getResourceAsStream("/fixtures/altair-dus-stn.json")
                .readAllBytes(), StandardCharsets.UTF_8);

        List<Fare> live = client().search(QUERY, Deadline.in(Duration.ofSeconds(10)));
        List<Fare> fromDisk = new FixtureConnector(new AmadeusParser("amadeus"),
                "/fixtures/altair-dus-stn.json").search(QUERY, Deadline.in(Duration.ofSeconds(10)));

        // The whole argument for the demo: one parser, one code path, and the
        // only difference is where the bytes came from.
        assertEquals(fromDisk.size(), live.size());
        assertEquals(fromDisk.getFirst().base(), live.getFirst().base());
        assertEquals(fromDisk.getFirst().itinerary().key(), live.getFirst().itinerary().key());
    }

    @Test
    void no_credentials_is_a_supported_mode_rather_than_an_error() {
        // The published demo runs without a key and every test runs without one,
        // so this returns empty rather than throwing.
        assertTrue(AmadeusClient.fromEnvironment("amadeus").isEmpty()
                || System.getenv("AMADEUS_CLIENT_ID") != null);
    }
}
