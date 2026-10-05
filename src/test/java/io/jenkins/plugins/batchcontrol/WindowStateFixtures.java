package io.jenkins.plugins.batchcontrol;

import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.htmlunit.html.DomAttr;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.jvnet.hudson.test.JenkinsRule;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared reading of whether a permission window still applies to its item (D-71a, D-71b), from
 * the screens and from the stored grant file, for the rows of note 264.
 *
 * <p>The screen contract (documented by ui-dev): in the grants page's Active list
 * ({@code table[data-batch-control-list=active]}) each window's row links to its detail page
 * {@code batch-control/grants/<id>/} and its Remaining cell carries
 * {@code data-batch-control-window-state="bound"} (the time left) or {@code ="unbound"} with
 * exactly the text {@link #UNBOUND_TEXT}; on the detail page the Permission Window table's State
 * cell carries the same marker ("Open, N min left" when bound); an ended window has no marker.
 *
 * <p>The stored form (ARCHITECTURE section 5): a grant file ({@code batch-control/grants/<id>.xml})
 * carries {@code itemIdentity}, recorded at approval; absent, the grant is bound to nothing, and it
 * is cleared when an item event ends the binding.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-71a/D-71b, docs/ARCHITECTURE.md sections 4 and 5
 * and the ui-dev screen contract only (no src/main knowledge).
 */
final class WindowStateFixtures {

    static final String STATE = "data-batch-control-window-state";
    static final String UNBOUND_TEXT = "No longer applies (the item was renamed, moved or deleted)";
    static final String ACTIVE_LIST = "table[data-batch-control-list=active]";
    private static final Pattern LIST_TIME_LEFT = Pattern.compile("\\d+\\s*min");
    private static final Pattern DETAIL_OPEN = Pattern.compile("^Open, .*\\d.* left$");
    /** An {@code itemIdentity} element with some content (the binding recorded at approval). */
    private static final Pattern STORED_IDENTITY = Pattern.compile("<itemIdentity(?:\\s[^>]*)?>\\s*[^<\\s]");

    private WindowStateFixtures() {
        // utility class
    }

    /** The id of {@code user}'s single active window naming {@code fullName} (asserted unique). */
    static String windowId(String user, String fullName) {
        List<Grant> found = GrantService.get().listActive().stream()
                .filter(g -> user.equals(g.getUser()) && fullName.equals(g.getScope().getFullName()))
                .collect(Collectors.toList());
        assertEquals(1, found.size(), "fixture: " + user + " must hold exactly one active window on " + fullName + ", got " + found);
        return found.get(0).getId();
    }

    /**
     * The Active list's row (tr) that links to {@code batch-control/grants/<grantId>/}, or null
     * (also when the page shows no Active table because no window is active).
     */
    static DomElement activeRow(JenkinsRule j, HtmlPage list, String grantId) throws Exception {
        DomNode table = list.querySelector(ACTIVE_LIST);
        if (table == null) {
            return null;
        }
        String detailPath = new URL(j.getURL(), "batch-control/grants/" + grantId + "/").getPath();
        DomElement found = null;
        for (DomNode n : table.querySelectorAll("tr")) {
            DomElement row = (DomElement) n;
            for (DomElement a : row.getElementsByTagName("a")) {
                if (!a.hasAttribute("href") || a.getAttribute("href").startsWith("#")) {
                    continue;
                }
                if (list.getFullyQualifiedUrl(a.getAttribute("href")).getPath().equals(detailPath)) {
                    assertTrue(found == null || found == row, "the Active list must hold one row for the window " + grantId);
                    found = row;
                }
            }
        }
        return found;
    }

    /** The elements carrying the window state marker inside {@code scope}. */
    private static List<DomElement> markers(DomNode scope) {
        List<DomElement> out = new ArrayList<>();
        for (DomNode n : scope.querySelectorAll("[" + STATE + "]")) {
            out.add((DomElement) n);
        }
        return out;
    }

    /** The detail page of the window, opened by {@code viewer}; it must answer 200. */
    static HtmlPage detailPage(JenkinsRule j, String viewer, String grantId) throws Exception {
        HtmlPage detail = UsabilityFixtures.htmlPage(j, viewer, "batch-control/grants/" + grantId + "/");
        assertEquals(200, detail.getWebResponse().getStatusCode(), viewer + " must open the detail page of the window " + grantId);
        return detail;
    }

    /**
     * The window {@code grantId} is shown as no longer applying: its Active list row (seen by
     * {@code viewer}) and its detail page each carry exactly one state marker, {@code unbound}, whose
     * text is exactly {@link #UNBOUND_TEXT}.
     */
    static void assertShownUnbound(JenkinsRule j, String viewer, String grantId, String what) throws Exception {
        HtmlPage list = UsabilityFixtures.htmlPage(j, viewer, "batch-control/grants/");
        DomElement row = activeRow(j, list, grantId);
        assertNotNull(row, what + ": the window must still be listed among the active windows (it has not ended): "
                + excerpt(list.asNormalizedText()));
        assertSingleMarker(row, "unbound", what + " (Active list row)");
        assertEquals(UNBOUND_TEXT, markers(row).get(0).asNormalizedText().trim(), what + ": the Active list must say the window no longer applies");

        DomNode main = mainPanel(detailPage(j, viewer, grantId));
        assertSingleMarker(main, "unbound", what + " (detail page)");
        assertEquals(UNBOUND_TEXT, markers(main).get(0).asNormalizedText().trim(), what + ": the detail page must say the window no longer applies");
    }

    /**
     * The window {@code grantId} is shown as applying: its Active list row carries one marker,
     * {@code bound}, showing the time left; its detail page carries one marker, {@code bound},
     * reading "Open, N min left". Neither says it no longer applies.
     */
    static void assertShownBound(JenkinsRule j, String viewer, String grantId, String what) throws Exception {
        HtmlPage list = UsabilityFixtures.htmlPage(j, viewer, "batch-control/grants/");
        DomElement row = activeRow(j, list, grantId);
        assertNotNull(row, what + ": the window must be listed among the active windows: " + excerpt(list.asNormalizedText()));
        assertSingleMarker(row, "bound", what + " (Active list row)");
        String listText = markers(row).get(0).asNormalizedText().trim();
        assertTrue(LIST_TIME_LEFT.matcher(listText).find(), what + ": the Active list must show the time left, was '" + listText + "'");
        assertFalse(row.asNormalizedText().contains("No longer applies"), what + ": a bound window must not be shown as no longer applying");

        DomNode main = mainPanel(detailPage(j, viewer, grantId));
        assertSingleMarker(main, "bound", what + " (detail page)");
        String detailText = markers(main).get(0).asNormalizedText().trim();
        assertTrue(DETAIL_OPEN.matcher(detailText).matches(), what + ": the detail page must read 'Open, N min left', was '" + detailText + "'");
        assertFalse(main.asNormalizedText().contains("No longer applies"), what + ": a bound window's page must not say it no longer applies");
    }

    /** An ended window: no Active list row and no state marker on its detail page. */
    static void assertShownEnded(JenkinsRule j, String viewer, String grantId, String what) throws Exception {
        HtmlPage list = UsabilityFixtures.htmlPage(j, viewer, "batch-control/grants/");
        assertNull(activeRow(j, list, grantId), what + ": an ended window must not be listed among the active windows");
        DomNode main = mainPanel(detailPage(j, viewer, grantId));
        assertTrue(markers(main).isEmpty(), what + ": an ended window's detail page carries no window state marker, found "
                + markers(main).stream().map(e -> e.getAttribute(STATE) + ":" + e.asNormalizedText()).collect(Collectors.toList()));
    }

    private static void assertSingleMarker(DomNode scope, String expected, String what) {
        List<DomElement> found = markers(scope);
        assertEquals(1, found.size(), what + ": exactly one " + STATE + " marker expected, found "
                + found.stream().map(e -> e.getAttribute(STATE) + ":" + e.asNormalizedText()).collect(Collectors.toList()));
        assertEquals(expected, found.get(0).getAttribute(STATE), what + ": window state");
    }

    private static DomNode mainPanel(HtmlPage page) {
        DomNode main = page.querySelector("#main-panel");
        return main == null ? page : main;
    }

    /** Revoke controls (href, action, formaction or data-* URL whose path ends in {@code /revoke}) inside {@code scope}. */
    static List<String> revokeControls(HtmlPage page, DomNode scope) {
        List<String> out = new ArrayList<>();
        for (DomNode n : scope.querySelectorAll("*")) {
            DomElement e = (DomElement) n;
            for (DomAttr attr : e.getAttributesMap().values()) {
                String name = attr.getName();
                if (!"href".equals(name) && !"action".equals(name) && !"formaction".equals(name) && !name.startsWith("data-")) {
                    continue;
                }
                String v = attr.getValue().trim();
                if (v.isEmpty() || v.contains(" ")) {
                    continue;
                }
                try {
                    String path = page.getFullyQualifiedUrl(v).getPath().replaceAll("/+$", "");
                    if (path.endsWith("/revoke")) {
                        out.add(path);
                    }
                } catch (java.net.MalformedURLException ex) {
                    // not a URL
                }
            }
        }
        return out;
    }

    /** The stored grant file ({@code batch-control/grants/<id>.xml}, ARCHITECTURE 5). */
    static String storedGrant(JenkinsRule j, String grantId) throws Exception {
        Path file = j.jenkins.getRootDir().toPath().resolve("batch-control/grants/" + grantId + ".xml");
        assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): the window is stored at " + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** Whether the stored grant still records the item's identity (ARCHITECTURE 5 {@code itemIdentity}). */
    static boolean storedBinding(JenkinsRule j, String grantId) throws Exception {
        return STORED_IDENTITY.matcher(storedGrant(j, grantId)).find();
    }
}
