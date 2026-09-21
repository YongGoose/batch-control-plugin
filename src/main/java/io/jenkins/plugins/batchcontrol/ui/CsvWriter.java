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
 * <p>Security (D-18): any cell whose value starts with {@code =}, {@code +}, {@code -} or
 * {@code @} is prefixed with a single quote so spreadsheet applications never interpret it as a
 * formula, and standard CSV quoting is applied (cells containing comma, quote or line breaks are
 * wrapped in double quotes with inner quotes doubled). Permission checks are the caller's job.
 */
@Restricted(NoExternalUse.class)
public final class CsvWriter {

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
     * Encodes one cell: formula-injection sanitization first (D-18), then CSV quoting.
     * Package-private for direct unit testing.
     */
    static String encode(@CheckForNull Object value) {
        String s = value == null ? "" : String.valueOf(value);
        if (!s.isEmpty()) {
            char first = s.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@') {
                s = "'" + s;
            }
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0
                || s.indexOf('\r') >= 0) {
            s = '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
