package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.net.URL;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-66 (hosting review round 3, R4-8, R4-12): "The grants, run requests and activations pages list
 * pending requests first, then active and ended items, each row linking to its detail page; the
 * request form is not the first thing on the grants page. A permission window can be revoked from
 * its own detail page as well as from the list." Matrix rows T-UI-95..97, T-UI-102, T-UI-103 (notes 245, 246, 249).
 *
 * <p>Rows are found by the id in their link, order is document order of those links in
 * {@code #main-panel} (outside the tab bar), and the revoke control is found by its endpoint
 * (a path ending in {@code /revoke}), never by caption. The confirmation step before a revoke is
 * browser behaviour and is left to e2e (note 246).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-66 and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class RequestLayoutTest {

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1", "a2", "m1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(Item.BUILD, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a2"));
        strategy.add(BatchControlPermissions.MANAGE, PermissionEntry.user("m1"));
        j.jenkins.setAuthorizationStrategy(strategy);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.save();
        job = j.createFreeStyleProject("batch-x");
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * T-UI-95: the grants page lists a pending request before an active window before an ended
     * (rejected) request; each row links to a detail page that opens; the request form (posting to
     * {@code grants/create}) is not the first thing on the page: if present at all, it comes after
     * the pending row.
     */
    @Test
    public void t_ui_95_grantsPageListsPendingThenActiveThenEndedWithDetailLinks() throws Exception {
        GrantRequest ended = grantRequest("ended one");
        as("a1", () -> GrantRequestService.get().reject(ended.getId(), "no"));
        GrantRequest activeRequest = grantRequest("active one");
        Grant active = as("a1", () -> GrantRequestService.get().approve(activeRequest.getId(), "ok"));
        GrantRequest pending = grantRequest("pending one");

        HtmlPage page = UsabilityFixtures.htmlPage(j, "admin", "batch-control/grants/");
        assertEquals(200, page.getWebResponse().getStatusCode(), "premise: admin opens the grants page");
        int p = RequestPageFixtures.firstLinkIndex(page, pending.getId());
        int a = Math.max(-1, minPositive(RequestPageFixtures.firstLinkIndex(page, activeRequest.getId()),
                RequestPageFixtures.firstLinkIndex(page, active.getId())));
        int e = RequestPageFixtures.firstLinkIndex(page, ended.getId());
        List<String> links = RequestPageFixtures.mainLinkPaths(page);
        assertTrue(p >= 0, "the pending request's row must link to it; links: " + links);
        assertTrue(a >= 0, "the active window's row must link to its detail page; links: " + links);
        assertTrue(e >= 0, "the ended request's row must link to it; links: " + links);
        assertTrue(p < a && a < e, "pending first, then active, then ended (D-66): positions " + p + ", " + a + ", " + e
                + " in " + links);

        assertInList(page, "pending", pending.getId());
        assertInList(page, "active", active.getId());
        assertInList(page, "ended", ended.getId());
        for (String token : new String[] {pending.getId(), ended.getId()}) {
            assertDetailOpens(page, token);
        }
        URL activeLink = RequestPageFixtures.firstLink(page, active.getId());
        assertDetailOpens(page, activeLink != null ? active.getId() : activeRequest.getId());

        org.htmlunit.html.DomNode pendingAnchor = anchorFor(page, pending.getId());
        for (HtmlForm form : UsabilityFixtures.formsEndingWith(page, "batch-control/grants/create")) {
            assertTrue(RequestPageFixtures.position(page, form) > RequestPageFixtures.position(page, pendingAnchor),
                    "the request form must not be the first thing on the grants page (D-66): it precedes the pending row");
        }
    }

    /**
     * T-UI-96: the run requests page and the activations page list a pending request before a
     * decided one, each row linking to a detail page that opens.
     */
    @Test
    public void t_ui_96_requestsAndActivationsPagesListPendingFirst() throws Exception {
        RunRequest decided = as("u1", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "decided run", "a1"));
        as("a1", () -> RunRequestService.get().reject(decided.getId(), "no"));
        RunRequest pending = as("u1", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "pending run", "a1"));
        assertPendingFirst("batch-control/requests/", pending.getId(), decided.getId());

        FreeStyleProject other = j.createFreeStyleProject("batch-y");
        BatchControlFixtures.setBatchControl(other, new BatchControlJobProperty(true));
        ActivationRequest decidedActivation = as("u1", () -> ActivationService.get().create(other,
                ActivationRequest.Action.ACTIVATE, "go live", List.of("a1")));
        as("a1", () -> ActivationService.get().approve(decidedActivation.getId(), "ok"));
        ActivationRequest pendingActivation = as("u1", () -> ActivationService.get().create(job,
                ActivationRequest.Action.ACTIVATE, "go live too", List.of("a1")));
        assertPendingFirst("batch-control/activations/", pendingActivation.getId(), decidedActivation.getId());
    }

    /**
     * T-UI-97: a permission window can be revoked from its own detail page, under the list's rule
     * (BatchControl/Manage). Guard first: the window's holder u1 (RequestGrant) and the approver a1
     * are offered no revoke control on the detail page, their POST to the detail page's revoke
     * endpoint is refused with 403 and a GET there never revokes; the window stays active. Then the
     * Manage holder m1 finds the control on the same page, POSTs it, and the window is inactive with
     * a GRANT_REVOKE record by m1.
     */
    @Test
    public void t_ui_97_windowIsRevokedFromItsDetailPageByManageHolderOnly() throws Exception {
        GrantRequest request = grantRequest("revoke me");
        Grant grant = as("a1", () -> GrantRequestService.get().approve(request.getId(), "ok"));
        assertTrue(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE), "premise: the window is active");

        HtmlPage list = UsabilityFixtures.htmlPage(j, "m1", "batch-control/grants/");
        URL detail = RequestPageFixtures.firstLink(list, grant.getId());
        if (detail == null) {
            detail = RequestPageFixtures.firstLink(list, request.getId());
        }
        assertNotNull(detail, "the active window's row must link to its detail page; links: " + RequestPageFixtures.mainLinkPaths(list));
        String detailPath = detail.getPath().substring(j.getURL().getPath().length());

        HtmlPage managerView = UsabilityFixtures.htmlPage(j, "m1", detailPath);
        assertEquals(200, managerView.getWebResponse().getStatusCode(), "the Manage holder opens the window's detail page");
        List<URL> revoke = RequestPageFixtures.controlsEndingWith(managerView, "/revoke");
        assertEquals(1, revoke.size(), "the window's detail page must offer exactly one revoke control (D-66), found " + revoke
                + ": " + excerpt(managerView.asNormalizedText()));
        String revokePath = revoke.get(0).getPath().substring(j.getURL().getPath().length());
        assertEquals("batch-control/grants/" + grant.getId() + "/revoke", revokePath,
                "the detail page's revoke control must post to grants/<id>/revoke (D-66 public surface)");

        for (String user : new String[] {"u1", "a1"}) {
            HtmlPage view = UsabilityFixtures.htmlPage(j, user, detailPath);
            if (view.getWebResponse().getStatusCode() == 200) {
                assertTrue(RequestPageFixtures.controlsEndingWith(view, "/revoke").isEmpty(),
                        user + " (no Manage) must not be offered the revoke control on the detail page");
            }
            assertEquals(403, ApproverFormFixtures.post(j, user, revokePath, List.of()).getStatusCode(),
                    user + " (no Manage) must be refused with 403 by the detail page's revoke endpoint " + revokePath);
        }
        Page get = UsabilityFixtures.clientNoJs(j, "m1").getPage(new WebRequest(new URL(j.getURL(), revokePath), HttpMethod.GET));
        assertTrue(get.getWebResponse().getStatusCode() >= 400, "GET on the revoke endpoint must be refused, got "
                + get.getWebResponse().getStatusCode());
        assertTrue(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE), "guard: refused attempts leave the window active");

        int code = ApproverFormFixtures.post(j, "m1", revokePath, List.of()).getStatusCode();
        assertTrue(code < 400, "the Manage holder's revoke from the detail page must succeed, got HTTP " + code);
        assertFalse(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE), "the window must be inactive after the revoke");
        assertTrue(FileStore.get().listChangeRecords(YearMonth.now(BatchClock.clock())).stream()
                .anyMatch(r -> r.getType() == ChangeType.GRANT_REVOKE && "m1".equals(r.getUser())),
                "the revoke must leave a GRANT_REVOKE record by m1");
    }

    /**
     * T-UI-102: each list pages on its own parameter. With 120 ended grant requests and one pending,
     * the first page of the ended list shows fewer than 120; following {@code endedPage=2,3,...}
     * reaches every ended request exactly through the ended list, and the pending row stays on every
     * ended page (the parameter does not move the other lists). Guard: a {@code pendingPage=2}
     * page still lists the first ended page's rows.
     */
    @Test
    public void t_ui_102_listsPageIndependently() throws Exception {
        java.util.Set<String> endedIds = new java.util.TreeSet<>();
        for (int i = 0; i < 120; i++) {
            GrantRequest r = grantRequest("ended " + i);
            as("a1", () -> GrantRequestService.get().reject(r.getId(), "no"));
            endedIds.add(r.getId());
        }
        GrantRequest pending = grantRequest("pending one");

        java.util.Set<String> seen = new java.util.TreeSet<>();
        List<String> first = null;
        for (int pageNo = 1; pageNo <= 20 && seen.size() < endedIds.size(); pageNo++) {
            HtmlPage page = UsabilityFixtures.htmlPage(j, "admin", "batch-control/grants/?endedPage=" + pageNo);
            assertEquals(200, page.getWebResponse().getStatusCode(), "endedPage=" + pageNo + " must open");
            List<String> rows = idsInList(page, "ended", endedIds);
            if (pageNo == 1) {
                first = rows;
                assertTrue(rows.size() > 0 && rows.size() < endedIds.size(), "the ended list must page: page 1 shows " + rows.size()
                        + " of " + endedIds.size());
            }
            assertInList(page, "pending", pending.getId());
            if (rows.isEmpty()) {
                break;
            }
            seen.addAll(rows);
        }
        assertEquals(endedIds, seen, "following endedPage must reach every ended request");

        HtmlPage other = UsabilityFixtures.htmlPage(j, "admin", "batch-control/grants/?pendingPage=2");
        assertEquals(first, idsInList(other, "ended", endedIds), "pendingPage must not move the ended list");
    }

    /**
     * T-UI-103: on the activations page the pending row awaiting the viewer's decision carries
     * {@code small[data-batch-control-awaiting]} for the designated approver a1 only; the
     * administrator sees the row without the marker, and a2 (an approver who is not designated)
     * sees no marked row (the row itself may be withheld from a2).
     */
    @Test
    public void t_ui_103_activationAwaitingMarkerOnlyForTheDesignatedApprover() throws Exception {
        ActivationRequest pending = as("u1", () -> ActivationService.get().create(job,
                ActivationRequest.Action.ACTIVATE, "go live", List.of("a1")));
        assertEquals(Boolean.TRUE, awaitingMarked("a1", pending.getId()), "the designated approver's pending row must carry the awaiting marker");
        assertEquals(Boolean.FALSE, awaitingMarked("admin", pending.getId()), "the administrator sees the row, not designated: no marker");
        assertTrue(awaitingMarked("a2", pending.getId()) != Boolean.TRUE, "a2 is not designated: no awaiting marker (or no row)");
    }

    // ---------------------------------------------------------------- helpers

    /** Whether the pending row of {@code id} on {@code user}'s activations page carries the awaiting marker; null without the row. */
    private Boolean awaitingMarked(String user, String id) throws Exception {
        HtmlPage page = UsabilityFixtures.htmlPage(j, user, "batch-control/activations/");
        assertEquals(200, page.getWebResponse().getStatusCode(), user + " opens the activations page");
        org.htmlunit.html.DomElement row = rowIn(page, "pending", id);
        if (row == null) {
            return null;
        }
        return !row.querySelectorAll("small[data-batch-control-awaiting]").isEmpty();
    }

    private static org.htmlunit.html.DomElement rowIn(HtmlPage page, String list, String id) throws Exception {
        for (org.htmlunit.html.DomNode t : page.querySelectorAll("table[data-batch-control-list=" + list + "]")) {
            for (org.htmlunit.html.DomNode tr : t.querySelectorAll("tr")) {
                for (org.htmlunit.html.DomNode a : tr.querySelectorAll("a[href]")) {
                    if (page.getFullyQualifiedUrl(((org.htmlunit.html.DomElement) a).getAttribute("href")).getPath().contains(id)) {
                        return (org.htmlunit.html.DomElement) tr;
                    }
                }
            }
        }
        return null;
    }

    private static void assertInList(HtmlPage page, String list, String id) throws Exception {
        assertNotNull(rowIn(page, list, id), id + " must be a row of table[data-batch-control-list=" + list + "] (D-66); links: "
                + RequestPageFixtures.mainLinkPaths(page));
    }

    /** Ids of {@code candidates} linked from rows of the list, in order. */
    private static List<String> idsInList(HtmlPage page, String list, java.util.Set<String> candidates) throws Exception {
        List<String> out = new java.util.ArrayList<>();
        for (org.htmlunit.html.DomNode t : page.querySelectorAll("table[data-batch-control-list=" + list + "]")) {
            for (org.htmlunit.html.DomNode a : t.querySelectorAll("a[href]")) {
                String path = page.getFullyQualifiedUrl(((org.htmlunit.html.DomElement) a).getAttribute("href")).getPath();
                for (String id : candidates) {
                    if (path.contains(id) && !out.contains(id)) {
                        out.add(id);
                    }
                }
            }
        }
        return out;
    }

    private GrantRequest grantRequest(String reason) throws Exception {
        return as("u1", () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.JOB, "batch-x"),
                Arrays.asList(GrantAction.CONFIGURE), 30, reason, "a1"));
    }

    private void assertPendingFirst(String path, String pendingId, String decidedId) throws Exception {
        HtmlPage page = UsabilityFixtures.htmlPage(j, "admin", path);
        assertEquals(200, page.getWebResponse().getStatusCode(), "premise: admin opens " + path);
        int p = RequestPageFixtures.firstLinkIndex(page, pendingId);
        int d = RequestPageFixtures.firstLinkIndex(page, decidedId);
        List<String> links = RequestPageFixtures.mainLinkPaths(page);
        assertTrue(p >= 0 && d >= 0, path + ": both rows must link to their requests; links: " + links);
        assertTrue(p < d, path + ": the pending request must be listed before the decided one (D-66): " + links);
        assertInList(page, "pending", pendingId);
        // a decided run request has ended; an approved activation may be shown as active (note 249)
        assertTrue(rowIn(page, "ended", decidedId) != null || rowIn(page, "active", decidedId) != null,
                decidedId + " must be a row of the active or ended list (D-66); links: " + links);
        assertDetailOpens(page, pendingId);
        assertDetailOpens(page, decidedId);
    }

    private void assertDetailOpens(HtmlPage page, String token) throws Exception {
        URL link = RequestPageFixtures.firstLink(page, token);
        assertNotNull(link, "a row must link to " + token);
        Page detail = UsabilityFixtures.clientNoJs(j, "admin").getPage(new WebRequest(link, HttpMethod.GET));
        assertEquals(200, detail.getWebResponse().getStatusCode(), "the detail link " + link + " must open");
        assertTrue(detail.getWebResponse().getContentAsString().contains(token), "the detail page " + link + " must be about " + token);
    }

    private static org.htmlunit.html.DomNode anchorFor(HtmlPage page, String token) throws Exception {
        for (org.htmlunit.html.DomElement a : page.getElementById("main-panel").getElementsByTagName("a")) {
            if (!RequestPageFixtures.insideTabBar(a) && a.hasAttribute("href")
                    && page.getFullyQualifiedUrl(a.getAttribute("href")).getPath().contains(token)) {
                return a;
            }
        }
        throw new AssertionError("no row link to " + token);
    }

    private static int minPositive(int x, int y) {
        if (x < 0) {
            return y;
        }
        if (y < 0) {
            return x;
        }
        return Math.min(x, y);
    }

    private static <T> T as(String id, Callable<T> body) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(id, true).impersonate2())) {
            return body.call();
        }
    }
}
