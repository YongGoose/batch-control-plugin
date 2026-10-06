package io.jenkins.plugins.batchcontrol;

import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.htmlunit.html.DomAttr;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.jvnet.hudson.test.JenkinsRule;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared reading of a permission window's state for the rows of notes 264 and 270: whether it is
 * active and on which item, or whether it has ended, read from the service and from the grants
 * page's lists (D-66), never from the window state marker.
 *
 * <p>D-74 (SPEC item 8 line 170, ARCHITECTURE 4 "Following the item"): a window applies to its item,
 * not to a name. When an administrator or a user with their own permissions renames or moves the
 * item, the window follows it (so do the windows on the items below a renamed or moved folder);
 * deleting the item ends (revokes, reason "its item was deleted") the windows naming it or anything
 * below it, with a GRANT_REVOKE record; creating a new item at a window's name and starting Jenkins
 * after the item vanished end it too. There is no "no longer applies" state any more: a window either
 * applies to its (possibly renamed or moved) item or has ended. The "No longer applies" display that
 * D-71a introduced is being removed, so nothing here asserts it either way, and the window state
 * marker ({@code data-batch-control-window-state}) is not read at all.
 *
 * <p>The page contract used (D-66, T-UI-95, T-08-122, T-08-66): the grants page lists active
 * windows in {@code table[data-batch-control-list=active]}, each row linking to the window's detail
 * page {@code batch-control/grants/<id>/} and naming the window's item by its full name; ended
 * windows are rows of {@code table[data-batch-control-list=ended]} naming the item, and a revocation
 * reason (D-63) is shown on the grants page.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-63/D-66/D-74, docs/ARCHITECTURE.md sections 4
 * and 5 only (no src/main knowledge).
 */
final class WindowStateFixtures {

    static final String ACTIVE_LIST = "table[data-batch-control-list=active]";
    static final String ENDED_LIST = "table[data-batch-control-list=ended]";
    /** The documented reason of a window ended by its item's deletion (ARCHITECTURE 4, D-74). */
    static final String DELETED_REASON = "its item was deleted";

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

    /** The active window {@code grantId} from the service, or null if it is not active. */
    static Grant active(String grantId) {
        return GrantService.get().listActive().stream().filter(g -> grantId.equals(g.getId())).findFirst().orElse(null);
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

    /** The rows of the Ended list whose text names {@code fullName} (as a whole word between separators). */
    static List<DomElement> endedRowsNaming(HtmlPage list, String fullName) {
        List<DomElement> out = new ArrayList<>();
        for (DomNode table : list.querySelectorAll(ENDED_LIST)) {
            for (DomNode n : table.querySelectorAll("tr")) {
                String text = n.asNormalizedText();
                if (names(text, fullName)) {
                    out.add((DomElement) n);
                }
            }
        }
        return out;
    }

    /** True if {@code text} contains {@code fullName} not directly followed or preceded by a name character. */
    private static boolean names(String text, String fullName) {
        int at = text.indexOf(fullName);
        while (at >= 0) {
            boolean before = at == 0 || !nameChar(text.charAt(at - 1));
            int end = at + fullName.length();
            boolean after = end >= text.length() || !nameChar(text.charAt(end));
            if (before && after) {
                return true;
            }
            at = text.indexOf(fullName, at + 1);
        }
        return false;
    }

    private static boolean nameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '/' || c == '.';
    }

    /** The detail page of the window, opened by {@code viewer}; it must answer 200. */
    static HtmlPage detailPage(JenkinsRule j, String viewer, String grantId) throws Exception {
        HtmlPage detail = UsabilityFixtures.htmlPage(j, viewer, "batch-control/grants/" + grantId + "/");
        assertEquals(200, detail.getWebResponse().getStatusCode(), viewer + " must open the detail page of the window " + grantId);
        return detail;
    }

    /**
     * The window {@code grantId} is active on the item {@code fullName}: the service lists it as
     * active with exactly that scope full name, and the grants page seen by {@code viewer} lists it
     * in the Active list in a row that links to its detail page and names {@code fullName}.
     */
    static void assertActiveOn(JenkinsRule j, String viewer, String grantId, String fullName, String what) throws Exception {
        Grant grant = active(grantId);
        assertNotNull(grant, what + ": the window " + grantId + " must be active, active windows: " + describeActive());
        assertEquals(fullName, grant.getScope().getFullName(), what + ": the window must name its item's current full name");
        HtmlPage list = UsabilityFixtures.htmlPage(j, viewer, "batch-control/grants/");
        DomElement row = activeRow(j, list, grantId);
        assertNotNull(row, what + ": the window must be listed among the active windows: " + excerpt(list.asNormalizedText()));
        assertTrue(names(row.asNormalizedText(), fullName), what + ": the Active list row must name the item " + fullName
                + ": " + excerpt(row.asNormalizedText()));
    }

    /**
     * The window {@code grantId} has ended: the service does not list it as active, the grants page
     * seen by {@code viewer} has no Active list row for it, and its detail page still opens (an
     * ended window keeps its page).
     */
    static void assertEnded(JenkinsRule j, String viewer, String grantId, String what) throws Exception {
        assertNull(active(grantId), what + ": the window " + grantId + " must have ended (not active)");
        HtmlPage list = UsabilityFixtures.htmlPage(j, viewer, "batch-control/grants/");
        assertNull(activeRow(j, list, grantId), what + ": an ended window must not be listed among the active windows");
        detailPage(j, viewer, grantId);
    }

    /**
     * The window {@code grantId} on {@code fullName} was ended by its item's deletion: ended as in
     * {@link #assertEnded}, and the Ended list of the grants page has a row naming {@code fullName}
     * that shows the reason {@link #DELETED_REASON} (D-63: the grants screen shows a revocation's
     * reason).
     */
    static void assertEndedByDeletion(JenkinsRule j, String viewer, String grantId, String fullName, String what) throws Exception {
        assertEnded(j, viewer, grantId, what);
        HtmlPage list = UsabilityFixtures.htmlPage(j, viewer, "batch-control/grants/");
        List<DomElement> rows = endedRowsNaming(list, fullName);
        assertTrue(rows.stream().anyMatch(r -> r.asNormalizedText().toLowerCase(Locale.ROOT).contains(DELETED_REASON)),
                what + ": the Ended list must show the window on " + fullName + " with the reason '" + DELETED_REASON + "'; rows naming it: "
                        + rows.stream().map(r -> excerpt(r.asNormalizedText())).collect(Collectors.toList()));
    }

    /** The ids of every GRANT_REVOKE record now stored (current month). */
    static Set<String> revokeRecordIds() {
        return ApproverFormFixtures.records(ChangeType.GRANT_REVOKE).stream().map(ChangeRecord::getId).collect(Collectors.toSet());
    }

    /** The GRANT_REVOKE records stored since {@code before} (ids of {@link #revokeRecordIds()}). */
    static List<ChangeRecord> revokeRecordsSince(Set<String> before) {
        return ApproverFormFixtures.records(ChangeType.GRANT_REVOKE).stream().filter(r -> !before.contains(r.getId()))
                .collect(Collectors.toList());
    }

    /** True if {@code rec} identifies the window {@code grantId} on {@code fullName}. */
    static boolean identifies(ChangeRecord rec, String grantId, String fullName) {
        String target = String.valueOf(rec.getTarget());
        String detail = String.valueOf(rec.getDetail());
        return grantId.equals(rec.getGrantId()) || target.contains(grantId) || detail.contains(grantId) || fullName.equals(target);
    }

    /**
     * Since {@code before}, exactly one GRANT_REVOKE record was stored per window of
     * {@code windows} (each {@code {grantId, fullName}}) and no other, each stating in its detail
     * that the item was deleted (D-63: the record detail names the reason; the reason's exact text,
     * {@link #DELETED_REASON}, is pinned on the grants screen, {@link #assertEndedByDeletion}).
     */
    static void assertDeletionRevokeRecords(Set<String> before, String[][] windows, String what) {
        List<ChangeRecord> added = revokeRecordsSince(before);
        assertEquals(windows.length, added.size(), what + ": one GRANT_REVOKE record per window ended by the deletion, got " + describe(added));
        for (String[] w : windows) {
            long matching = added.stream().filter(r -> identifies(r, w[0], w[1])).count();
            assertEquals(1, matching, what + ": exactly one GRANT_REVOKE record must identify the window on " + w[1] + " (" + w[0] + "), got "
                    + describe(added));
        }
        for (ChangeRecord r : added) {
            assertTrue(String.valueOf(r.getDetail()).toLowerCase(Locale.ROOT).contains("was deleted"),
                    what + ": the GRANT_REVOKE record must say that the item was deleted: " + describe(List.of(r)));
        }
    }

    static String describe(List<ChangeRecord> records) {
        return records.stream().map(r -> "[user=" + r.getUser() + " target=" + r.getTarget() + " grantId=" + r.getGrantId()
                + " detail=" + r.getDetail() + "]").collect(Collectors.joining(", "));
    }

    private static String describeActive() {
        return GrantService.get().listActive().stream().map(g -> g.getId() + "@" + g.getScope().getFullName())
                .collect(Collectors.joining(", "));
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

    /** The main panel of a page (or the page itself). */
    static DomNode mainPanel(HtmlPage page) {
        DomNode main = page.querySelector("#main-panel");
        return main == null ? page : main;
    }

    /** The stored grant file ({@code batch-control/grants/<id>.xml}, ARCHITECTURE 5). */
    static String storedGrant(JenkinsRule j, String grantId) throws Exception {
        Path file = j.jenkins.getRootDir().toPath().resolve("batch-control/grants/" + grantId + ".xml");
        assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): the window is stored at " + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
