package io.github.arsalanrc.fanout.telemetry;

import java.util.List;

/**
 * Where finished spans go.
 *
 * <p>An interface with one method, so the tests can collect spans in memory and
 * the application can write them wherever it likes. There is deliberately no
 * network exporter here: shipping spans over OTLP/HTTP would mean a dependency,
 * and the whole point is that a Collector can read what this writes without
 * one. Writing OTLP/JSON to standard output and letting a Collector tail it is
 * a real deployment, not a workaround.
 */
@FunctionalInterface
public interface SpanSink {
    void accept(List<Span> trace);

    /** Throws nothing away and does nothing. The default when nobody is watching. */
    SpanSink NONE = trace -> {
    };
}
