package io.jenkins.plugins.batchcontrol.store;

import java.util.List;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Shared CSV building rules for every export (SPEC item 12, D-18). The ui layer builds its
 * rows through {@link #row}/{@link #cell} so the two safety rules are applied once, in the
 * right order, for all four exports:
 *
 * <ol>
 *   <li><b>Formula neutralization (D-18)</b>: a cell VALUE starting with {@code = + - @} gets
 *       a leading apostrophe so a spreadsheet never interprets it as a formula. Applied to the
 *       raw value first, so the apostrophe always sits directly before the payload.</li>
 *   <li><b>RFC 4180 quoting</b>: after neutralization, a cell containing a comma, quote or
 *       line break is wrapped in double quotes with inner quotes doubled.</li>
 * </ol>
 */
@Restricted(NoExternalUse.class)
public final class CsvSupport {

    private CsvSupport() {
    }

    /** D-18: prefixes {@code '} when the value starts with {@code = + - @}; null becomes "". */
    public static String sanitizeCell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            return "'" + value;
        }
        return value;
    }

    /** One finished CSV cell: D-18 neutralization, then RFC 4180 quoting. */
    public static String cell(String value) {
        String sanitized = sanitizeCell(value);
        if (sanitized.indexOf(',') >= 0 || sanitized.indexOf('"') >= 0
                || sanitized.indexOf('\n') >= 0 || sanitized.indexOf('\r') >= 0) {
            return '"' + sanitized.replace("\"", "\"\"") + '"';
        }
        return sanitized;
    }

    /** One CSV line (without the terminator) from raw cell values. */
    public static String row(String... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cell(values[i]));
        }
        return sb.toString();
    }

    /** {@link #row(String...)} for list-shaped rows. */
    public static String row(List<String> values) {
        return row(values.toArray(new String[0]));
    }
}
