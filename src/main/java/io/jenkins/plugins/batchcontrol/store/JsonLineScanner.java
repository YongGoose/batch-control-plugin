package io.jenkins.plugins.batchcontrol.store;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Allocation-light reader of one flat JSONL record, used by the page queries in place of a
 * general JSON parser: a page load reads up to {@link Store#MAX_SCANNED_RECORDS} lines, and
 * building a full JSON object tree per line made its allocation grow with the month.
 *
 * <p>{@link #scan} indexes the top-level members of a line in place (offsets into the caller's
 * buffer, no objects); values are materialised only when asked for, and short repeated strings
 * (job names, users, results, types) come from a small per-query cache instead of a new
 * {@code String} per line. A line that is not a well-formed JSON object makes {@link #scan}
 * return {@code false}; the caller then re-reads it with json-lib before skipping it, so this
 * reader can only be faster than the reference parser, never stricter (security-10 S-02).
 *
 * <p>One instance per query; not thread-safe.
 */
@Restricted(NoExternalUse.class)
final class JsonLineScanner {

    private static final int INITIAL_MEMBERS = 16;
    private static final int CACHE_SIZE = 1024;
    private static final int MAX_CACHED_LENGTH = 64;

    private byte[] buf;
    private int count;
    // Member tables grow on demand (security-10 S-02): a record with many parameters must never be
    // rejected for its size. They only grow to the widest line of the query.
    private int[] keyStart = new int[INITIAL_MEMBERS];
    private int[] keyEnd = new int[INITIAL_MEMBERS];
    /** Value range; for a string, the bytes between the quotes. */
    private int[] valueStart = new int[INITIAL_MEMBERS];
    private int[] valueEnd = new int[INITIAL_MEMBERS];
    /** '"' string (no escapes), '\\' string with escapes, 'n' null, '{' object, '0' other. */
    private byte[] kind = new byte[INITIAL_MEMBERS];

    private final String[] cache = new String[CACHE_SIZE];
    private final byte[][] cacheBytes = new byte[CACHE_SIZE][];

    private int pos;
    private int end;
    private JsonLineScanner nested;

    private boolean isEmptyObject(int s, int e) {
        for (int p = s + 1; p < e - 1; p++) {
            byte b = buf[p];
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') {
                return false;
            }
        }
        return true;
    }

    /** Indexes the members of the object in {@code b[off, off+len)}; {@code false} if malformed. */
    boolean scan(byte[] b, int off, int len) {
        buf = b;
        pos = off;
        end = off + len;
        count = 0;
        try {
            skipWs();
            expect('{');
            skipWs();
            if (peek() == '}') {
                pos++;
                return trailingWsOnly();
            }
            while (true) {
                skipWs();
                expect('"');
                int ks = pos;
                skipStringBody();
                int ke = pos - 1;
                skipWs();
                expect(':');
                skipWs();
                if (count == keyStart.length) {
                    grow();
                }
                keyStart[count] = ks;
                keyEnd[count] = ke;
                readValue(count);
                count++;
                skipWs();
                byte c = next();
                if (c == '}') {
                    return trailingWsOnly();
                }
                if (c != ',') {
                    return false;
                }
            }
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private void grow() {
        int n = keyStart.length * 2;
        keyStart = Arrays.copyOf(keyStart, n);
        keyEnd = Arrays.copyOf(keyEnd, n);
        valueStart = Arrays.copyOf(valueStart, n);
        valueEnd = Arrays.copyOf(valueEnd, n);
        kind = Arrays.copyOf(kind, n);
    }

    /**
     * A numeric member as a long, or {@link Long#MIN_VALUE} when absent or not an integer (the
     * caller then parses the whole line and lets the record decide).
     */
    long optLong(byte[] key) {
        try {
            return requireLong(key);
        } catch (IllegalArgumentException | ArithmeticException e) {
            return Long.MIN_VALUE;
        }
    }

    /** Index of the member named {@code key} (ASCII bytes), or -1. */
    int find(byte[] key) {
        for (int i = 0; i < count; i++) {
            int n = keyEnd[i] - keyStart[i];
            if (n == key.length && Arrays.equals(buf, keyStart[i], keyEnd[i], key, 0, n)) {
                return i;
            }
        }
        return -1;
    }

    /** The string value of member {@code key}; {@code null} if absent or JSON null. */
    String optString(byte[] key, boolean shared) {
        int i = find(key);
        if (i < 0 || kind[i] == 'n') {
            return null;
        }
        return string(i, shared);
    }

    /** As {@link #optString} but absent or null is an error (the record is skipped). */
    String requireString(byte[] key, boolean shared) {
        String value = optString(key, shared);
        if (value == null) {
            throw new IllegalArgumentException("missing field");
        }
        return value;
    }

    /** A numeric member (a JSON number, or a string of digits as json-lib also accepts). */
    long requireLong(byte[] key) {
        int i = find(key);
        if (i < 0 || kind[i] == 'n' || kind[i] == '{') {
            throw new IllegalArgumentException("missing number");
        }
        int s = valueStart[i];
        int e = valueEnd[i];
        boolean negative = s < e && buf[s] == '-';
        if (negative) {
            s++;
        }
        if (s == e) {
            throw new IllegalArgumentException("empty number");
        }
        long v = 0;
        for (int p = s; p < e; p++) {
            int d = buf[p] - '0';
            if (d < 0 || d > 9) {
                throw new IllegalArgumentException("not an integer");
            }
            v = Math.multiplyExact(v, 10) + d;
        }
        return negative ? -v : v;
    }

    /**
     * A member holding an object of string values, or {@code null} if absent, null or empty. Any
     * other value (an array, a string, a number) is malformed and throws, so the line goes to the
     * reference parser like every other line this scanner does not accept (S-02), and the page path
     * and the full read treat it alike (T-GAP-147: both skip it).
     */
    Map<String, String> optStringMap(byte[] key) {
        int i = find(key);
        if (i < 0 || kind[i] == 'n') {
            return null;
        }
        if (kind[i] != '{') {
            throw new IllegalArgumentException("not an object");
        }
        if (isEmptyObject(valueStart[i], valueEnd[i])) {
            return null; // the common case ("parameters":{}): nothing to allocate
        }
        // One nested scanner per query (not per line) keeps this one's member table intact.
        if (nested == null) {
            nested = new JsonLineScanner();
        }
        if (!nested.scan(buf, valueStart[i], valueEnd[i] - valueStart[i])) {
            throw new IllegalArgumentException("malformed object");
        }
        if (nested.count == 0) {
            return null;
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int m = 0; m < nested.count; m++) {
            String name = nested.decode(nested.keyStart[m], nested.keyEnd[m], true);
            String value;
            if (nested.kind[m] == 'n') {
                value = null;
            } else if (nested.kind[m] == '"' || nested.kind[m] == '\\') {
                value = nested.string(m, false);
            } else {
                value = new String(nested.buf, nested.valueStart[m],
                        nested.valueEnd[m] - nested.valueStart[m], StandardCharsets.UTF_8);
            }
            map.put(name, value);
        }
        return map;
    }

    // ---------------------------------------------------------------- internals

    private String string(int i, boolean shared) {
        if (kind[i] == '"' || kind[i] == '\\') {
            if (kind[i] == '"') {
                return shared ? cached(valueStart[i], valueEnd[i])
                        : new String(buf, valueStart[i], valueEnd[i] - valueStart[i], StandardCharsets.UTF_8);
            }
            return decode(valueStart[i], valueEnd[i], false);
        }
        // A bare number or literal read as a string (json-lib getString does the same).
        return new String(buf, valueStart[i], valueEnd[i] - valueStart[i], StandardCharsets.UTF_8);
    }

    private String cached(int s, int e) {
        int n = e - s;
        if (n > MAX_CACHED_LENGTH) {
            return new String(buf, s, n, StandardCharsets.UTF_8);
        }
        int h = 1;
        for (int p = s; p < e; p++) {
            h = 31 * h + buf[p];
        }
        int slot = (h ^ (h >>> 16)) & (CACHE_SIZE - 1);
        byte[] known = cacheBytes[slot];
        if (known != null && Arrays.equals(known, 0, known.length, buf, s, e)) {
            return cache[slot];
        }
        String value = new String(buf, s, n, StandardCharsets.UTF_8);
        cacheBytes[slot] = Arrays.copyOfRange(buf, s, e);
        cache[slot] = value;
        return value;
    }

    /** Decodes a string body with escapes. */
    private String decode(int s, int e, boolean shared) {
        boolean escaped = false;
        for (int p = s; p < e; p++) {
            if (buf[p] == '\\') {
                escaped = true;
                break;
            }
        }
        if (!escaped) {
            return shared ? cached(s, e) : new String(buf, s, e - s, StandardCharsets.UTF_8);
        }
        String raw = new String(buf, s, e - s, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(raw.length());
        for (int p = 0; p < raw.length(); p++) {
            char c = raw.charAt(p);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (++p >= raw.length()) {
                throw new IllegalArgumentException("dangling escape");
            }
            char x = raw.charAt(p);
            switch (x) {
                case '"', '\\', '/' -> sb.append(x);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (p + 4 >= raw.length()) {
                        throw new IllegalArgumentException("short unicode escape");
                    }
                    sb.append((char) Integer.parseInt(raw.substring(p + 1, p + 5), 16));
                    p += 4;
                }
                default -> throw new IllegalArgumentException("bad escape");
            }
        }
        return sb.toString();
    }

    private void readValue(int i) {
        byte c = peek();
        if (c == '"') {
            pos++;
            int s = pos;
            boolean escapes = skipStringBody();
            valueStart[i] = s;
            valueEnd[i] = pos - 1;
            kind[i] = escapes ? (byte) '\\' : (byte) '"';
        } else if (c == '{' || c == '[') {
            int s = pos;
            skipContainer();
            valueStart[i] = s;
            valueEnd[i] = pos;
            kind[i] = c == '{' ? (byte) '{' : (byte) '0';
        } else {
            int s = pos;
            while (pos < end) {
                byte b = buf[pos];
                if (b == ',' || b == '}' || b == ' ' || b == '\t' || b == '\r' || b == '\n') {
                    break;
                }
                pos++;
            }
            if (pos == s) {
                throw new IllegalStateException("empty value");
            }
            valueStart[i] = s;
            valueEnd[i] = pos;
            kind[i] = pos - s == 4 && buf[s] == 'n' && buf[s + 1] == 'u' && buf[s + 2] == 'l' && buf[s + 3] == 'l'
                    ? (byte) 'n' : (byte) '0';
        }
    }

    /** Skips to just past the closing quote; returns whether an escape occurred. */
    private boolean skipStringBody() {
        boolean escapes = false;
        while (pos < end) {
            byte b = buf[pos++];
            if (b == '\\') {
                escapes = true;
                pos++;
            } else if (b == '"') {
                return escapes;
            }
        }
        throw new IllegalStateException("unterminated string");
    }

    private void skipContainer() {
        int depth = 0;
        while (pos < end) {
            byte b = buf[pos++];
            if (b == '"') {
                skipStringBody();
            } else if (b == '{' || b == '[') {
                depth++;
            } else if (b == '}' || b == ']') {
                depth--;
                if (depth == 0) {
                    return;
                }
            }
        }
        throw new IllegalStateException("unterminated container");
    }

    private boolean trailingWsOnly() {
        skipWs();
        return pos == end;
    }

    private void skipWs() {
        while (pos < end) {
            byte b = buf[pos];
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') {
                return;
            }
            pos++;
        }
    }

    private byte peek() {
        if (pos >= end) {
            throw new IllegalStateException("unexpected end");
        }
        return buf[pos];
    }

    private byte next() {
        byte b = peek();
        pos++;
        return b;
    }

    private void expect(char c) {
        if (next() != c) {
            throw new IllegalStateException("expected " + c);
        }
    }
}
