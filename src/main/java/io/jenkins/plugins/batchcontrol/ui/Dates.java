package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DecimalStyle;
import java.util.Locale;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The plugin's only time-rendering vocabulary (U-07). Every timestamp and span shown on a
 * screen goes through these three methods so that the same kind of value never reads two
 * ways on two screens (grant durations are rendered elsewhere):
 *
 * <ul>
 *   <li>a <b>point in time</b> is always absolute, {@code yyyy-MM-dd HH:mm:ss z} in the
 *       plugin clock's zone ({@code BatchClock.clock().getZone()}, read on every call) —
 *       {@link #format};</li>
 *   <li>a <b>length of time</b> (a build's duration) is always a span, {@code 3 min 20 sec} —
 *       {@link #span};</li>
 *   <li>a <b>countdown</b> to a known instant is the same span vocabulary — {@link #until}.</li>
 * </ul>
 *
 * <p>Absolute is the default because these screens are an audit trail: "2026-09-26 14:21:20 KST"
 * is what an operator correlates against a build log or an incident timeline, and it does not
 * change meaning when the page is left open or pasted into a ticket. A span is used only where
 * the value genuinely is a length rather than a moment — a duration, and the remaining life of a
 * grant window, where "12 min 30 sec" is the number the user is acting on and the absolute
 * expiry is shown in the neighbouring column anyway.
 */
@Restricted(NoExternalUse.class)
public final class Dates {

    private static final String PATTERN = "yyyy-MM-dd HH:mm:ss z";

    private Dates() {
    }

    /** @return the formatted timestamp, or an empty string for null (e.g. undecided requests). */
    public static String format(@CheckForNull Instant instant) {
        if (instant == null) {
            return "";
        }
        // Display string: the zone is the plugin clock's, read per call so a test clock or
        // a zone change applies immediately; the zone name follows the viewer's locale, but the
        // digits stay ASCII (DecimalStyle.STANDARD) so the value reads the same everywhere.
        return DateTimeFormatter.ofPattern(PATTERN, displayLocale())
                .withDecimalStyle(DecimalStyle.STANDARD)
                .withZone(BatchClock.clock().getZone())
                .format(instant);
    }

    private static Locale displayLocale() {
        org.kohsuke.stapler.StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        Locale locale = req == null ? null : req.getLocale();
        return locale == null ? Locale.getDefault(Locale.Category.FORMAT) : locale;
    }

    /**
     * A length of time in the one span vocabulary the plugin uses ({@code 3 min 20 sec}).
     *
     * @param millis the length in milliseconds
     * @return the span, or an empty string for a non-positive length
     */
    public static String span(long millis) {
        return millis <= 0 ? "" : Util.getTimeSpanString(millis);
    }

    /**
     * How much time is left until {@code instant}, read off the {@link BatchClock} like every
     * other time read in the plugin.
     *
     * @param instant the deadline, possibly null
     * @return the remaining span in the same vocabulary as {@link #span}, {@code "expired"} once
     *         the deadline has passed, or an empty string when there is no deadline
     */
    public static String until(@CheckForNull Instant instant) {
        if (instant == null) {
            return "";
        }
        long millis = instant.toEpochMilli() - BatchClock.now().toEpochMilli();
        return millis <= 0 ? "expired" : span(millis);
    }
}
