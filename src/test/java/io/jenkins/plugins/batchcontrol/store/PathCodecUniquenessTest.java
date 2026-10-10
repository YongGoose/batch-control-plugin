package io.jenkins.plugins.batchcontrol.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit rows (no Jenkins) for SPEC item 4, "the file name derived from an item's full name is
 * unique" (#25). Matrix row T-04-05; the integration consequences are T-04-06/07 in
 * {@code StoreFileNameUniquenessTest}.
 *
 * <p>The crafted name is built from the issue's description of the old shortening only
 * (matrix note 62): an encoded name longer than 250 characters became the first 180 encoded
 * characters, a {@code -}, and the upper-case hex SHA-256 of the full name (the case is the one the old version emits). ARCHITECTURE
 * section 5 fixes the rest of the encoding for the characters used here ({@code /} becomes
 * {@code %2F}, letters are URL-safe and stay as they are).
 *
 * Written from docs/SPEC.md, docs/ARCHITECTURE.md section 5, issue #25 and docs/TEST-MATRIX.md
 * only (no src/main knowledge).
 */
@Tag("core")
public class PathCodecUniquenessTest {

    /** Victim: two folders and a leaf, encoded length 100 + 3 + 70 + 3 + 100 = 276 (shortened). */
    public static final String FOLDER_A = "a".repeat(100);
    public static final String FOLDER_B = "b".repeat(70);
    public static final String VICTIM_LEAF = "v".repeat(100);
    public static final String VICTIM = FOLDER_A + "/" + FOLDER_B + "/" + VICTIM_LEAF;

    /** The old shortened form of {@link #VICTIM}, written literally the way issue #25 describes it. */
    public static String legacyShortForm(String fullName) {
        String plainEncoding = fullName.replace("/", "%2F");
        return plainEncoding.substring(0, 180) + "-" + sha256Hex(fullName);
    }

    /**
     * The attacker's full name: the decoded 180-character prefix of the victim's encoding, a
     * {@code -}, and sha256(victim full name). It lives in the victim's own folder (the prefix
     * ends four characters into the victim's leaf), and its own encoding is 245 characters long,
     * so it is not shortened itself.
     */
    public static String attackerFor(String victim) {
        String prefix = victim.replace("/", "%2F").substring(0, 180).replace("%2F", "/");
        return prefix + "-" + sha256Hex(victim);
    }

    public static String sha256Hex(String s) {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /**
     * T-04-05 (#25): the crafted attacker name and the long victim name are two distinct full
     * names and must map to two distinct stored file names; neither may exceed the 250-character
     * budget. A shortened encoding must also never be a string that decodes to some other name
     * whose own encoding is the same string (that is the whole collision class, not just this
     * instance).
     */
    @Test
    public void t_04_05_shortenedEncodingNeverEqualsAPlainEncoding() {
        String attacker = attackerFor(VICTIM);
        // premise: the crafted name is what the issue describes
        assertEquals(FOLDER_A + "/" + FOLDER_B + "/" + "vvvv-" + sha256Hex(VICTIM), attacker, "fixture: the attacker's name is the decoded 180-char prefix + '-' + sha256(victim)");
        assertNotEquals(VICTIM, attacker, "fixture: two distinct full names");
        assertEquals(legacyShortForm(VICTIM), attacker.replace("/", "%2F"), "fixture: the attacker's plain encoding equals the victim's old shortened form");
        assertTrue(attacker.replace("/", "%2F").length() <= 250, "fixture: the attacker's encoding is short enough not to be shortened");

        String victimFile = PathCodec.encode(VICTIM);
        String attackerFile = PathCodec.encode(attacker);
        assertTrue(victimFile.length() <= 250, "the shortened victim name must stay within the file name budget");
        assertTrue(attackerFile.length() <= 250, "the attacker's name must stay within the file name budget");
        assertNotEquals(victimFile, attackerFile, "two distinct full names must never map to the same stored file (#25)");
        assertEquals(victimFile, PathCodec.encode(VICTIM), "the mapping must stay deterministic");

        // The general form: whatever a shortened encoding decodes to (if it decodes at all), that
        // name must not encode back to the same file name unless it is the victim itself.
        String decoded;
        try {
            decoded = PathCodec.decode(victimFile);
        } catch (IllegalArgumentException refused) {
            return; // a shortened form that is not decodable cannot collide with a plain name
        }
        if (!VICTIM.equals(decoded)) {
            assertNotEquals(victimFile, PathCodec.encode(decoded), "a shortened encoding decodes to \"" + decoded + "\", which is a different full name"
                            + " mapping to the same file");
        }
    }
}
