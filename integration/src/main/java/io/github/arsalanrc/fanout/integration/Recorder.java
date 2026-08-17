package io.github.arsalanrc.fanout.integration;

import io.github.arsalanrc.fanout.core.Deadline;
import io.github.arsalanrc.fanout.core.Fare;
import io.github.arsalanrc.fanout.core.Query;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * Captures a real supplier response and writes it as a fixture.
 *
 * <pre>
 *   export AMADEUS_CLIENT_ID=...
 *   export AMADEUS_CLIENT_SECRET=...
 *   mvn -q -pl integration exec:java -Dexec.mainClass=...Recorder -Dexec.args="DUS STN 2026-09-01 1"
 * </pre>
 *
 * <p><b>What is written is the response, byte for byte.</b> Not a normalised
 * object, not a trimmed version, not something reformatted for readability. The
 * point of a fixture is that the parser cannot tell it from the network, and
 * every edit made to make it prettier is a way the demo drifts away from the
 * thing it claims to demonstrate.
 *
 * <p>The recorded file is then parsed straight back and the fares are printed,
 * so a recording that captured an error page or an empty result is obvious at
 * the moment it is made rather than three weeks later when the demo looks
 * strangely quiet.
 *
 * <p><b>No key is ever written into a fixture.</b> The response body carries
 * flight offers, not credentials, and the token lives only in a header that is
 * never recorded.
 */
public final class Recorder {

    private Recorder() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: Recorder <origin> <destination> <yyyy-mm-dd> <passengers> [output]");
            System.exit(2);
        }

        Query query = new Query(args[0], args[1], LocalDate.parse(args[2]), Integer.parseInt(args[3]));
        Path output = Path.of(args.length > 4 ? args[4]
                : "integration/src/test/resources/fixtures/amadeus-"
                        + query.origin().toLowerCase(java.util.Locale.ROOT) + "-"
                        + query.destination().toLowerCase(java.util.Locale.ROOT) + ".json");

        AmadeusClient client = AmadeusClient.fromEnvironment("amadeus").orElseThrow(() ->
                new IllegalStateException("""
                        No credentials. Set AMADEUS_CLIENT_ID and AMADEUS_CLIENT_SECRET in the \
                        environment, and never commit them.

                        Note that Amadeus decommissioned its free Self-Service portal on 17 July \
                        2026, so these now have to be Enterprise credentials. Without them the \
                        repository runs on the modelled fixtures, which is the supported \
                        default and is labelled as such."""));

        System.out.println("Asking Amadeus for " + query.origin() + " to " + query.destination()
                + " on " + query.departure() + " for " + query.passengers());

        String body = client.fetch(query, Deadline.in(Duration.ofSeconds(30)));

        Files.createDirectories(output.getParent());
        Files.writeString(output, body);
        System.out.println("Wrote " + body.length() + " bytes to " + output);

        /*
         * Read it back through the real parser. A recording of an error page,
         * or of a route with no flights, parses to nothing and would otherwise
         * sit in the repository looking like a working fixture.
         */
        List<Fare> fares = new AmadeusParser("amadeus").parse(body, query);
        System.out.println("Parsed " + fares.size() + " fares");

        if (fares.isEmpty()) {
            System.err.println("""
                    That fixture contains no fares. Check the route and date before committing it: \
                    the Amadeus test environment carries no low-cost carriers and not every route \
                    on every day.""");
            System.exit(1);
        }

        for (Fare fare : fares.stream().limit(3).toList()) {
            System.out.println("  " + fare.itinerary().origin() + "-" + fare.itinerary().destination()
                    + "  " + fare.base() + "  " + fare.itinerary().total().toMinutes() + " min");
        }
    }
}
