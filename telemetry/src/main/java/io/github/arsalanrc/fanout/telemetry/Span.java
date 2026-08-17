package io.github.arsalanrc.fanout.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One finished unit of work, in the shape OpenTelemetry defines.
 *
 * <p>The field names and the enum numbers are not invented. They are the OTLP
 * wire contract, so a document built from these spans is something a real
 * OpenTelemetry Collector will accept without a translation layer. Getting the
 * numbers wrong would produce a document that parses and means something else,
 * which is the usual shape of a bug in this repository.
 */
public record Span(String traceId, String spanId, String parentSpanId, String name,
                   Kind kind, long startUnixNano, long endUnixNano,
                   Map<String, Object> attributes, Status status, String statusMessage) {

    /** OTLP span kinds. The numbers are the specification's, not ours. */
    public enum Kind {
        INTERNAL(1),
        /** Work this process asked somebody else to do, which is every connector call. */
        CLIENT(3);

        final int code;

        Kind(int code) {
            this.code = code;
        }
    }

    /** OTLP status codes, again the specification's numbering. */
    public enum Status {
        UNSET(0),
        OK(1),
        ERROR(2);

        final int code;

        Status(int code) {
            this.code = code;
        }
    }

    public Span {
        attributes = Map.copyOf(new LinkedHashMap<>(attributes));
    }

    public long durationNanos() {
        return endUnixNano - startUnixNano;
    }
}
