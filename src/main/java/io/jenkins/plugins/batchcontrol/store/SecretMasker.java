package io.jenkins.plugins.batchcontrol.store;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Masks secret material in config XML text BEFORE it is diffed or stored (SPEC item 9:
 * "unified diff with secrets masked"). Both diff sides must be masked with the same rules so a
 * secret can never leak through either side of a hunk.
 *
 * <p>Two rules:
 * <ol>
 *   <li>the text content of any element whose tag name contains {@code password}, {@code secret},
 *       {@code token} (covers {@code apiToken}) or {@code passphrase}, case-insensitively;</li>
 *   <li>anything shaped like an encrypted Jenkins {@code Secret} payload
 *       ({@code {AQ<base64>}}), wherever it appears.</li>
 * </ol>
 */
@Restricted(NoExternalUse.class)
public final class SecretMasker {

    public static final String MASK = "********";

    /**
     * {@code <tagWithSecretWord>content</sameTag>} where the tag name contains one of the
     * secret keywords. Only non-empty content is masked (an empty element carries nothing).
     */
    private static final Pattern SECRET_ELEMENT = Pattern.compile(
            "<([A-Za-z0-9_.\\-]*(?:password|secret|token|passphrase)[A-Za-z0-9_.\\-]*)>([^<]+)</\\1>",
            Pattern.CASE_INSENSITIVE);

    /** Encrypted {@code hudson.util.Secret} payloads: {@code {AQAAABAAAA...==}}. */
    private static final Pattern ENCRYPTED_SECRET = Pattern.compile(
            "\\{AQ[A-Za-z0-9+/=]{8,}\\}");

    private SecretMasker() {
    }

    /** Returns the text with every secret occurrence replaced by {@value #MASK}. */
    public static String mask(String text) {
        Objects.requireNonNull(text, "text");
        String masked = ENCRYPTED_SECRET.matcher(text).replaceAll(MASK);
        Matcher elements = SECRET_ELEMENT.matcher(masked);
        StringBuilder sb = new StringBuilder(masked.length());
        while (elements.find()) {
            elements.appendReplacement(sb,
                    "<" + Matcher.quoteReplacement(elements.group(1)) + ">" + MASK
                            + "</" + Matcher.quoteReplacement(elements.group(1)) + ">");
        }
        elements.appendTail(sb);
        return sb.toString();
    }
}
