package io.github.arsalanrc.fanout.telemetry;

import java.util.List;
import java.util.Map;

/**
 * Spans as an OTLP/JSON document.
 *
 * <p>This is the protobuf-JSON mapping of {@code ExportTraceServiceRequest},
 * which is what an OpenTelemetry Collector accepts. Writing it by hand instead
 * of taking the SDK is only defensible if it is actually right, so three
 * details of the specification are worth naming because each one is a place a
 * hand-written encoder quietly goes wrong.
 *
 * <p><b>64-bit integers are strings.</b> Protobuf JSON encodes {@code fixed64}
 * and {@code uint64} as quoted decimal, because JSON numbers are doubles and a
 * nanosecond timestamp is bigger than a double can hold exactly. Emitting
 * {@code 1756728000123456789} as a bare number silently rounds it to the
 * nearest 256 nanoseconds. Every timestamp here is quoted.
 *
 * <p><b>Attributes are a list, not an object.</b> {@code [{"key":…,"value":…}]}
 * rather than {@code {"key": value}}, and each value is itself tagged with its
 * type: {@code stringValue}, {@code intValue}, {@code boolValue}. An untagged
 * value is dropped by the Collector rather than rejected, which is the worst
 * outcome: a document that imports with the attributes missing.
 *
 * <p><b>Ids are lowercase hex, not base64.</b> The protobuf field is
 * {@code bytes}, whose JSON mapping is normally base64, and OTLP overrides that
 * for trace and span ids specifically.
 */
public final class OtlpJson {

    private static final String SERVICE = "fanout";
    private static final String SCOPE = "io.github.arsalanrc.fanout";

    private OtlpJson() {
    }

    /** One trace as a complete export request. */
    public static String document(List<Span> spans) {
        StringBuilder out = new StringBuilder();

        out.append("{\"resourceSpans\":[{\"resource\":{\"attributes\":[");
        attribute(out, "service.name", SERVICE);
        out.append("]},\"scopeSpans\":[{\"scope\":{\"name\":");
        string(out, SCOPE);
        out.append("},\"spans\":[");

        for (int i = 0; i < spans.size(); i++) {
            if (i > 0) out.append(',');
            span(out, spans.get(i));
        }

        return out.append("]}]}]}").toString();
    }

    private static void span(StringBuilder out, Span span) {
        out.append("{\"traceId\":");
        string(out, span.traceId());
        out.append(",\"spanId\":");
        string(out, span.spanId());

        // A root span omits the field rather than sending sixteen zeroes. Both
        // are accepted; omitting is what the SDKs emit.
        if (span.parentSpanId() != null) {
            out.append(",\"parentSpanId\":");
            string(out, span.parentSpanId());
        }

        out.append(",\"name\":");
        string(out, span.name());
        out.append(",\"kind\":").append(span.kind().code);

        // Quoted, because these do not fit a double exactly.
        out.append(",\"startTimeUnixNano\":\"").append(span.startUnixNano()).append('"');
        out.append(",\"endTimeUnixNano\":\"").append(span.endUnixNano()).append('"');

        out.append(",\"attributes\":[");
        boolean first = true;
        for (Map.Entry<String, Object> entry : span.attributes().entrySet()) {
            if (!first) out.append(',');
            first = false;
            attribute(out, entry.getKey(), entry.getValue());
        }
        out.append(']');

        out.append(",\"status\":{\"code\":").append(span.status().code);
        if (span.statusMessage() != null) {
            out.append(",\"message\":");
            string(out, span.statusMessage());
        }
        out.append("}}");
    }

    /** One typed attribute. The type tag is what stops the Collector dropping it. */
    private static void attribute(StringBuilder out, String key, Object value) {
        out.append("{\"key\":");
        string(out, key);
        out.append(",\"value\":{");

        switch (value) {
            case null -> out.append("\"stringValue\":\"\"");
            case Boolean b -> out.append("\"boolValue\":").append(b);
            case Integer i -> out.append("\"intValue\":\"").append(i).append('"');
            case Long l -> out.append("\"intValue\":\"").append(l).append('"');
            case Double d -> out.append("\"doubleValue\":").append(d);
            default -> {
                out.append("\"stringValue\":");
                string(out, value.toString());
            }
        }

        out.append("}}");
    }

    /**
     * A JSON string, escaped.
     *
     * <p>Control characters have to go as {@code \\uXXXX}. A raw newline inside
     * a string is invalid JSON, and a supplier error message is exactly the
     * sort of value that contains one.
     */
    private static void string(StringBuilder out, String value) {
        out.append('"');

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }

        out.append('"');
    }

    /** Writes each trace as one line of OTLP/JSON, which a Collector can tail. */
    public static SpanSink toStdout() {
        return trace -> {
            if (!trace.isEmpty()) System.out.println(document(trace));
        };
    }
}
