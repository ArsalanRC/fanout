package io.github.arsalanrc.fanout.integration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A small, strict JSON reader.
 *
 * <p>The JDK has no JSON parser, and this repository has no runtime
 * dependencies, so it writes one. That is a deliberate choice rather than a
 * constraint worked around: normalising supplier responses is where the real
 * bugs in a metasearch live, and owning the parser keeps them where they can be
 * seen and tested.
 *
 * <p><b>The reason this file matters more than it looks.</b> A wrong parser
 * does not throw. It returns something plausible, and the fare built from it
 * looks like every other fare. So this one refuses rather than guesses: bad
 * input raises {@link Malformed} with the offset, a missing field is
 * {@link #isMissing()} rather than null, and asking a string for a number is an
 * error rather than a zero.
 *
 * <p><b>And the part that protects the money.</b> {@link #minorUnits(int)}
 * converts {@code "123.45"} to {@code 12345} by reading the digits, never by
 * going through a {@code double}. A parser that hands back a double has already
 * lost precision before {@link io.github.arsalanrc.fanout.core.Money} can
 * defend it, which makes the guard there useless.
 */
public final class Json {

    /** What a member lookup returns when the field is not there. */
    public static final Json MISSING = new Json(Kind.MISSING, null);

    private enum Kind { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL, MISSING }

    private final Kind kind;
    private final Object value;

    private Json(Kind kind, Object value) {
        this.kind = kind;
        this.value = value;
    }

    // ------------------------------------------------------------- parsing

    public static Json parse(String text) {
        if (text == null) throw new Malformed("No input", 0);

        Reader reader = new Reader(text);
        reader.skipWhitespace();
        Json parsed = reader.readValue();

        reader.skipWhitespace();
        // Trailing content is a real signal: two concatenated documents, or a
        // truncated read that happened to stop on a valid value. Both produce a
        // usable first object, which is exactly why they go unnoticed.
        if (!reader.done()) throw new Malformed("Trailing content after the value", reader.at());

        return parsed;
    }

    // ------------------------------------------------------------- access

    /** A member of an object, or {@link #MISSING}. Never null. */
    public Json get(String field) {
        if (kind != Kind.OBJECT) return MISSING;
        return asMap().getOrDefault(field, MISSING);
    }

    /** Walk several levels at once, stopping at the first thing that is not there. */
    public Json path(String... fields) {
        Json here = this;
        for (String field : fields) {
            here = here.get(field);
            if (here.isMissing()) return MISSING;
        }
        return here;
    }

    public List<Json> array() {
        if (kind != Kind.ARRAY) throw new WrongType("array", describe());
        @SuppressWarnings("unchecked")
        List<Json> list = (List<Json>) value;
        return list;
    }

    /** An array, or an empty list when the field is absent. */
    public List<Json> arrayOrEmpty() {
        return kind == Kind.MISSING || kind == Kind.NULL ? List.of() : array();
    }

    public String text() {
        if (kind == Kind.STRING) return (String) value;
        // A number read as text is normal in supplier payloads: some quote
        // their prices and some do not, and both mean the same thing.
        if (kind == Kind.NUMBER) return (String) value;
        throw new WrongType("string", describe());
    }

    public Optional<String> optionalText() {
        return kind == Kind.STRING || kind == Kind.NUMBER ? Optional.of(text()) : Optional.empty();
    }

    public boolean isMissing() {
        return kind == Kind.MISSING || kind == Kind.NULL;
    }

    /**
     * A decimal amount as minor units, without a {@code double} anywhere.
     *
     * <p>{@code minorUnits(2)} turns {@code "123.45"} into {@code 12345} and
     * {@code "123.4"} into {@code 12340}. Extra places are an error rather than
     * a rounding, because a supplier sending three decimals to a two-decimal
     * currency has said something this code does not understand, and quietly
     * dropping the last digit is how a price ends up a cent out for a month.
     *
     * @param scale minor units per major unit as a power of ten: 2 for EUR, 0 for JPY
     */
    public long minorUnits(int scale) {
        String raw = text().trim();
        if (raw.isEmpty()) throw new Malformed("Empty amount", 0);

        boolean negative = raw.startsWith("-");
        if (negative || raw.startsWith("+")) raw = raw.substring(1);

        int dot = raw.indexOf('.');
        String whole = dot < 0 ? raw : raw.substring(0, dot);
        String fraction = dot < 0 ? "" : raw.substring(dot + 1);

        if (whole.isEmpty()) whole = "0";
        if (!whole.matches("\\d+") || !fraction.matches("\\d*")) {
            throw new Malformed("Not a decimal amount: " + text(), 0);
        }
        if (fraction.length() > scale) {
            throw new Malformed(
                    "Amount " + text() + " has more decimal places than the currency has, which is "
                            + scale + ". Refusing to round somebody's money silently.", 0);
        }

        String padded = whole + (fraction + "0".repeat(scale)).substring(0, scale);
        long parsed = Long.parseLong(padded);
        return negative ? -parsed : parsed;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Json> asMap() {
        return (Map<String, Json>) value;
    }

    private String describe() {
        return kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public String toString() {
        return kind == Kind.MISSING ? "<missing>" : String.valueOf(value);
    }

    // ------------------------------------------------------------- errors

    public static final class Malformed extends RuntimeException {
        public Malformed(String message, int offset) {
            super(message + " (at offset " + offset + ")");
        }
    }

    public static final class WrongType extends RuntimeException {
        public WrongType(String wanted, String found) {
            super("Wanted a " + wanted + " and found a " + found);
        }
    }

    // ------------------------------------------------------------- reader

    private static final class Reader {
        private final String src;
        private int i;

        Reader(String src) {
            this.src = src;
        }

        int at() {
            return i;
        }

        boolean done() {
            return i >= src.length();
        }

        void skipWhitespace() {
            while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
        }

        Json readValue() {
            if (done()) throw new Malformed("Ran out of input", i);

            return switch (src.charAt(i)) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> new Json(Kind.STRING, readString());
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        private Json readObject() {
            expect('{');
            Map<String, Json> members = new LinkedHashMap<>();
            skipWhitespace();

            if (peek() == '}') {
                i++;
                return new Json(Kind.OBJECT, members);
            }

            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                members.put(key, readValue());
                skipWhitespace();

                char next = peek();
                i++;
                if (next == '}') return new Json(Kind.OBJECT, members);
                if (next != ',') throw new Malformed("Expected , or } in an object", i - 1);
            }
        }

        private Json readArray() {
            expect('[');
            List<Json> items = new ArrayList<>();
            skipWhitespace();

            if (peek() == ']') {
                i++;
                return new Json(Kind.ARRAY, items);
            }

            while (true) {
                skipWhitespace();
                items.add(readValue());
                skipWhitespace();

                char next = peek();
                i++;
                if (next == ']') return new Json(Kind.ARRAY, items);
                if (next != ',') throw new Malformed("Expected , or ] in an array", i - 1);
            }
        }

        private String readString() {
            expect('"');
            StringBuilder out = new StringBuilder();

            while (true) {
                if (done()) throw new Malformed("Unterminated string", i);
                char c = src.charAt(i++);

                if (c == '"') return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }

                if (done()) throw new Malformed("Unterminated escape", i);
                char escape = src.charAt(i++);

                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (i + 4 > src.length()) throw new Malformed("Truncated \\u escape", i);
                        out.append((char) Integer.parseInt(src.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw new Malformed("Unknown escape \\" + escape, i - 1);
                }
            }
        }

        private Json readBoolean() {
            if (src.startsWith("true", i)) {
                i += 4;
                return new Json(Kind.BOOLEAN, Boolean.TRUE);
            }
            if (src.startsWith("false", i)) {
                i += 5;
                return new Json(Kind.BOOLEAN, Boolean.FALSE);
            }
            throw new Malformed("Expected true or false", i);
        }

        private Json readNull() {
            if (!src.startsWith("null", i)) throw new Malformed("Expected null", i);
            i += 4;
            return new Json(Kind.NULL, null);
        }

        /**
         * Numbers are kept as the text they arrived as.
         *
         * <p>This is the decision that protects every price in the system. Turn
         * {@code 123.45} into a double here and the money type downstream is
         * defending an amount that has already lost precision.
         */
        private Json readNumber() {
            int start = i;
            if (peek() == '-' || peek() == '+') i++;

            while (!done() && (Character.isDigit(src.charAt(i)) || "+-.eE".indexOf(src.charAt(i)) >= 0)) i++;

            String raw = src.substring(start, i);
            if (raw.isEmpty() || !raw.matches("[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) {
                throw new Malformed("Not a number: " + raw, start);
            }
            return new Json(Kind.NUMBER, raw);
        }

        private char peek() {
            if (done()) throw new Malformed("Ran out of input", i);
            return src.charAt(i);
        }

        private void expect(char c) {
            if (done() || src.charAt(i) != c) throw new Malformed("Expected " + c, i);
            i++;
        }
    }
}
