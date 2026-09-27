package io.jenkins.plugins.batchcontrol.ui;

import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests (no Jenkins) for the two view helpers U-07 introduced. Matrix rows T-UI-20 (the diff
 * size summary shown on the change-records screen) and T-UI-21 (the one time vocabulary the
 * screens render through).
 *
 * <p>The screen half of the same fix is T-UI-19 in {@code ChangeRecordDiffViewTest}: this class
 * pins the counting and the wording, that one pins that the screen shows them without a click.
 */
public class DiffSummaryAndDatesTest {

    /** The shape {@code store/UnifiedDiff} emits: two header lines, then hunks. */
    private static final String DIFF = "--- before\n"
            + "+++ after\n"
            + "@@ -1,4 +1,5 @@\n"
            + " <project>\n"
            + "-  <description>before</description>\n"
            + "+  <description>after</description>\n"
            + "+  <disabled>false</disabled>\n"
            + " </project>\n";

    /** The placeholder stored instead of an oversized diff (store/UnifiedDiff). */
    private static final String TOO_LARGE =
            "Diff too large to store (5000 lines); the change was recorded without the full diff.";

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-UI-20: the summary counts content lines only. The {@code ---}/{@code +++} header lines of
     * a unified diff start with the same characters as the content lines and must not be counted,
     * the "diff too large" placeholder summarises as empty (there is nothing to count, and the row
     * then shows the placeholder itself), and null and empty input summarise as empty.
     */
    @Test
    public void t_ui_20_diffSummaryCountsContentLinesOnly() {
        assertEquals("+2 / -1 lines", DiffSummary.of(DIFF), "the summary must count the two added and one removed content line and"
                + " exclude the --- / +++ header pair");

        // The same diff without its header lines must summarise identically: that is what makes
        // the exclusion measurable rather than incidental (a summariser that counted the headers
        // would say "+3 / -2 lines" above and "+2 / -1 lines" here).
        assertEquals(DiffSummary.of(DIFF.substring(DIFF.indexOf("@@"))), DiffSummary.of(DIFF), "the header pair must contribute nothing at all to the counts");

        // A diff that is nothing but the header pair therefore has nothing countable.
        assertEquals("", DiffSummary.of("--- before\n+++ after\n"), "a diff of only the header lines must summarise as empty");

        assertEquals("", DiffSummary.of(TOO_LARGE), "the \"diff too large\" placeholder holds no +/- lines and must summarise as"
                + " empty so the row shows the placeholder rather than \"+0 / -0 lines\"");
        assertEquals("", DiffSummary.of(null), "a record with no diff must summarise as empty");
        assertEquals("", DiffSummary.of(""), "an empty diff must summarise as empty");

        // Falsifiability guard: with the counting removed every assertion above still holds,
        // because they all expect an empty string. Pin the non-empty direction too.
        assertNotEquals("", DiffSummary.of(DIFF), "guard: a real diff must produce a non-empty summary");
        assertEquals("+1 / -0 lines", DiffSummary.of("--- a\n+++ b\n@@ @@\n+only added\n"), "an addition-only diff must report the zero side explicitly");
        assertEquals("+0 / -1 lines", DiffSummary.of("--- a\n+++ b\n@@ @@\n-only removed\n"), "a removal-only diff must report the zero side explicitly");
    }

    /**
     * T-UI-21: the time vocabulary's empty and expired cases. A span of zero is not "0 sec" but
     * nothing at all (there is no length to show), a countdown with no deadline renders nothing,
     * and a deadline that has passed renders the expired marker rather than a negative span.
     */
    @Test
    public void t_ui_21_dateHelperEmptyAndExpiredCases() {
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        BatchClock.setForTest(Clock.fixed(now, ZoneOffset.UTC));

        assertEquals("", Dates.span(0), "a zero-length span must render as nothing, not as \"0 sec\"");
        assertEquals("", Dates.span(-1_000), "a negative length must render as nothing");
        assertEquals("", Dates.until(null), "a countdown with no deadline must render as nothing");
        assertEquals("expired", Dates.until(now.minusSeconds(1)), "a deadline one second in the past must render the expired marker");
        assertEquals("expired", Dates.until(now), "a deadline exactly now has no time left and must render the expired marker");
        assertEquals("", Dates.format(null), "an absent timestamp must render as nothing");

        // Falsifiability guards: all of the above are satisfied by helpers that return "" for
        // everything, so pin the live directions as well.
        assertFalse(Dates.span(200_000).isEmpty(), "guard: a real length must render a non-empty span");
        String remaining = Dates.until(now.plusSeconds(750));
        assertFalse(remaining.isEmpty(), "guard: a future deadline must render a non-empty countdown");
        assertNotEquals("expired", remaining, "a future deadline must not render as expired");
        assertEquals(Dates.span(750_000), remaining, "a countdown must use the same span vocabulary as a duration");
        // Rendered in the controller's own zone, so only the date prefix is zone-independent
        // enough to assert on.
        assertTrue(Dates.format(now).startsWith("2026-09-2"), "guard: a real timestamp must render absolutely, was " + Dates.format(now));
    }
}
