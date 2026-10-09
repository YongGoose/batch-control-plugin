package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.WebResponse;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug hunt B, R4-01 (b) (matrix rows T-08-194, T-08-195, note 296): with a case-insensitive
 * security realm, a user who signs in with another letter case than the one their grant request was
 * made under ({@code LREQUESTER} against {@code lrequester}) is the same user for Batch Control: they
 * see and may cancel their own request, and their approved window confers its permission.
 *
 * <p>Basis: SPEC 3 ("User ids are compared with Jenkins' configured user id strategy, not by plain
 * string equality"), SPEC 7 (the requester may cancel a PENDING request), SPEC 8 (on approval the
 * requester obtains the window's Jenkins permission) and the frozen bug-hunt contract: every user id
 * comparison of the plugin (the grants page and detail predicates, GrantService's user comparisons)
 * uses the realm's id strategy; the other-case login sees and can cancel its own request (200) and
 * its approved window confers CONFIGURE. Reproduced on a real Jenkins with LDAP: badge 1 but no row,
 * detail 404, and the window conferred nothing (403).
 *
 * <p>Batch Control matrix strategy (grants confer only under a Batch Control strategy, D-35a), change
 * control on, approver a1. The dummy realm's id strategy is case-insensitive (asserted). Both
 * spellings of the requester get the same matrix entries, so their native permissions are identical
 * whatever matrix-auth's own sid comparison; nobody but the administrator holds Item/Configure
 * natively. {@code u2} is an unrelated user with the same permissions as the requester.
 *
 * <p>Written from docs/SPEC.md items 3, 7 and 8, DECISIONS D-35a and P-09 and the bug-hunt B contract
 * only (no src/main knowledge).
 */
@WithJenkins
public class GrantIdStrategyTest {

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"lrequester", "LREQUESTER", "u2"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
            strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user(userId));
        }
        strategy.add(Jenkins.READ, PermissionEntry.user("a1"));
        strategy.add(Item.READ, PermissionEntry.user("a1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);
        User.getById("lrequester", true).save(); // the account exists under its realm spelling, as with LDAP

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        job = j.createFreeStyleProject("job-k");

        assertTrue(j.jenkins.getSecurityRealm().getUserIdStrategy().equals("LREQUESTER", "lrequester"),
                "premise: the configured user id strategy is case-insensitive");
    }

    /**
     * T-08-194 (R4-01 b): lrequester requests a CONFIGURE window on {@code job-k} (PENDING). Signed in
     * as {@code LREQUESTER}, the same user sees a row linking the request on the grants page, opens its
     * detail page (200) and cancels it (2xx/3xx, CANCELLED). Guards first: u2 (unrelated, same
     * permissions) sees no row for it and gets 404 on its detail page (P-09); lrequester sees the row.
     */
    @Test
    public void t_08_194_otherCaseLoginSeesAndCancelsOwnGrantRequest() throws Exception {
        String id = submitGrantOk(j, "lrequester", "job-k", List.of("CONFIGURE"), 30, "maintenance of job-k", null, "a1");
        String detail = "batch-control/grants/" + id + "/";

        assertFalse(listsRequest("u2", id), "guard: u2, an unrelated user, must not see lrequester's request on the grants page");
        assertEquals(404, get("u2", detail).getStatusCode(), "guard: u2 must get 404 on lrequester's request (P-09)");
        assertTrue(listsRequest("lrequester", id), "guard: lrequester sees the own request on the grants page");

        assertTrue(listsRequest("LREQUESTER", id), "LREQUESTER is lrequester under the case-insensitive id strategy and must"
                + " see the own request on the grants page");
        WebResponse page = get("LREQUESTER", detail);
        assertEquals(200, page.getStatusCode(), "LREQUESTER must open the own request's detail page: "
                + UsabilityFixtures.excerpt(page.getContentAsString()));
        WebResponse cancel = ApproverFormFixtures.post(j, "LREQUESTER", detail + "cancel", List.of());
        assertSuccess(cancel, "LREQUESTER's cancel of the own PENDING request");
        assertEquals(RequestStatus.CANCELLED, GrantRequestService.get().load(id).getStatus(),
                "the requester's cancel (signed in as LREQUESTER) must cancel the request");
    }

    /**
     * T-08-195 (R4-01 b): lrequester's CONFIGURE window on {@code job-k}, approved by a1, confers
     * Item/Configure on {@code job-k} to the authentication {@code LREQUESTER}, and LREQUESTER signed
     * in opens {@code job-k/configure} (200). Guards first: the window confers Configure to
     * {@code lrequester}; u2 holds none (ACL) and gets 403 on the configure page.
     */
    @Test
    public void t_08_195_otherCaseLoginHoldsApprovedWindow() throws Exception {
        String id = submitGrantOk(j, "lrequester", "job-k", List.of("CONFIGURE"), 30, "maintenance of job-k", null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: a1 approves");

        assertTrue(job.getACL().hasPermission2(token("lrequester"), Item.CONFIGURE),
                "guard: the approved window confers Item/Configure on job-k to lrequester");
        assertFalse(job.getACL().hasPermission2(token("u2"), Item.CONFIGURE), "guard: u2 holds no Item/Configure on job-k");
        assertEquals(403, get("u2", job.getUrl() + "configure").getStatusCode(), "guard: u2 is refused job-k's configure page");

        assertTrue(job.getACL().hasPermission2(token("LREQUESTER"), Item.CONFIGURE), "the window of lrequester must confer"
                + " Item/Configure to LREQUESTER, the same user under the case-insensitive id strategy");
        WebResponse configure = get("LREQUESTER", job.getUrl() + "configure");
        assertEquals(200, configure.getStatusCode(), "LREQUESTER must open job-k's configure page through the own window");
    }

    // ---------------------------------------------------------------- helpers

    private WebResponse get(String userId, String path) throws Exception {
        return ApproverFormFixtures.get(j, userId, path);
    }

    /** True if {@code userId}'s grants page has a link to the request's detail page. */
    private boolean listsRequest(String userId, String id) throws Exception {
        Page page = ApproverFormFixtures.client(j, userId).getPage(new URL(j.getURL(), "batch-control/grants/"));
        assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: " + userId + " opens the grants page");
        assertTrue(page instanceof HtmlPage, "fixture: the grants page is HTML");
        HtmlPage list = (HtmlPage) page;
        String path = new URL(j.getURL(), "batch-control/grants/" + id + "/").getPath();
        for (HtmlAnchor anchor : list.getAnchors()) {
            String href = anchor.getHrefAttribute();
            if (!href.isEmpty() && list.getFullyQualifiedUrl(href).getPath().equals(path)) {
                return true;
            }
        }
        return false;
    }
}
