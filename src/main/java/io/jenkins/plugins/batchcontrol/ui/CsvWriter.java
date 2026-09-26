package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.IOException;
import java.io.PrintWriter;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * Minimal CSV emitter for the audit export endpoints (SPEC item 12).
 *
 * <p>Two independent transformations are applied to every cell, in this order, and they must not
 * be confused with each other:
 *
 * <ol>
 *   <li><b>Formula-injection sanitisation (D-18, S-22).</b> A cell whose first non-whitespace
 *       character is {@code =}, {@code +}, {@code -} or {@code @} is prefixed with a single quote,
 *       so no spreadsheet application evaluates it. The prefix is the <em>only</em> defence
 *       here.</li>
 *   <li><b>RFC 4180 quoting.</b> A cell containing a comma, a double quote or a line break is
 *       wrapped in double quotes with inner quotes doubled. This is a <em>delimiter</em> concern
 *       and is <b>not</b> a formula defence: a spreadsheet strips the quotes before looking at the
 *       value, so a quoted-but-unprefixed {@code \r=1+1} is still evaluated. Sanitisation
 *       therefore runs first, which also puts the prefix inside the quotes where it belongs.</li>
 * </ol>
 *
 * <p>Permission checks are the caller's job.
 */
@Restricted(NoExternalUse.class)
public final class CsvWriter {

    /** Characters a spreadsheet treats as the start of a formula. */
    private static final String FORMULA_STARTERS = "=+-@";

    private final PrintWriter out;

    private CsvWriter(PrintWriter out) {
        this.out = out;
    }

    /**
     * Sets the CSV content type and attachment disposition on the response and returns a writer.
     *
     * @param filename download name; must be a constant, caller-controlled name (never user input)
     */
    public static CsvWriter open(StaplerResponse2 rsp, String filename) throws IOException {
        rsp.setContentType("text/csv;charset=UTF-8");
        rsp.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        return new CsvWriter(rsp.getWriter());
    }

    /** Writes one row; each cell is sanitized and quoted as needed. Nulls become empty cells. */
    public void row(Object... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(encode(cells[i]));
        }
        sb.append("\r\n");
        out.print(sb);
    }

    /**
     * Encodes one cell: formula-injection sanitisation first (D-18, S-22), then CSV quoting.
     * Package-private for direct unit testing.
     */
    static String encode(@CheckForNull Object value) {
        String s = value == null ? "" : String.valueOf(value);
        if (startsFormula(s)) {
            s = "'" + s;
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0
                || s.indexOf('\r') >= 0) {
            s = '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    /**
     * Whether a spreadsheet would read this cell as a formula.
     *
     * <p>S-22: the test is on the first <b>non-whitespace</b> character, not the first character.
     * Leading whitespace does not stop Excel or Google Sheets evaluating what follows it, and a
     * leading TAB or CR is the vector that actually reaches these exports:
     *
     * <ul>
     *   <li>{@code decisionComment} is not trimmed anywhere on its path to {@code requests.csv} —
     *       {@code action/RequestItem} passes the approver's raw {@code @QueryParameter} through
     *       and {@code policy/RunRequestService} only tests {@code trim().isEmpty()} before
     *       storing the original — so a {@code BatchControl/Approve} holder controls this cell's
     *       leading bytes exactly.</li>
     *   <li>a job full name may begin with a <b>space</b>: {@code Jenkins.checkGoodName} rejects
     *       ISO control codes and its own unsafe-character set (which does contain {@code @}) but
     *       not a leading space, and it does not reject {@code =}, {@code +} or {@code -} either.
     *       That reaches {@code jobFullName} in three exports and {@code target} in
     *       {@code changes.csv}.</li>
     *   <li>{@code reason} is trimmed today, but only by its one UI caller
     *       ({@code action/JobRequestAction}) rather than by the service that stores it, so the
     *       defence would not survive a second entry point.</li>
     * </ul>
     *
     * <p>The prefix is added in front of the whitespace rather than replacing it: these are audit
     * exports, so the stored value is preserved byte for byte and only made inert.
     *
     * @return true when the first non-whitespace character is one of {@value #FORMULA_STARTERS};
     *         false for an empty or all-whitespace cell
     */
    private static boolean startsFormula(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            return FORMULA_STARTERS.indexOf(c) >= 0;
        }
        return false;
    }
}
