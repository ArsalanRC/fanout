package io.github.arsalanrc.fanout.search;

import io.github.arsalanrc.fanout.integration.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The published page shows what this code actually does, or this fails.
 *
 * <p>The page is static and replays a recorded answer, which is the only way a
 * page on GitHub Pages can show a running system. The risk that comes with it
 * is obvious and worth guarding: the engine changes, nobody re-runs the
 * recorder, and the page carries on presenting last month's prices as though
 * they were today's behaviour. It would look completely fine.
 *
 * <p>So every capture is re-run here and compared with the committed file.
 * Prices, rows, sellers, supplier statuses and drops all have to match.
 *
 * <p><b>Timings are deliberately not compared.</b> They differ on every run and
 * on every machine, so asserting them would produce a test that fails for a
 * reason nobody cares about, and a test that fails often enough gets deleted.
 * What matters is that the shape of the answer is still the shape on the page.
 */
class PageDataTest {

    @Test
    @DisplayName("every recorded search still matches what the services produce")
    void the_page_data_has_not_gone_stale() throws Exception {
        List<String> complaints = new ArrayList<>();

        for (PageData.Capture capture : PageData.CAPTURES) {
            Path file = locate(capture.name() + ".json");
            assertTrue(Files.exists(file), """
                    Missing %s. Run PageData to write it, and commit the result.
                    """.formatted(file));

            Json committed = Json.parse(Files.readString(file));
            Json fresh = Json.parse(PageData.record(capture));

            compare(capture.name(), committed, fresh, complaints);
        }

        assertTrue(complaints.isEmpty(), """
                The page is showing something the code no longer produces. Re-run the recorder \
                and commit what it writes:

                  mvn -q install -DskipTests
                  mvn -q -pl search exec:java -Dexec.mainClass=%s

                %s""".formatted(PageData.class.getName(), String.join("\n", complaints)));
    }

    private static void compare(String name, Json committed, Json fresh, List<String> complaints) {
        // `bool` rather than `text`, because the reader refuses to hand a
        // boolean back as a string and is right to.
        check(name, "complete",
                String.valueOf(committed.get("complete").bool()),
                String.valueOf(fresh.get("complete").bool()), complaints);
        check(name, "dropped.lapsed",
                committed.path("dropped", "lapsed").text(),
                fresh.path("dropped", "lapsed").text(), complaints);
        check(name, "dropped.unpriceable",
                committed.path("dropped", "unpriceable").text(),
                fresh.path("dropped", "unpriceable").text(), complaints);
        check(name, "rows", rows(committed), rows(fresh), complaints);
        check(name, "suppliers", suppliers(committed), suppliers(fresh), complaints);
    }

    /** Every row, as the page would print it. Order matters: it is the ranking. */
    private static String rows(Json document) {
        List<String> out = new ArrayList<>();
        for (Json row : document.get("itineraries").arrayOrEmpty()) {
            out.add("%s %s %s %s sellers=%s".formatted(
                    row.get("key").text(),
                    row.get("carrier").text(),
                    row.path("best", "minor").text(),
                    row.path("spread", "minor").text(),
                    row.get("sellers").text()));
        }
        return String.join(" | ", out);
    }

    /** Who answered and how, without the timings. */
    private static String suppliers(Json document) {
        List<String> out = new ArrayList<>();
        for (Json outcome : document.get("suppliers").arrayOrEmpty()) {
            out.add(outcome.get("supplier").text() + "=" + outcome.get("status").text()
                    + "/" + outcome.get("fares").text());
        }
        return String.join(" | ", out);
    }

    private static void check(String name, String field, String was, String now, List<String> complaints) {
        if (!was.equals(now)) {
            complaints.add("  %s, %s:%n    on the page: %s%n    from the code: %s"
                    .formatted(name, field, was, now));
        }
    }

    /**
     * The data directory, from wherever the tests were started.
     *
     * <p>Surefire runs in the module directory and a developer running one test
     * from an IDE may not. Both are worth surviving, because a test that only
     * passes when launched a particular way gets ignored.
     */
    private static Path locate(String file) {
        for (Path root : List.of(Path.of("site", "data"), Path.of("..", "site", "data"))) {
            Path candidate = root.resolve(file);
            if (Files.exists(candidate)) return candidate;
        }
        return Path.of("..", "site", "data").resolve(file);
    }
}
