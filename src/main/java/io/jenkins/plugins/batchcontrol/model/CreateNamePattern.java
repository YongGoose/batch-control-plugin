package io.jenkins.plugins.batchcontrol.model;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Failure;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The optional name restriction of a CREATE grant (D-40): either an exact item name, or a Java
 * regular expression written as {@code /regex/} that must match the whole item name. Only the item
 * name is ever matched, never the full name.
 */
@Restricted(NoExternalUse.class)
public final class CreateNamePattern {

    /** Upper bound on the stored restriction, so a request cannot carry an unbounded regex. */
    public static final int MAX_LENGTH = 1000;

    private final String source;
    @CheckForNull
    private final Pattern regex;

    private CreateNamePattern(String source, @CheckForNull Pattern regex) {
        this.source = source;
        this.regex = regex;
    }

    /**
     * Normalizes user input: {@code null} for an absent or blank restriction, otherwise the trimmed
     * text.
     */
    @CheckForNull
    public static String normalize(@CheckForNull String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        return text.trim();
    }

    /** Whether the text is written in the {@code /regex/} form. */
    static boolean isRegexForm(String text) {
        return text.length() >= 2 && text.startsWith("/") && text.endsWith("/");
    }

    /**
     * Validates and compiles a restriction at submission time.
     *
     * @param text the normalized restriction (see {@link #normalize}); must not be {@code null}
     * @throws IllegalArgumentException if the text is too long, the regex does not compile, or an
     *         exact name is not a valid item name
     */
    public static CreateNamePattern parse(String text) {
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("The name restriction must not exceed "
                    + MAX_LENGTH + " characters.");
        }
        if (isRegexForm(text)) {
            String body = text.substring(1, text.length() - 1);
            if (body.isEmpty()) {
                throw new IllegalArgumentException("The name restriction regular expression is empty.");
            }
            try {
                return new CreateNamePattern(text, Pattern.compile(body));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("The name restriction is not a valid regular "
                        + "expression: " + e.getDescription(), e);
            }
        }
        try {
            Jenkins.checkGoodName(text);
        } catch (Failure e) {
            throw new IllegalArgumentException("The name restriction is not a valid item name: "
                    + e.getMessage(), e);
        }
        return new CreateNamePattern(text, null);
    }

    /**
     * Compiles a stored restriction for matching; a stored value that no longer parses matches
     * nothing (fail-safe), represented by a pattern that never matches.
     */
    public static CreateNamePattern forStored(String text) {
        try {
            return parse(text);
        } catch (IllegalArgumentException e) {
            return new CreateNamePattern(text, Pattern.compile("(?!)"));
        }
    }

    /** Whether the new item's name (not its full name) satisfies the restriction. */
    public boolean matches(@CheckForNull String itemName) {
        if (itemName == null) {
            return false;
        }
        String name = itemName.trim();
        return regex != null ? regex.matcher(name).matches() : source.equals(name);
    }

    public String getSource() {
        return source;
    }

    @Override
    public String toString() {
        return source;
    }
}
