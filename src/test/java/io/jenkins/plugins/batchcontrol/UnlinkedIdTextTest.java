package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlOption;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UX review 2026-10-04 UX-1, a regression of the e2e-11 DEF-05 fix: wherever a list shows a
 * request or permission-window id the viewer cannot open, the id is still shown as text and the
 * cell is never empty. Matrix rows T-UI-107..109 (note 251).
 *
 * <p>Derived from SPEC 2 ("A link inside the Batch Control screens is shown only to a user who may
 * open its target"), SPEC 10 ("APPROVED_REQUEST runs are connected to the request detail by the
 * request id"; approval and run on one line), SPEC 12 (request history) and SPEC 8 / D-66 (the
 * grants lists). SPEC offers no user path to a window whose request the viewer cannot open
 * (window and request share the same visibility), so T-UI-109 removes the request files from the
 * store (ARCHITECTURE 5) to obtain windows with no linkable request (note 251).
 *
 * <p>"Shown as text" is read as: the row's text carries the full id, or an element in the row
 * shows a leading part of the id (at least 8 characters) and names the full id in {@code title}
 * or {@code data-batch-control-id} (the short form DEF-05 suggested). Rows are found by content
 * (the job name), never by caption or column position. Viewer {@code hv} holds only Overall/Read
 * and BatchControl/ViewHistory: not the requester, not a designated approver, no Manage, no
 * Item/Read on the job, so by P-09 the request detail is not open to hv (premise asserted).
 *
 * <p>Written from docs/SPEC.md, docs/ARCHITECTURE.md section 5 and docs/TEST-MATRIX.md only (no
 * src/main knowledge).
 */
@WithJenkins
public class UnlinkedIdTextTest {

    private static final String JOB = "idtext-j";
    private static final String ACTIVE_JOB = "idtext-active";
    private static final String ENDED_JOB = "idtext-ended";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1", "hv"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
        }
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(Item.BUILD, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        strategy.add(BatchControlPermissions.VIEW_HISTORY, PermissionEntry.user("hv"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-UI-107: the run of an approved request, on the dashboard and on History (runs), viewed by
     * hv who may see those screens but cannot open the request: the Request cell shows the request
     * id as text and does not link it.
     */
    @Test
    public void t_ui_107_runRequestCellShowsUnlinkableIdAsText() throws Exception {
        RunRequest request = approvedRun();
        assertRequestNotOpenTo("hv", "batch-control/requests/" + request.getId() + "/");

        for (String path : new String[] {"batch-control/dashboard/", "batch-control/history/?kind=runs"}) {
            HtmlPage page = UsabilityFixtures.htmlPage(j, "hv", path);
            assertEquals(200, page.getWebResponse().getStatusCode(), "premise: hv (ViewHistory) opens " + path);
            DomElement row = rowNaming(page, JOB);
            assertNotNull(row, path + ": premise: the run of " + JOB + " is listed for hv (T-06-51); page: "
                    + excerpt(page.asNormalizedText()));
            assertNoLinkTo(row, "requests/" + request.getId(), path);
            assertShowsId(row, path + " Request cell", request.getId());
        }
    }

    /**
     * T-UI-108: History (requests) viewed by hv: the request's row shows its id as text, without a
     * link to a detail page hv cannot open.
     */
    @Test
    public void t_ui_108_requestHistoryShowsUnlinkableIdAsText() throws Exception {
        RunRequest request = approvedRun();
        assertRequestNotOpenTo("hv", "batch-control/requests/" + request.getId() + "/");

        String path = requestsHistoryPath();
        HtmlPage page = UsabilityFixtures.htmlPage(j, "hv", path);
        assertEquals(200, page.getWebResponse().getStatusCode(), "premise: hv (ViewHistory) opens " + path);
        DomElement row = rowNaming(page, JOB);
        assertNotNull(row, path + ": premise: the request on " + JOB + " is listed in the request history for hv"
                + " (history screens are outside P-09, note 87); page: " + excerpt(page.asNormalizedText()));
        assertNoLinkTo(row, "requests/" + request.getId(), path);
        assertShowsId(row, path + " id cell", request.getId());
    }

    /**
     * T-UI-109: an active and an ended permission window whose requests no longer exist in the
     * store (their {@code requests/grant/<id>.xml} files, ARCHITECTURE 5, are removed, so neither
     * has a detail page to link to) are listed on the grants page for the administrator, each row
     * showing the window's (or request's) id as text.
     */
    @Test
    public void t_ui_109_windowWithoutLinkableRequestShowsIdAsText() throws Exception {
        j.createFreeStyleProject(ACTIVE_JOB);
        j.createFreeStyleProject(ENDED_JOB);
        GrantRequest activeRequest = grantRequest(ACTIVE_JOB);
        GrantRequest endedRequest = grantRequest(ENDED_JOB);
        Grant active = as("a1", () -> GrantRequestService.get().approve(activeRequest.getId(), "ok"));
        Grant ended = as("a1", () -> GrantRequestService.get().approve(endedRequest.getId(), "ok"));
        GrantService.get().revoke(ended.getId());

        Path store = StoreDataFixtures.storeDir();
        for (GrantRequest request : List.of(activeRequest, endedRequest)) {
            Path file = store.resolve("requests/grant/" + request.getId() + ".xml");
            assertTrue(Files.exists(file), "fixture: " + file + " is stored (ARCHITECTURE 5)");
            Files.delete(file);
        }
        for (String id : new String[] {activeRequest.getId(), endedRequest.getId(), active.getId(), ended.getId()}) {
            assertTrue(getStatus("admin", "batch-control/grants/" + id + "/") >= 400,
                    "premise: " + id + " has no detail page left to link to");
        }

        HtmlPage page = UsabilityFixtures.htmlPage(j, "admin", "batch-control/grants/");
        assertEquals(200, page.getWebResponse().getStatusCode(), "premise: admin opens the grants page");
        String[][] cases = {
            {"active", ACTIVE_JOB, active.getId(), activeRequest.getId()},
            {"ended", ENDED_JOB, ended.getId(), endedRequest.getId()}};
        for (String[] c : cases) {
            DomElement row = null;
            for (DomNode table : page.querySelectorAll("table[data-batch-control-list=" + c[0] + "]")) {
                if (row == null) {
                    row = rowNaming(table, c[1]);
                }
            }
            assertNotNull(row, "premise: the window on " + c[1] + " is a row of table[data-batch-control-list=" + c[0]
                    + "] (D-66); page: " + excerpt(page.asNormalizedText()));
            assertNoLinkTo(row, "grants/" + c[3], c[0] + " list");
            assertNoLinkTo(row, "grants/" + c[2], c[0] + " list");
            assertShowsAnyId(row, c[0] + " list ID cell", c[2], c[3]);
        }
    }

    // ---------------------------------------------------------------- fixtures

    private RunRequest approvedRun() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(JOB);
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(job, property);
        RunRequest request = as("u1", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "id text run", "a1"));
        as("a1", () -> {
            RunRequestService.get().approve(request.getId(), "ok");
            return null;
        });
        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size(), "fixture: the approved run must have executed once");
        return request;
    }

    private GrantRequest grantRequest(String jobName) throws Exception {
        return as("u1", () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.JOB, jobName),
                Arrays.asList(GrantAction.CONFIGURE), 30, "id text window", List.of("a1")));
    }

    private void assertRequestNotOpenTo(String user, String path) throws Exception {
        assertTrue(getStatus(user, path) >= 400, "premise: " + user + " cannot open " + path + " (P-09)");
        assertEquals(200, getStatus("admin", path), "premise guard: the request detail itself exists");
    }

    /** The History "requests" view, discovered from the History page's own kind controls. */
    private String requestsHistoryPath() throws Exception {
        HtmlPage history = UsabilityFixtures.htmlPage(j, "admin", "batch-control/history/");
        for (HtmlAnchor a : history.getAnchors()) {
            String href = a.getHrefAttribute();
            int at = href.indexOf("kind=");
            if (at >= 0) {
                String value = href.substring(at + 5).split("&")[0];
                if (value.toLowerCase(Locale.ROOT).contains("request")) {
                    return "batch-control/history/?kind=" + value;
                }
            }
        }
        for (DomNode node : history.querySelectorAll("select[name=kind]")) {
            for (HtmlOption option : ((HtmlSelect) node).getOptions()) {
                if (option.getValueAttribute().toLowerCase(Locale.ROOT).contains("request")) {
                    return "batch-control/history/?kind=" + option.getValueAttribute();
                }
            }
        }
        return "batch-control/history/?kind=requests";
    }

    // ---------------------------------------------------------------- assertions

    /** The innermost table row under {@code root} whose text names {@code token}. */
    private static DomElement rowNaming(DomNode root, String token) {
        DomElement found = null;
        for (DomNode node : root.querySelectorAll("tr")) {
            DomElement tr = (DomElement) node;
            if (!tr.querySelectorAll("tr").isEmpty() || RequestPageFixtures.insideTabBar(tr)) {
                continue;
            }
            if (tr.asNormalizedText().contains(token) && tr.querySelectorAll("th").isEmpty()) {
                if (found == null) {
                    found = tr;
                }
            }
        }
        return found;
    }

    private static void assertNoLinkTo(DomElement row, String pathPart, String where) {
        List<String> hrefs = new ArrayList<>();
        for (DomNode node : row.querySelectorAll("a[href]")) {
            String href = ((DomElement) node).getAttribute("href");
            if (href.contains(pathPart)) {
                hrefs.add(href);
            }
        }
        assertTrue(hrefs.isEmpty(), where + ": the viewer cannot open " + pathPart + ", so the row must not link it (SPEC 2); found "
                + hrefs);
    }

    private static void assertShowsId(DomElement row, String where, String id) {
        assertTrue(showsId(row, id), where + ": the id " + id + " must be shown as text even without a link (UX-1); row was '"
                + row.asNormalizedText() + "' / " + excerpt(row.asXml()));
    }

    private static void assertShowsAnyId(DomElement row, String where, String... ids) {
        for (String id : ids) {
            if (showsId(row, id)) {
                return;
            }
        }
        throw new AssertionError(where + ": one of " + Arrays.toString(ids) + " must be shown as text even without a link"
                + " (UX-1); row was '" + row.asNormalizedText() + "' / " + excerpt(row.asXml()));
    }

    private static boolean showsId(DomElement row, String id) {
        if (row.asNormalizedText().contains(id)) {
            return true;
        }
        for (DomElement e : row.getHtmlElementDescendants()) {
            String text = e.asNormalizedText().trim();
            boolean names = id.equals(e.getAttribute("title")) || id.equals(e.getAttribute("data-batch-control-id"));
            if (names && text.length() >= 8 && id.startsWith(text.replace("…", "").replace("...", ""))) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- HTTP

    private int getStatus(String user, String path) throws Exception {
        JenkinsRule.WebClient wc = UsabilityFixtures.clientNoJs(j, user);
        Page page = wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET));
        return page.getWebResponse().getStatusCode();
    }

    private static <T> T as(String userId, Callable<T> body) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(userId, true).impersonate2())) {
            return body.call();
        }
    }
}
