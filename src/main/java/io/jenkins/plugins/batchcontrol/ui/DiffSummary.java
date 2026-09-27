package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * One-line size summary of a stored unified diff, for the change-records screen (U-07).
 *
 * <p>The diff itself was behind a collapsed {@code <details>}, so the single most important fact
 * on a change-control screen — that a configuration change happened and roughly how big it was —
 * cost a click per row. Expanding every row by default is not the answer either: a page holds up
 * to 50 records and a {@code config.xml} diff runs to hundreds of lines, so 50 open diffs is a
 * screen nobody can read. The compromise is that the shape of the change is always visible and
 * only the body stays on demand.
 *
 * <p>Counting is purely textual and matches the format {@code store/UnifiedDiff} emits: a
 * {@code --- before} / {@code +++ after} header, then hunks of {@code @@ … @@}, context lines and
 * {@code +}/{@code -} lines. The two header lines are excluded by length (a bare {@code ---} or
 * {@code +++} is not a content line), and the "diff too large to store" placeholder contains no
 * {@code +}/{@code -} lines at all, so it summarises as empty and the row simply shows the note.
 */
@Restricted(NoExternalUse.class)
public final class DiffSummary {

    private DiffSummary() {
    }

    /**
     * @param diff the stored unified diff, possibly null
     * @return {@code "+12 / -3 lines"}, or an empty string when there is nothing countable
     */
    public static String of(@CheckForNull String diff) {
        if (diff == null || diff.isEmpty()) {
            return "";
        }
        int added = 0;
        int removed = 0;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("+++") || line.startsWith("---")) {
                continue;
            }
            if (line.startsWith("+")) {
                added++;
            } else if (line.startsWith("-")) {
                removed++;
            }
        }
        if (added == 0 && removed == 0) {
            return "";
        }
        return "+" + added + " / -" + removed + " lines";
    }
}
