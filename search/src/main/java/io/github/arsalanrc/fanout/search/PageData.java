package io.github.arsalanrc.fanout.search;

import io.github.arsalanrc.fanout.integration.Connector;
import io.github.arsalanrc.fanout.integration.HttpConnector;
import io.github.arsalanrc.fanout.integration.IntegrationServer;
import io.github.arsalanrc.fanout.integration.Market;
import io.github.arsalanrc.fanout.telemetry.SpanSink;
import io.github.arsalanrc.fanout.telemetry.Tracer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Records real searches for the published page to replay.
 *
 * <p>The page is static, so it cannot run any of this. What it can do is replay
 * an answer this code actually produced, and the difference between those two
 * things is the difference between a demonstration and a drawing.
 *
 * <p><b>It runs both services and goes over a socket.</b> Calling
 * {@link Fanout} in process would be simpler and would quietly drop the hop
 * that the whole architecture is about, so the numbers on the page would be
 * missing the part worth showing.
 *
 * <p><b>Every latency on the page comes from here.</b> Nothing is typed in by
 * hand and nothing is rounded to look tidy. A page carrying invented timings is
 * a design mock of a system rather than a record of one, and the only way to
 * keep that honest is to have no path by which a number could be invented.
 *
 * <p>{@code PageDataTest} re-runs these searches and compares the answer with
 * what is committed, so the files cannot quietly go stale when the engine
 * changes. It compares prices, rows, suppliers and drops. It deliberately does
 * not compare timings, which differ on every run and on every machine.
 *
 * <pre>
 *   mvn -q install -DskipTests
 *   mvn -q -pl search exec:java \
 *     -Dexec.mainClass=io.github.arsalanrc.fanout.search.PageData
 * </pre>
 */
public final class PageData {

    /** The searches the page shows, and why each one is there. */
    public static final List<Capture> CAPTURES = List.of(
            // The ordinary case. Everybody answers, and one journey is sold twice.
            new Capture("search-cabin", "basket=cabin&budget=3000"),
            // The same search with a suitcase. A different airline wins.
            new Capture("search-checked", "basket=checked&budget=3000"),
            // Tighter than the slowest supplier answers, so it comes back partial.
            new Capture("search-tight", "basket=cabin&budget=300"));

    public record Capture(String name, String parameters) {

        public String query() {
            return "origin=DUS&destination=STN&date=2026-09-01&" + parameters;
        }
    }

    private PageData() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "site/data");
        Files.createDirectories(out);

        for (Capture capture : CAPTURES) {
            String body = record(capture);
            Files.writeString(out.resolve(capture.name() + ".json"), body + "\n");
            System.out.println("wrote " + capture.name() + ".json, " + body.length() + " bytes");
        }

        System.out.println();
        System.out.println("Every timing in those files was measured across two processes.");
        System.out.println("Fares are modelled. See fixtures/README.md.");
    }

    /** One search, through both services, exactly as a browser would get it. */
    public static String record(Capture capture) throws Exception {
        try (IntegrationServer suppliers = new IntegrationServer(0, Market.suppliers())) {
            suppliers.start();

            List<Connector> remote = HttpConnector.discover(
                    "http://127.0.0.1:" + suppliers.port(), Duration.ofSeconds(2));

            Fanout fanout = new Fanout(remote, new Breaker(3, Duration.ofSeconds(30)),
                    new Tracer(SpanSink.NONE));

            try (SearchServer edge = new SearchServer(0, fanout, Market::asOf, "distributed")) {
                edge.start();
                return get("http://127.0.0.1:" + edge.port() + "/search?" + capture.query());
            }
        }
    }

    private static String get(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("The search answered " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }
}
