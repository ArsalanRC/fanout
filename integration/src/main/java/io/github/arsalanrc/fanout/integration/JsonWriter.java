package io.github.arsalanrc.fanout.integration;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The other half of {@link Json}: a small JSON writer.
 *
 * <p>Reading supplier payloads needed a parser. Putting two services on either
 * side of a network needs a writer as well, because the internal fare model now
 * has to survive a hop, and it has to arrive as the same thing that left.
 *
 * <p><b>Balance is checked rather than assumed.</b> {@link #done()} refuses to
 * hand back a document with a container still open. String concatenation
 * produces broken JSON silently, the reader on the far side then fails a long
 * way from the cause, and the stack trace points at the parser rather than at
 * the missing brace. One counter removes that whole class of afternoon.
 *
 * <p><b>The escaping is duplicated from {@code OtlpJson} on purpose.</b>
 * Sharing it would make {@code integration} depend on {@code telemetry}, and
 * that dependency is backwards: the connectors have no business knowing that
 * tracing exists. Twenty lines is a cheaper price than a wrong arrow between
 * two modules, and both copies are pinned by their own tests.
 */
public final class JsonWriter {

    private final StringBuilder out = new StringBuilder();

    /** The closing brace owed for every container still open, innermost first. */
    private final Deque<Character> owed = new ArrayDeque<>();

    private boolean needsComma;

    public JsonWriter object() {
        return open('{', null);
    }

    public JsonWriter object(String field) {
        return open('{', field);
    }

    public JsonWriter array() {
        return open('[', null);
    }

    public JsonWriter array(String field) {
        return open('[', field);
    }

    /** Closes whichever container is innermost, so callers never name it twice. */
    public JsonWriter end() {
        if (owed.isEmpty()) throw new IllegalStateException("Nothing is open to close");

        out.append(owed.pop());
        needsComma = true;
        return this;
    }

    public JsonWriter field(String name, String value) {
        key(name);
        if (value == null) out.append("null");
        else string(value);
        return this;
    }

    public JsonWriter field(String name, long value) {
        key(name);
        out.append(value);
        return this;
    }

    public JsonWriter field(String name, boolean value) {
        key(name);
        out.append(value);
        return this;
    }

    /**
     * A member whose value is already a JSON document.
     *
     * <p>For nesting something that was serialised elsewhere, which is what the
     * page recorder does with a whole search response. Passing it through
     * {@link #field(String, String)} would encode it as a string, and the far
     * side would have to parse twice to get at it.
     *
     * <p><b>The one method here that trusts its caller.</b> Nothing checks that
     * the text is valid JSON, so anything reaching it from outside this
     * codebase would need checking first. Every current caller is passing the
     * body of a response this repository just produced.
     */
    public JsonWriter rawField(String name, String json) {
        key(name);
        out.append(json);
        return this;
    }

    /** An element of an array, rather than a member of an object. */
    public JsonWriter value(String element) {
        separate();
        string(element);
        needsComma = true;
        return this;
    }

    /**
     * The finished document.
     *
     * @throws IllegalStateException when a container was never closed. This is
     *         worth throwing rather than tolerating: unbalanced JSON is still a
     *         string, and it travels a long way before anything notices.
     */
    public String done() {
        if (!owed.isEmpty()) {
            throw new IllegalStateException(
                    owed.size() + " container(s) never closed, so this document is not JSON yet");
        }
        return out.toString();
    }

    private JsonWriter open(char brace, String field) {
        if (field == null) separate();
        else key(field);

        out.append(brace);
        owed.push(brace == '{' ? '}' : ']');
        needsComma = false;
        return this;
    }

    private void key(String name) {
        separate();
        string(name);
        out.append(':');
        needsComma = true;
    }

    private void separate() {
        if (needsComma) out.append(',');
    }

    /**
     * A JSON string, escaped.
     *
     * <p>Control characters go as {@code \\uXXXX}. A raw newline inside a string
     * is invalid JSON, and a supplier error message is exactly the sort of value
     * that carries one.
     */
    private void string(String value) {
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
}
