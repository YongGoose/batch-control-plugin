package io.jenkins.plugins.batchcontrol.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.Locale;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-72b (2), security-35 S-35-03: the characters a stored text may contain. Batch Control's
 * files are XML 1.0, which cannot carry the control characters U+0000 to U+001F other than tab,
 * line feed and carriage return, the non-characters U+FFFE and U+FFFF, or half of a surrogate
 * pair. Such a character either makes the save fail (U+0000, U+FFFE, U+FFFF, a lone surrogate) or
 * does not survive a reload (the other control characters), so a text holding one is refused
 * before anything is stored.
 */
@Restricted(NoExternalUse.class)
public final class XmlChars {

    private XmlChars() {
    }

    /**
     * The index of the first character of {@code text} that XML 1.0 cannot store, or {@code -1}
     * when there is none ({@code null} has none).
     */
    public static int firstInvalid(@CheckForNull CharSequence text) {
        if (text == null) {
            return -1;
        }
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c < 0x20) {
                if (c != '\t' && c != '\n' && c != '\r') {
                    return i;
                }
            } else if (c == '￾' || c == '￿') {
                return i;
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
                    i++; // a complete pair: one supplementary character
                } else {
                    return i;
                }
            } else if (Character.isLowSurrogate(c)) {
                return i;
            }
        }
        return -1;
    }

    /** Whether {@code text} holds only characters XML 1.0 can store. */
    public static boolean isStorable(@CheckForNull CharSequence text) {
        return firstInvalid(text) < 0;
    }

    /** The character at {@code index} of {@code text} as {@code U+XXXX}, for a refusal message. */
    public static String describe(CharSequence text, int index) {
        return String.format(Locale.ROOT, "U+%04X", (int) text.charAt(index));
    }
}
