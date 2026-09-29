package io.jenkins.plugins.batchcontrol.model;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Failure;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
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

    private static final Logger LOGGER = Logger.getLogger(CreateNamePattern.class.getName());

    /** Upper bound on the stored restriction, so a request cannot carry an unbounded regex. */
    public static final int MAX_LENGTH = 1000;

    /** D-40a (security-08 S-03): a longer new name never matches a restriction. */
    public static final int MAX_NAME_LENGTH = 255;

    /** D-40a (security-08 S-03): how long one regular-expression match may run before it fails. */
    static final long MATCH_DEADLINE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

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

    /**
     * Plain-text description of a stored restriction for an explanation to the holder
     * (e2e-03 DEF-19), for example {@code names matching the pattern /app-[0-9]+/} or
     * {@code the name 'app-2'}.
     */
    public static String describe(String text) {
        return isRegexForm(text) ? "names matching the pattern " + text : "the name '" + text + "'";
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
        if (text.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("An exact name restriction must not exceed "
                    + MAX_NAME_LENGTH + " characters.");
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

    /**
     * Whether the new item's name (not its full name) satisfies the restriction. The name is
     * matched exactly as submitted, untrimmed (S-06). A name over {@value #MAX_NAME_LENGTH}
     * characters never matches, and a regular-expression match that runs longer than the deadline
     * fails (S-03), so no pattern can stall the calling thread.
     */
    public boolean matches(@CheckForNull String itemName) {
        if (itemName == null || itemName.isEmpty() || itemName.length() > MAX_NAME_LENGTH) {
            return false;
        }
        if (regex == null) {
            return source.equals(itemName);
        }
        try {
            return regex.matcher(new DeadlineCharSequence(itemName,
                    System.nanoTime() + MATCH_DEADLINE_NANOS)).matches();
        } catch (MatchTimeout e) {
            LOGGER.warning(() -> "The name restriction " + source + " took longer than "
                    + TimeUnit.NANOSECONDS.toMillis(MATCH_DEADLINE_NANOS) + " ms on a name of "
                    + itemName.length() + " characters; treated as no match");
            return false;
        }
    }

    /** Thrown by {@link DeadlineCharSequence} when a match overruns its deadline. */
    private static final class MatchTimeout extends RuntimeException {
        private static final long serialVersionUID = 1L;

        MatchTimeout() {
            super("regular expression match deadline exceeded", null, false, false);
        }
    }

    /**
     * A character sequence that fails the regex engine once a deadline has passed. The engine reads
     * characters continually while backtracking, so every read checks elapsed monotonic time (a
     * duration bound, not a wall-clock judgement, so it does not use the plugin clock).
     */
    private static final class DeadlineCharSequence implements CharSequence {
        private final String text;
        private final long deadline;

        DeadlineCharSequence(String text, long deadline) {
            this.text = text;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() - deadline > 0) {
                throw new MatchTimeout();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(text.substring(start, end), deadline);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    public String getSource() {
        return source;
    }

    @Override
    public String toString() {
        return source;
    }
}
