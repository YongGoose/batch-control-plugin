package io.jenkins.plugins.batchcontrol.store;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Coverage lane 3, scenario L3-11 (unit, no Jenkins): malformed escapes are refused on decoding, and
 * every encoding of names built from {@code a} and {@code é} is well formed and unique. Matrix row
 * T-GAP-340 (note 279).
 *
 * <p>Basis: ARCHITECTURE 5 "잡 이름 인코딩: {@code /} → {@code %2F}, 기타 URL-safe 인코딩. 디코딩 시 경로 탈출(..)
 * 검증" and "A shortened item file name is {@code prefix~sha256}; encode writes {@code ~} as {@code %7E}";
 * SPEC 4 line 74 "the file name derived from an item's full name is unique: no two distinct full names map
 * to the same stored file, including names long enough to be shortened". The existing decode refusal
 * ({@code decode("../x")} throws IllegalArgumentException, T-SEC-04) is the pattern for refusing input that
 * is not a valid encoding.
 *
 * <p>Written from docs/SPEC.md item 4 and docs/ARCHITECTURE.md section 5 only (no src/main knowledge).
 */
public class PathCodecGapTest {

    private static final Pattern PERCENT = Pattern.compile("%(?![0-9A-Fa-f]{2})");

    /**
     * T-GAP-340 (L3-11; ARCHITECTURE 5, SPEC 4 line 74): {@code decode("abc%4")} (an escape cut short) and
     * {@code decode("%zz")} (an escape that is not hexadecimal) throw IllegalArgumentException; for every
     * length 1 .. 400 and several names of that length built from {@code a} and {@code é} (all {@code a},
     * all {@code é}, alternating, and one {@code é} at the start, the middle and the end), no encoding holds
     * a {@code %} that is not followed by two hexadecimal digits, and no two different names share an
     * encoding. Guard: {@code decode} of a well-formed escape gives the character back.
     */
    @Test
    public void t_gap_340_malformedEscapesAreRefusedAndEncodingsStayWellFormedAndUnique() {
        assertThrows(IllegalArgumentException.class, () -> PathCodec.decode("abc%4"), "an escape cut short is refused");
        assertThrows(IllegalArgumentException.class, () -> PathCodec.decode("%zz"), "a non-hexadecimal escape is refused");
        assertEquals("a/b", PathCodec.decode(PathCodec.encode("a/b")), "guard: a well-formed encoding decodes back");

        Map<String, String> seen = new HashMap<>();
        for (int length = 1; length <= 400; length++) {
            for (String name : namesOf(length)) {
                String encoded = PathCodec.encode(name);
                Matcher bad = PERCENT.matcher(encoded);
                assertFalse(bad.find(), "the encoding of a " + length + "-character name holds a '%' without two hex digits at "
                        + (bad.find(0) ? bad.start() : -1) + ": " + encoded);
                String other = seen.put(encoded, name);
                if (other != null && !other.equals(name)) {
                    fail("SPEC 4 line 74: two different names (" + describe(other) + " and " + describe(name) + ") encode alike: " + encoded);
                }
            }
        }
    }

    private static String[] namesOf(int length) {
        StringBuilder alternating = new StringBuilder();
        for (int i = 0; i < length; i++) {
            alternating.append(i % 2 == 0 ? 'a' : 'é');
        }
        String as = "a".repeat(length);
        return new String[] {
            as,
            "é".repeat(length),
            alternating.toString(),
            replaceAt(as, 0),
            replaceAt(as, length / 2),
            replaceAt(as, length - 1)
        };
    }

    private static String replaceAt(String s, int at) {
        return s.substring(0, at) + 'é' + s.substring(at + 1);
    }

    private static String describe(String name) {
        return name == null ? "null" : name.length() + " chars, é at " + name.indexOf('é');
    }
}
