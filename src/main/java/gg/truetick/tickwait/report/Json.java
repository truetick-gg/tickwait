package gg.truetick.tickwait.report;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal JSON writer (no dependencies: the plugin must load on any server version). */
public final class Json {
    private final StringBuilder sb = new StringBuilder();
    private final Deque<Boolean> first = new ArrayDeque<>();
    private boolean afterName;

    private void sep() {
        if (afterName) {
            afterName = false;
            return;
        }
        if (!first.isEmpty()) {
            if (first.peek()) {
                first.pop();
                first.push(false);
            } else {
                sb.append(',');
            }
        }
    }

    public Json beginObject() {
        sep();
        sb.append('{');
        first.push(true);
        return this;
    }

    public Json endObject() {
        first.pop();
        sb.append('}');
        return this;
    }

    public Json beginArray() {
        sep();
        sb.append('[');
        first.push(true);
        return this;
    }

    public Json endArray() {
        first.pop();
        sb.append(']');
        return this;
    }

    public Json name(String name) {
        sep();
        quote(name);
        sb.append(':');
        afterName = true;
        return this;
    }

    public Json value(String v) {
        sep();
        if (v == null) {
            sb.append("null");
        } else {
            quote(v);
        }
        return this;
    }

    public Json value(long v) {
        sep();
        sb.append(v);
        return this;
    }

    /** NaN and infinities aren't JSON — they are written as null ("not measured"). */
    public Json value(double v) {
        sep();
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            sb.append("null");
        } else {
            sb.append(String.format(Locale.ROOT, "%.4f", v));
        }
        return this;
    }

    public Json value(boolean v) {
        sep();
        sb.append(v);
        return this;
    }

    private void quote(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    @Override
    public String toString() {
        return sb.toString();
    }

    private static final Pattern UNICODE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    /**
     * Reads a top-level string field from a small, trusted JSON response (our own API's).
     * Returns null when absent. Not a general parser.
     */
    public static String stringField(String json, String field) {
        if (json == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find()) {
            return null;
        }
        String raw = m.group(1);
        Matcher u = UNICODE.matcher(raw);
        StringBuffer out = new StringBuffer();
        while (u.find()) {
            u.appendReplacement(out, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(u.group(1), 16))));
        }
        u.appendTail(out);
        return out.toString().replace("\\\"", "\"").replace("\\/", "/").replace("\\n", "\n").replace("\\\\", "\\");
    }
}
