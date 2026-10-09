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

    /**
     * R1-01: refuses a user-entered text that holds a character XML 1.0 cannot store, before
     * anything is stored. The message is the one the run request form already uses:
     * {@code "The <field> contains a character that cannot be stored (U+000B); remove it and submit
     * again."}. {@code null} and storable texts pass. The web layer answers the
     * {@link IllegalArgumentException} with a refusal on the form (HTTP 400).
     *
     * @param field what the text is, as the message names it ({@code "comment"}, {@code "reason"})
     */
    public static void requireStorable(@CheckForNull String text, String field) {
        int bad = firstInvalid(text);
        if (bad >= 0) {
            throw new IllegalArgumentException("The " + field + " contains a character that cannot be stored ("
                    + describe(text, bad) + "); remove it and submit again.");
        }
    }

    /**
     * R1-01: {@code text} without the characters XML 1.0 cannot store. For system text taken from
     * outside the plugin (a console line, a build's parameter value) and kept for display only;
     * every other character is kept as it is. {@code null} stays {@code null}.
     */
    @CheckForNull
    public static String removeInvalid(@CheckForNull String text) {
        int bad = firstInvalid(text);
        if (bad < 0) {
            return text;
        }
        int length = text.length();
        StringBuilder out = new StringBuilder(length);
        out.append(text, 0, bad);
        for (int i = bad; i < length; i++) {
            char c = text.charAt(i);
            if (c < 0x20) {
                if (c == '\t' || c == '\n' || c == '\r') {
                    out.append(c);
                }
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
                    out.append(c).append(text.charAt(i + 1));
                    i++; // a complete pair: one supplementary character
                }
            } else if (c != '￾' && c != '￿' && !Character.isLowSurrogate(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
