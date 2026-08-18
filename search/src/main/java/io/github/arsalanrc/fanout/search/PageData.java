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
    /**
     * The searches the page shows.
     *
     * <p>Every route crossed with every control the page offers, because the
     * page turns those into buttons and a combination with no file behind it is
     * a button that quietly shows the wrong answer.
     */
    public static final List<Capture> CAPTURES = build();

    private static List<Capture> build() {
        List<Capture> all = new java.util.ArrayList<>();
        for (String route : Market.ROUTES) {
            String[] parts = route.split("-");
            for (java.time.LocalDate date : Market.DATES) {
                for (String basket : List.of("cabin", "checked")) {
                    for (String budget : List.of("3000", "300")) {
                        all.add(new Capture(
                                "search-" + route.toLowerCase(java.util.Locale.ROOT)
                                        + "-" + date + "-" + basket + "-" + budget,
                                route, date,
                                "origin=" + parts[0] + "&destination=" + parts[1]
                                        + "&date=" + date + "&basket=" + basket
                                        + "&budget=" + budget));
                    }
                }
            }
        }
        return List.copyOf(all);
    }

    public record Capture(String name, String route, java.time.LocalDate date, String query) {
    }

    private PageData() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "site/data");
        Files.createDirectories(out);
        int written = 0;

        java.util.Map<String, List<Capture>> byMarket = new java.util.LinkedHashMap<>();
        for (Capture capture : CAPTURES) {
            byMarket.computeIfAbsent(capture.route() + capture.date(), k -> new java.util.ArrayList<>())
                    .add(capture);
        }

        for (List<Capture> market : byMarket.values()) {
            List<String> answers = record(market);
            for (int i = 0; i < market.size(); i++) {
                Files.writeString(out.resolve(market.get(i).name() + ".json"), answers.get(i) + "\n");
                written++;
            }
            System.out.println("recorded " + market.getFirst().route()
                    + " on " + market.getFirst().date());
        }

        System.out.println("\n" + written + " searches written to " + out);
        System.out.println();
        System.out.println("Every timing in those files was measured across two processes.");
        System.out.println("Fares are modelled. See fixtures/README.md.");
    }

    /** One search, through both services, exactly as a browser would get it. */
    public static String record(Capture capture) throws Exception {
        return record(List.of(capture)).getFirst();
    }

    /**
     * Records a run of captures that share a market, on one pair of servers.
     *
     * <p>Every capture in the list must be for the same route and date, because
     * the servers are built for one market. Starting a fresh pair for each of
     * four baskets against the same fixtures is a few hundred milliseconds
     * spent proving nothing, and there are three hundred and sixty four of them.
     */
    public static List<String> record(List<Capture> captures) throws Exception {
        Capture first = captures.getFirst();

        try (IntegrationServer suppliers =
                     new IntegrationServer(0, Market.suppliers(first.route(), first.date()))) {
            suppliers.start();

            List<Connector> remote = HttpConnector.discover(
                    "http://127.0.0.1:" + suppliers.port(), Duration.ofSeconds(2));

            List<String> answers = new java.util.ArrayList<>();
            for (Capture capture : captures) {
                Fanout fanout = new Fanout(remote, new Breaker(3, Duration.ofSeconds(30)),
                        new Tracer(SpanSink.NONE));

                try (SearchServer edge = new SearchServer(
                        0, fanout, () -> Market.asOf(capture.date()), "distributed")) {
                    edge.start();
                    answers.add(get("http://127.0.0.1:" + edge.port() + "/search?" + capture.query()));
                }
            }
            return answers;
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
