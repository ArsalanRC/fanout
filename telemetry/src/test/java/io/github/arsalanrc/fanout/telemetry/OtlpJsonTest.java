package io.github.arsalanrc.fanout.telemetry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Writing OTLP by hand is only defensible if it is right, so these check the
 * parts of the specification a hand-written encoder gets wrong.
 */
class OtlpJsonTest {

    /** Fixed bytes, so identifiers are exact rather than matched by pattern. */
    private static RandomGenerator fixed(int seed) {
        return new RandomGenerator() {
            private int next = seed;

            @Override
            public long nextLong() {
                return next++;
            }

            @Override
            public void nextBytes(byte[] bytes) {
                for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (next + i);
                next++;
            }
        };
    }

    private static Tracer tracer(List<Span> collected) {
        return new Tracer(collected::addAll,
                Clock.fixed(Instant.parse("2026-09-01T12:00:00.123456789Z"), ZoneOffset.UTC),
                fixed(1));
    }

    @Test
    @DisplayName("nanosecond timestamps are quoted, or a Collector rounds them")
    void large_integers_are_encoded_as_strings() {
        List<Span> collected = new ArrayList<>();
        Tracer.Trace trace = tracer(collected).trace("metasearch");
        trace.end();

        String json = OtlpJson.document(collected);

        // 2026-09-01T12:00:00.123456789Z in epoch nanos. A JSON number is a
        // double, which holds 53 bits exactly, and this needs 61. Unquoted it
        // would round to the nearest 256 nanoseconds and nothing would say so.
        assertTrue(json.contains("\"startTimeUnixNano\":\"1788264000123456789\""), json);
        assertFalse(json.contains("\"startTimeUnixNano\":1788264000123456789"));
    }

    @Test
    void identifiers_are_lowercase_hex_of_the_right_length() {
        List<Span> collected = new ArrayList<>();
        Tracer.Trace trace = tracer(collected).trace("metasearch");
        trace.child("supplier.search", Span.Kind.CLIENT).end();
        trace.end();

        for (Span span : collected) {
            // OTLP overrides the usual base64 mapping for these two fields.
            assertTrue(span.traceId().matches("[0-9a-f]{32}"), span.traceId());
            assertTrue(span.spanId().matches("[0-9a-f]{16}"), span.spanId());
            assertNotEquals("0".repeat(32), span.traceId());
            assertNotEquals("0".repeat(16), span.spanId());
        }
    }

    @Test
    @DisplayName("attributes are a typed list, not an object")
    void attributes_carry_their_type() {
        List<Span> collected = new ArrayList<>();
        Tracer.Trace trace = tracer(collected).trace("metasearch");
        trace.root()
                .attribute("route", "DUS-STN")
                .attribute("passengers", 3)
                .attribute("complete", false);
        trace.end();

        String json = OtlpJson.document(collected);

        // An untagged value is dropped by the Collector rather than rejected,
        // so the document imports with the attributes silently missing.
        assertTrue(json.contains("{\"key\":\"route\",\"value\":{\"stringValue\":\"DUS-STN\"}}"), json);
        assertTrue(json.contains("{\"key\":\"passengers\",\"value\":{\"intValue\":\"3\"}}"), json);
        assertTrue(json.contains("{\"key\":\"complete\",\"value\":{\"boolValue\":false}}"), json);
    }

    @Test
    void a_child_names_its_parent_and_a_root_omits_the_field() {
        List<Span> collected = new ArrayList<>();
        Tracer.Trace trace = tracer(collected).trace("metasearch");
        String childId = trace.child("supplier.search", Span.Kind.CLIENT).spanId();
        trace.child("supplier.search", Span.Kind.CLIENT).end();
        trace.end();

        Span root = collected.stream().filter(s -> s.parentSpanId() == null).findFirst().orElseThrow();
        Span child = collected.stream().filter(s -> s.parentSpanId() != null).findFirst().orElseThrow();

        assertEquals(root.spanId(), child.parentSpanId());
        assertEquals(root.traceId(), child.traceId());
        assertNotEquals(root.spanId(), childId);

        String json = OtlpJson.document(List.of(root));
        assertFalse(json.contains("parentSpanId"), "a root span sends no parent, not sixteen zeroes");
    }

    @Test
    void span_kind_and_status_use_the_specification_numbers() {
        List<Span> collected = new ArrayList<>();
        Tracer.Trace trace = tracer(collected).trace("metasearch");
        trace.child("supplier.search", Span.Kind.CLIENT).failed("certificate expired").end();
        trace.end();

        String json = OtlpJson.document(collected);

        // CLIENT is 3 and ERROR is 2. Inventing the numbers produces a document
        // that parses and means something else.
        assertTrue(json.contains("\"kind\":3"), json);
        assertTrue(json.contains("\"status\":{\"code\":2,\"message\":\"certificate expired\"}"), json);
        // The root ended without an explicit status, so UNSET.
        assertTrue(json.contains("\"kind\":1"), json);
    }

    @Test
    @DisplayName("a newline in an error message does not produce invalid JSON")
    void control_characters_are_escaped() {
        List<Span> collected = new ArrayList<>();
        Tracer.Trace trace = tracer(collected).trace("metasearch");
        trace.child("supplier.search", Span.Kind.CLIENT)
                .failed("read failed:\nconnection \"reset\"\tby peer" + (char) 0x01 + "end").end();
        trace.end();

        String json = OtlpJson.document(collected);

        // A raw newline inside a JSON string is invalid, and a supplier error
        // is exactly the value most likely to contain one.
        assertFalse(json.contains("failed:\nconnection"), "a raw newline would make this invalid JSON");
        assertTrue(json.contains("read failed:\\nconnection"), json);
        assertTrue(json.contains("\\\"reset\\\""), json);
        assertTrue(json.contains("\\tby peer"), json);

        /*
         * A control character with no shorthand escape, which is the case the
         * other three never reach. Newline, tab and the quote each have their
         * own branch, so without this the general "below 0x20" rule is never
         * exercised and a raw 0x01 ships inside a JSON string. That is invalid
         * JSON, and parsers reject the document rather than the field.
         */
        assertFalse(json.contains("by peer" + (char) 0x01), "a raw control character got out");
        assertTrue(json.contains("by peer" + "\\" + "u0001" + "end"), json);
    }

    @Test
    void a_span_never_ends_before_it_starts() {
        List<Span> collected = new ArrayList<>();
        Tracer.Trace trace = tracer(collected).trace("metasearch");
        trace.end();

        // The wall clock is fixed here, so a duration measured from it would be
        // exactly zero. It comes from nanoTime instead, which is monotonic, so
        // a clock correction cannot produce a negative span.
        Span root = collected.getFirst();
        assertTrue(root.durationNanos() >= 0);
        assertTrue(root.endUnixNano() >= root.startUnixNano());
    }

    @Test
    void a_trace_is_exported_once_even_if_ended_twice() {
        List<List<Span>> exports = new ArrayList<>();
        Tracer tracer = new Tracer(exports::add,
                Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC), fixed(1));

        Tracer.Trace trace = tracer.trace("metasearch");
        trace.end();
        trace.end();

        assertEquals(1, exports.size());
    }
}
