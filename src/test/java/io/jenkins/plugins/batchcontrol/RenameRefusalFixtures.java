package io.jenkins.plugins.batchcontrol;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.htmlunit.WebResponse;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The D-71c rename refusal as documented (SPEC item 8 line 169, D-71c, TEST-MATRIX note 266):
 * while change control is on, no permission window allows renaming any job or folder. The refusal
 * text starts "Renaming '&lt;full name&gt;' (&lt;kind&gt;) is not allowed: while change control is
 * on, a permission window does not allow renaming a job or folder"; core's field check
 * ({@code checkNewName}) answers 200 with that error, the rename endpoints ({@code confirmRename},
 * {@code doRename}) answer 400 with that text plus " Nothing was renamed.".
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-71c and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
final class RenameRefusalFixtures {

    static final String REASON = "is not allowed: while change control is on, a permission window does not allow renaming a job or folder";

    private RenameRefusalFixtures() {
    }

    /** The documented start of the D-71c refusal for the item {@code fullName} of kind {@code kind}. */
    static String refusal(String fullName, String kind) {
        return "Renaming '" + fullName + "' (" + kind + ") " + REASON;
    }

    /**
     * {@code POST <path>} as {@code user} with a crumb, redirects not followed. The path is sent as
     * written (percent escapes included), so encoded endpoint names reach Stapler undecoded.
     */
    static WebResponse postPath(JenkinsRule j, String user, String path) throws Exception {
        return ApproverFormFixtures.post(j, user, path, List.of());
    }

    /** The rename was refused as D-71c documents: HTTP 400, the refusal text and "Nothing was renamed.". */
    static void assertWindowRenameRefused(WebResponse response, String fullName, String kind, String what) {
        String text = visible(response.getContentAsString());
        assertEquals(400, response.getStatusCode(), what + " must be refused with HTTP 400: " + ApproverFormFixtures.excerpt(text));
        String expected = refusal(fullName, kind);
        assertTrue(text.contains(expected), what + ": the refusal must say '" + expected + "': " + ApproverFormFixtures.excerpt(text));
        assertTrue(text.contains("Nothing was renamed."), what + ": the refusal must say 'Nothing was renamed.': "
                + ApproverFormFixtures.excerpt(text));
    }

    /** The FormValidation kind of a validation answer ({@code <div class=ok|warning|error>}), or "". */
    static String validationKind(WebResponse r) {
        Matcher m = Pattern.compile("class=[\"']?(ok|warning|error)\\b").matcher(r.getContentAsString());
        return m.find() ? m.group(1) : "";
    }

    /** Visible text of a markup fragment: tags removed, entities decoded, whitespace collapsed. */
    static String visible(String html) {
        String s = html.replaceAll("<[^>]*>", " ")
                .replace("&#039;", "'").replace("&#39;", "'").replace("&apos;", "'").replace("&quot;", "\"").replace("&#34;", "\"")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        return s.replaceAll("\\s+", " ").trim();
    }
}
