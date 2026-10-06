package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.relocate.RelocationAction;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 3, scenario L3-22: links that depend on what the viewer may open. Matrix rows
 * T-GAP-379 and T-GAP-380 (note 279).
 *
 * <p>Basis: SPEC 2 line 51 "A link inside the Batch Control screens is shown only to a user who may
 * open its target"; SPEC 9 "변경 시각에 변경자의 활성 Grant가 있으면 grantId가 연결되고"; LIMITATIONS 24 "Active grants
 * are visible only to their own holder and to Manage holders"; SPEC 8 line 168 "Because a window's
 * DELETE never applies to an item group (D-71), moving a folder ... cannot be authorised through
 * permission windows" and LIMITATIONS 44 "In both cases the refusal says an administrator must make the
 * move instead of suggesting a window"; the existing contract that a refusal page links the prefilled
 * grant form {@code batch-control/grants/new?scopeFullName=...} when a window could help (T-SEC-72).
 *
 * <p>Batch Control matrix strategy, change control on; u1 and u2 hold Overall/Read, Item/Read and
 * RequestGrant (u2 also the folders plugin's Move); viewer holds Overall/Read and ViewHistory only.
 *
 * <p>Written from docs/SPEC.md items 2, 8 and 9, docs/DECISIONS.md D-59 and D-71 and docs/LIMITATIONS.md
 * only (no src/main knowledge).
 */
@WithJenkins
public class LinkVisibilityGapTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "u2", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u2"));
        strategy.add(RelocationAction.RELOCATE, PermissionEntry.user("u2"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        strategy.add(Jenkins.READ, PermissionEntry.user("viewer"));
        strategy.add(BatchControlPermissions.VIEW_HISTORY, PermissionEntry.user("viewer"));
        j.jenkins.setAuthorizationStrategy(strategy);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-GAP-379 (L3-22; SPEC 2 line 51, SPEC 9 grantId): u1 saves job {@code lv-job} through
     * {@code config.xml} under a CONFIGURE window, so a CONFIGURE record carries the window's grant id.
     * On {@code batch-control/changes/} and on {@code batch-control/history/?kind=changes}, the row of
     * that record links the grant id to {@code batch-control/grants/<id>/} for the administrator, and
     * shows it as plain text, without a link to that page, for viewer, who may not open it (premise:
     * the page answers 403 or 404 to viewer).
     */
    @Test
    public void t_gap_379_grantIdIsALinkOnlyForViewersWhoMayOpenTheWindow() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("lv-job");
        String id = submitGrantOk(j, "u1", "lv-job", List.of("CONFIGURE"), 30, "edit lv-job", null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: a1 approves");
        String xml = job.getConfigFile().asString();
        String edited = StoreFaultGapTest.withDescription(xml, "edited by u1");
        assertFalse(edited.equals(xml), "fixture: the description changes");
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "u1");
        WebRequest post = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        post.setAdditionalHeader("Content-Type", "application/xml");
        post.setRequestBody(edited);
        assertTrue(wc.getPage(post).getWebResponse().getStatusCode() < 400, "fixture: u1's save under the window");
        ChangeRecord rec = ApproverFormFixtures.records(ChangeType.CONFIGURE).stream()
                .filter(r -> "lv-job".equals(r.getTarget()) && r.getGrantId() != null).reduce((a, b) -> b).orElse(null);
        assertNotNull(rec, "premise (SPEC 9): u1's save is a CONFIGURE record carrying the window's grant id");
        String grantId = rec.getGrantId();
        int viewerOpens = ApproverFormFixtures.get(j, "viewer", "batch-control/grants/" + grantId + "/").getStatusCode();
        assertTrue(viewerOpens == 403 || viewerOpens == 404, "premise (LIMITATIONS 24): viewer may not open the window's page, got " + viewerOpens);

        for (String path : new String[] {"batch-control/changes/", "batch-control/history/?kind=changes"}) {
            HtmlPage admin = UsabilityFixtures.htmlPage(j, "admin", path);
            DomElement adminRow = rowWith(admin, grantId);
            assertNotNull(adminRow, path + ": the administrator sees the record's grant id: " + excerpt(admin.asNormalizedText()));
            assertTrue(linksTo(admin, adminRow, "batch-control/grants/" + grantId + "/"),
                    "SPEC 2 line 51: the administrator, who may open the window, gets a link to it on " + path + ": " + adminRow.asXml());

            HtmlPage viewer = UsabilityFixtures.htmlPage(j, "viewer", path);
            DomElement viewerRow = rowWith(viewer, grantId);
            assertNotNull(viewerRow, path + ": viewer sees the record's grant id as text: " + excerpt(viewer.asNormalizedText()));
            assertFalse(linksTo(viewer, viewerRow, "batch-control/grants/" + grantId + "/"),
                    "SPEC 2 line 51: viewer, who may not open the window, gets no link to it on " + path + ": " + viewerRow.asXml());
        }
    }

    /**
     * T-GAP-380 (L3-22; SPEC 8 line 168, LIMITATIONS 44): u2 holds Move, a CREATE window on folder
     * {@code dest} (Create at the destination is not missing) and no Delete on folder {@code src}. u2's
     * browser move of {@code src} into {@code dest} is refused (403): nothing moves, the page says an
     * administrator must make the move, and it offers no link to the grant request form
     * ({@code batch-control/grants/new}). Guard: the same move of the job {@code lv-move} (a job, where a
     * DELETE window applies) is refused with a link to the grant request form prefilled with
     * {@code lv-move}.
     */
    @Test
    public void t_gap_380_refusedFolderMoveOffersNoWindowLink() throws Exception {
        Folder src = j.jenkins.createProject(Folder.class, "src");
        j.jenkins.createProject(Folder.class, "dest");
        FreeStyleProject job = j.createFreeStyleProject("lv-move");
        String id = submitGrantOk(j, "u2", "dest", List.of("CREATE"), 30, "move things into dest", null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: a1 approves u2's CREATE window on dest");

        Page folderMove = browserMove("u2", src, "/dest");
        assertEquals(403, folderMove.getWebResponse().getStatusCode(), "the folder move is refused: "
                + excerpt(folderMove.getWebResponse().getContentAsString()));
        assertNotNull(j.jenkins.getItemByFullName("src"), "src stays where it is");
        assertTrue(j.jenkins.getItemByFullName("dest/src") == null, "nothing arrives in dest");
        HtmlPage page = (HtmlPage) folderMove;
        String text = WindowStateFixtures.mainPanel(page).asNormalizedText();
        assertTrue(text.toLowerCase(Locale.ROOT).contains("administrator"), "LIMITATIONS 44: the refusal says an administrator must make the"
                + " move: " + excerpt(text));
        assertTrue(grantFormLinks(page).isEmpty(), "SPEC 8 line 168: the refusal offers no window it cannot grant, links: " + grantFormLinks(page));

        Page jobMove = browserMove("u2", job, "/dest");
        assertEquals(403, jobMove.getWebResponse().getStatusCode(), "guard: the job move without Delete is refused too");
        List<URL> links = grantFormLinks((HtmlPage) jobMove);
        assertTrue(links.stream().anyMatch(u -> String.valueOf(u.getQuery()).contains("scopeFullName=lv-move")),
                "guard (T-SEC-72): the job's refusal links the grant request form prefilled with lv-move: " + links);
    }

    // ------------------------------------------------------------------ helpers

    private Page browserMove(String userId, Item item, String destination) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, userId);
        wc.getOptions().setJavaScriptEnabled(false);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(item.getUrl() + "move/move"), HttpMethod.POST);
        request.setAdditionalHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        request.setRequestParameters(List.of(new NameValuePair("destination", destination)));
        return wc.getPage(request);
    }

    /** Links (href) and form actions inside the main panel that point at the grant request form. */
    private static List<URL> grantFormLinks(HtmlPage page) throws Exception {
        List<URL> out = new ArrayList<>();
        for (DomNode n : WindowStateFixtures.mainPanel(page).querySelectorAll("a[href], form[action]")) {
            DomElement e = (DomElement) n;
            String target = e.hasAttribute("href") ? e.getAttribute("href") : e.getAttribute("action");
            URL url = page.getFullyQualifiedUrl(target);
            if (url.getPath().contains("batch-control/grants/new") || url.getPath().contains("batch-control/grants/dialog")) {
                out.add(url);
            }
        }
        return out;
    }

    /** The table row whose text carries {@code needle}. */
    private static DomElement rowWith(HtmlPage page, String needle) {
        for (DomNode n : WindowStateFixtures.mainPanel(page).querySelectorAll("tr")) {
            if (n.asNormalizedText().contains(needle) || ((DomElement) n).asXml().contains(needle)) {
                if (n.asNormalizedText().contains("CONFIGURE")) {
                    return (DomElement) n;
                }
            }
        }
        return null;
    }

    private boolean linksTo(HtmlPage page, DomElement scope, String rootRelative) throws Exception {
        String target = new URL(j.getURL(), rootRelative).getPath();
        for (DomElement a : scope.getElementsByTagName("a")) {
            if (a.hasAttribute("href") && page.getFullyQualifiedUrl(a.getAttribute("href")).getPath().equals(target)) {
                return true;
            }
        }
        return false;
    }
}
