package io.jenkins.plugins.batchcontrol.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests (no Jenkins) for the CSV cell encoder. Matrix rows T-SEC-27, T-SEC-28 and T-SEC-29
 * (security-03 finding S-22: the formula guard tested the first character rather than the first
 * <em>non-whitespace</em> character, so a cell beginning with a TAB, CR, LF or space reached a
 * spreadsheet as a live formula).
 *
 * <p>This class lives in the {@code ...batchcontrol.ui} package because the encoder is exposed
 * package-privately for exactly this kind of direct test; the end-to-end halves of the same
 * finding are T-SEC-30 and T-SEC-31 in {@code CsvWhitespaceFormulaExportTest}.
 *
 * <p>The two mechanisms are deliberately measured apart (matrix note 46): the {@code '} prefix is
 * formula neutralisation and the surrounding quotes are RFC 4180 field quoting. They are
 * triggered by different characters, so a test that conflates them would pass for the wrong
 * reason — {@code "\t=1+1"} carries no CR, LF, comma or quote and must therefore come out
 * prefixed and <em>unquoted</em>.
 */
public class CsvFormulaPrefixTest {

    /**
     * T-SEC-27: every cell whose first non-whitespace character starts a formula is prefixed with
     * an apostrophe, the prefix sits at the very start of the cell value (inside the quotes when
     * the cell is quoted at all), and the original bytes are preserved after it — these are audit
     * exports, so the leading whitespace is kept rather than stripped.
     */
    @Test
    public void t_sec_27_whitespaceLedFormulaCellsAreNeutralised() {
        String[] payloads = {
                "\t=1+1",
                "\r=1+1",
                "\n=1+1",
                " =1+1",
                "  \t -2+3",
                "\t@SUM(1,2)",
                "\t+cmd|'/C calc'!A0"};

        for (String payload : payloads) {
            String encoded = CsvWriter.encode(payload);
            String cell = unquote(encoded);
            assertFalse(cell.isEmpty(), describe(payload) + ": the encoded cell must not be empty");
            assertEquals('\'', cell.charAt(0), describe(payload) + ": the cell must start with the neutralising apostrophe,"
                    + " but was " + describe(encoded));
            assertEquals("'" + payload, cell, describe(payload) + ": the prefix must be added in front of the original"
                    + " value, byte for byte (an audit export may not rewrite the recorded"
                    + " value), but was " + describe(encoded));
        }

        // Prefixing and RFC 4180 quoting are independent: this payload has no CR, LF, comma or
        // double quote, so it must be prefixed and NOT quoted. A test that accepted a quoted
        // result here would also pass for an encoder that quotes every cell it prefixes.
        assertEquals("'\t=1+1", CsvWriter.encode("\t=1+1"), "a TAB-led formula cell needs no field quoting: the apostrophe alone must"
                + " be the whole change");
    }

    /**
     * T-SEC-28: where the cell does need quoting, the apostrophe goes <em>inside</em> the quotes.
     * A prefix placed outside them would leave the formula as the first character a spreadsheet
     * sees when it unquotes the field.
     */
    @Test
    public void t_sec_28_apostropheIsInsideTheFieldQuotes() {
        String encoded = CsvWriter.encode("\r=1+1");

        assertEquals('"', encoded.charAt(0), "a CR-bearing cell must be field-quoted, but was " + describe(encoded));
        assertEquals('\'', encoded.charAt(1), "the first character inside the quotes must be the apostrophe, but the cell"
                + " was " + describe(encoded));
        assertEquals("\"'\r=1+1\"", encoded, "the whole cell must be the quoted, prefixed original, but was "
                + describe(encoded));

        // The same for the other two payloads that force quoting, so this row is not pinned to
        // one character: LF, and a comma inside the formula itself.
        assertEquals("\"'\n=1+1\"", CsvWriter.encode("\n=1+1"), "an LF-bearing formula cell must be quoted with the apostrophe inside");
        assertEquals("\"'\t@SUM(1,2)\"", CsvWriter.encode("\t@SUM(1,2)"), "a comma-bearing formula cell must be quoted with the apostrophe inside");
    }

    /**
     * T-SEC-29: nothing else gains a prefix. The case that matters most is the all-whitespace
     * cell: {@code startsFormula} walks past whitespace looking for the first real character, and
     * an implementation that ran off the end of the string and prefixed anyway would corrupt
     * every blank cell in every export.
     */
    @Test
    public void t_sec_29_nonFormulaCellsAreLeftAlone() {
        String[] untouched = {"x", "", "   ", "\t\thello", "a=1+1"};

        for (String value : untouched) {
            String encoded = CsvWriter.encode(value);
            assertEquals(value, encoded, describe(value) + ": a cell that is not a formula must be passed through"
                    + " unchanged, but was " + describe(encoded));
            assertFalse(encoded.startsWith("'"), describe(value) + ": no apostrophe may be added");
        }

        // Falsifiability guard: with the neutralisation removed altogether every assertion above
        // still holds, so pin that the encoder does prefix the cases it is for.
        assertEquals("'=1+1", CsvWriter.encode("=1+1"), "the plain formula cell must still be neutralised (guard: without this the"
                + " negative cases above would pass against an encoder that prefixes nothing)");
        assertNotEquals(" =1+1", CsvWriter.encode(" =1+1"), "a space-led formula cell must not be passed through unchanged");
    }

    // ---------------------------------------------------------------- helpers

    /** Strips RFC 4180 field quoting, so an assertion can talk about the cell's own value. */
    private static String unquote(String encoded) {
        if (encoded.length() >= 2 && encoded.charAt(0) == '"'
                && encoded.charAt(encoded.length() - 1) == '"') {
            return encoded.substring(1, encoded.length() - 1).replace("\"\"", "\"");
        }
        return encoded;
    }

    /** Renders control characters visibly, so a failure message can be read. */
    private static String describe(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
