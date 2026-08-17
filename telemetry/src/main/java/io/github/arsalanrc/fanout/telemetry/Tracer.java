package io.github.arsalanrc.fanout.telemetry;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records what a search actually did, as OpenTelemetry spans.
 *
 * <p>A metasearch is the case distributed tracing was invented for. Eight
 * suppliers are asked at once under one budget, and the interesting question is
 * never "did it work" but "which one ate the deadline". A log line cannot
 * answer that. A trace with a span per supplier shows it at a glance: which
 * answered in forty milliseconds, which took the whole budget, which the
 * breaker skipped without calling at all.
 *
 * <p><b>No dependency, and that is not a compromise.</b> The output is OTLP/JSON,
 * which is the wire format the OpenTelemetry Collector already speaks, so a
 * Collector tailing this output ingests it with no translation. Pulling in an
 * SDK would buy automatic instrumentation this project does not need and would
 * cost the one claim the whole repository is built on.
 *
 * <p>Both the clock and the randomness are injectable, so the tests can assert
 * exact identifiers and exact timestamps rather than matching patterns.
 */
public final class Tracer {

    private final SpanSink sink;
    private final Clock clock;
    private final java.util.random.RandomGenerator random;

    public Tracer(SpanSink sink) {
        this(sink, Clock.systemUTC(), new SecureRandom());
    }

    public Tracer(SpanSink sink, Clock clock, java.util.random.RandomGenerator random) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Begin a trace. Every span inside it shares one trace id. */
    public Trace trace(String name) {
        return new Trace(name);
    }

    /**
     * One search, and every supplier call inside it.
     *
     * <p>Not thread safe by accident: {@link #child} is called from the fan-out
     * thread and {@link Recording#end} from the virtual threads, so the span
     * list is synchronised. A tracer that dropped spans under concurrency would
     * be worst exactly when the trace mattered most.
     */
    public final class Trace {
        private final String traceId = hex(16);
        private final String rootId = hex(8);
        private final List<Span> spans = new ArrayList<>();
        private final Recording root;
        private final AtomicBoolean exported = new AtomicBoolean();

        private Trace(String name) {
            this.root = new Recording(name, rootId, null, Span.Kind.INTERNAL);
        }

        public String traceId() {
            return traceId;
        }

        public Recording root() {
            return root;
        }

        /** A span for work handed to somebody else, parented to the root. */
        public Recording child(String name, Span.Kind kind) {
            return new Recording(name, hex(8), rootId, kind);
        }

        /** A span in progress, inside this trace. */
        public final class Recording {
            private final String name;
            private final String spanId;
            private final String parentId;
            private final Span.Kind kind;
            private final long startUnixNano;
            private final long startedNanoTime;
            private final Map<String, Object> attributes = new LinkedHashMap<>();

            private Span.Status status = Span.Status.UNSET;
            private String statusMessage;
            private boolean ended;

            private Recording(String name, String spanId, String parentId, Span.Kind kind) {
                this.name = name;
                this.spanId = spanId;
                this.parentId = parentId;
                this.kind = kind;

                /*
                 * Two clocks on purpose. The wall clock says when this
                 * happened, which is what a trace is read against, and it can
                 * jump backwards when the machine syncs time. `nanoTime` is
                 * monotonic and says how long it took. Using the wall clock for
                 * both is how a span ends up with a negative duration on the
                 * day the clock is corrected.
                 */
                this.startUnixNano = unixNanos();
                this.startedNanoTime = System.nanoTime();
            }

            public Recording attribute(String key, Object value) {
                attributes.put(key, value);
                return this;
            }

            public Recording failed(String message) {
                this.status = Span.Status.ERROR;
                this.statusMessage = message;
                return this;
            }

            public Recording ok() {
                this.status = Span.Status.OK;
                return this;
            }

            public String spanId() {
                return spanId;
            }

            public void end() {
                if (ended) return;
                ended = true;

                long elapsed = System.nanoTime() - startedNanoTime;
                finished(new Span(traceId, spanId, parentId, name, kind,
                        startUnixNano, startUnixNano + elapsed, attributes, status, statusMessage));
            }
        }

        private void finished(Span span) {
            synchronized (spans) {
                spans.add(span);
            }
        }

        /**
         * End the root and hand the whole trace to the sink, once.
         *
         * <p>Exported as one list rather than span by span, because a consumer
         * that sees a child before its parent has to buffer and guess when the
         * trace is complete. A search is short and finishes in one place, so
         * the honest thing is to send it whole.
         */
        public void end() {
            root.end();
            if (!exported.compareAndSet(false, true)) return;

            synchronized (spans) {
                sink.accept(List.copyOf(spans));
            }
        }
    }

    private long unixNanos() {
        java.time.Instant now = clock.instant();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    /** Lowercase hex, the only encoding OTLP accepts for ids. */
    private String hex(int bytes) {
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);

        StringBuilder out = new StringBuilder(bytes * 2);
        for (byte b : raw) out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));

        // An all-zero id is invalid in OTLP and means "no span". Astronomically
        // unlikely from a real generator and trivially produced by a seeded one
        // in a test, so it is corrected rather than shipped.
        return out.toString().chars().allMatch(c -> c == '0') ? "0".repeat(bytes * 2 - 1) + "1" : out.toString();
    }
}
