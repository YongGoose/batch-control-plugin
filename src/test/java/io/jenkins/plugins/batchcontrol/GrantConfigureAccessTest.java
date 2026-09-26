package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SPEC item 8, the human-facing half of a change window: "승인되면 그 시간 동안 본인 계정으로 직접
 * 작업하며" — during the window the requester works on the target job with their own account.
 * Matrix rows T-08-14 (the configure screen opens), T-08-15 (the config XML can be read),
 * T-08-16 (the ACL reports the permissions core derives from Item/CONFIGURE), T-08-17 (all of
 * that reverts when the window closes) and T-08-18 (the implication handling may not widen the
 * scope or the action set).
 *
 * Why this class exists (matrix note 31): T-08-01 asserts only the SAVE half of a change window
 * (POST config.xml -> 200, persisted). Jenkins core gates *reading* a job's configuration —
 * both the /configure screen and GET config.xml — on {@code Item.EXTENDED_READ}, a permission
 * core ships disabled and treats as implied by {@code Item.CONFIGURE}. A grant that answers only
 * the exact permission it was asked to confer therefore passes T-08-01 while leaving the window
 * unusable in a browser: the user can save a configuration they cannot open.
 *
 * Time never passes for real: BatchClock is fixed and moved (matrix note 1).
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class GrantConfigureAccessTest {

    private static final Instant T0 = Instant.parse("2026-09-20T00:00:00Z");
    private static final int WINDOW_MINUTES = 30;

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private FreeStyleProject jobX; // the grant's scope
    private FreeStyleProject jobY; // outside the grant's scope

    @Before
    public void setUp() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());

        GlobalMatrixAuthorizationStrategy delegate = new GlobalMatrixAuthorizationStrategy();
        delegate.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1", "m1", "c1"}) {
            delegate.add(Jenkins.READ, PermissionEntry.user(userId));
            delegate.add(Item.READ, PermissionEntry.user(userId));
        }
        delegate.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        delegate.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        delegate.add(BatchControlPermissions.MANAGE, PermissionEntry.user("m1"));
        // c1 is the control: a real Item/Configure straight from the delegate, no grant involved.
        delegate.add(Item.CONFIGURE, PermissionEntry.user("c1"));
        j.jenkins.setAuthorizationStrategy(new BatchControlAuthorizationStrategy(delegate));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        jobX = j.createFreeStyleProject("batch-x");
        jobX.setDescription("base-x");
        jobY = j.createFreeStyleProject("batch-y");
        jobY.setDescription("base-y");

        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @After
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-08-14: inside the window the requester can OPEN the target job's configure screen —
     * GET job/batch-x/configure is 200 and carries the item config form.
     */
    @Test
    public void t_08_14_configureScreenOpensInsideWindow() throws Exception {
        JenkinsRule.WebClient wc = webClient().login("u1");
        assertEquals("before the grant the configure screen must be denied (Item/Read only)",
                403, status(get(wc, jobX.getUrl() + "configure")));

        grantConfigureOnJobX();

        Page page = get(wc, jobX.getUrl() + "configure");
        assertEquals("with an active CONFIGURE grant the requester must be able to OPEN the "
                + "configure screen of the scoped job", 200, status(page));
        assertTrue("the configure screen must be an HTML page, got " + page.getClass().getSimpleName(),
                page instanceof HtmlPage);

        HtmlPage html = (HtmlPage) page;
        HtmlForm configForm = configForm(html);
        assertNotNull("the configure screen must render the item config form (action configSubmit); "
                + "forms on the page: " + formActions(html), configForm);
        assertNotNull("the config form must carry the job's own fields",
                configForm.getTextAreaByName("description"));
    }

    /**
     * T-08-15: inside the window the requester can READ the target job's configuration —
     * GET job/batch-x/config.xml is 200 and returns the job's XML.
     */
    @Test
    public void t_08_15_configXmlIsReadableInsideWindow() throws Exception {
        JenkinsRule.WebClient wc = webClient().login("u1");
        assertEquals("before the grant the config XML must be denied (Item/Read only)",
                403, status(get(wc, jobX.getUrl() + "config.xml")));

        grantConfigureOnJobX();

        Page page = get(wc, jobX.getUrl() + "config.xml");
        assertEquals("with an active CONFIGURE grant the requester must be able to READ config.xml",
                200, status(page));
        String body = page.getWebResponse().getContentAsString();
        assertTrue("the response must be the job's configuration XML, got: " + excerpt(body),
                body.contains("<project") && body.contains("base-x"));
    }

    /**
     * T-08-16: an active CONFIGURE grant must make the ACL report the permissions Jenkins core
     * derives from Item/CONFIGURE, not only the literal granted permission. Item/EXTENDED_READ
     * (the read-only view of a job's configuration) is disabled by default in core and implied
     * by Item/CONFIGURE, so it must answer true inside the window.
     */
    @Test
    public void t_08_16_grantReportsPermissionsImpliedByConfigure() throws Exception {
        assertFalse("before the grant u1 must not hold EXTENDED_READ",
                hasPermissionAs("u1", jobX, Item.EXTENDED_READ));

        // Control: core really does derive EXTENDED_READ from a plain Item/Configure. If this
        // fixture assertion ever fails, this row can no longer measure anything and says so
        // loudly instead of passing silently.
        assertTrue("fixture: a holder of a direct Item/Configure must also hold EXTENDED_READ "
                + "(core treats it as implied by CONFIGURE)",
                hasPermissionAs("c1", jobX, Item.EXTENDED_READ));

        grantConfigureOnJobX();

        assertTrue("an active CONFIGURE grant must confer Item/Configure on the scoped job",
                hasPermissionAs("u1", jobX, Item.CONFIGURE));
        assertTrue("an active CONFIGURE grant must also report Item/EXTENDED_READ, which core "
                + "leaves disabled and treats as implied by Item/CONFIGURE — without it the "
                + "requester cannot open or read the configuration they are allowed to save",
                hasPermissionAs("u1", jobX, Item.EXTENDED_READ));
    }

    /**
     * T-08-17: when the window closes every read path reverts. Opening the configure screen,
     * reading config.xml and the EXTENDED_READ/CONFIGURE verdicts must all go back to denied —
     * the implied-permission handling may not leak a permanent permission.
     */
    @Test
    public void t_08_17_readPathsRevertWhenWindowCloses() throws Exception {
        grantConfigureOnJobX();

        JenkinsRule.WebClient inside = webClient().login("u1");
        assertEquals("fixture: the window must be open before it is closed",
                200, status(get(inside, jobX.getUrl() + "configure")));

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(WINDOW_MINUTES + 1)), ZoneOffset.UTC));

        JenkinsRule.WebClient wc = webClient().login("u1");
        assertEquals("past the expiry the configure screen must be denied again",
                403, status(get(wc, jobX.getUrl() + "configure")));
        assertEquals("past the expiry config.xml must be denied again",
                403, status(get(wc, jobX.getUrl() + "config.xml")));
        assertFalse("past the expiry Item/EXTENDED_READ must be false again (no permanent leak "
                + "through the implication handling)",
                hasPermissionAs("u1", jobX, Item.EXTENDED_READ));
        assertFalse("past the expiry Item/Configure must be false again",
                hasPermissionAs("u1", jobX, Item.CONFIGURE));
        assertFalse(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE));
        assertEquals("the job must be untouched", "base-x", jobX.getDescription());
    }

    /**
     * T-08-18 (over-grant guard for T-08-14..17): reporting the permissions implied by
     * Item/CONFIGURE must not widen the grant. Another job's configure screen and config XML
     * stay denied, and DELETE — an action this grant does not carry — stays denied too.
     */
    @Test
    public void t_08_18_impliedPermissionsDoNotWidenScopeOrActions() throws Exception {
        grantConfigureOnJobX();

        JenkinsRule.WebClient wc = webClient().login("u1");

        // scope boundary: the grant names batch-x only
        assertEquals("a JOB-scoped grant must not open another job's configure screen",
                403, status(get(wc, jobY.getUrl() + "configure")));
        assertEquals("a JOB-scoped grant must not open another job's config XML",
                403, status(get(wc, jobY.getUrl() + "config.xml")));
        assertFalse("EXTENDED_READ must not leak to a job outside the scope",
                hasPermissionAs("u1", jobY, Item.EXTENDED_READ));
        assertFalse("Item/Configure must not leak to a job outside the scope",
                hasPermissionAs("u1", jobY, Item.CONFIGURE));

        // action boundary: the grant carries CONFIGURE only
        assertFalse("a CONFIGURE-only grant must not report Item/Delete",
                hasPermissionAs("u1", jobX, Item.DELETE));
        assertFalse(GrantService.get().hasActiveGrant("u1", "batch-x", Item.DELETE));
        Page deletePage = wc.getPage(new WebRequest(
                wc.createCrumbedUrl(jobX.getUrl() + "doDelete"), HttpMethod.POST));
        assertTrue("a CONFIGURE-only grant must not open the delete path, got HTTP "
                + status(deletePage), status(deletePage) >= 400);
        assertNotNull("the job must survive the denied delete",
                j.jenkins.getItemByFullName("batch-x"));
        assertEquals("job Y must be untouched", "base-y", jobY.getDescription());
    }

    /**
     * Fixture control for T-08-14/15: a holder of a plain Item/Configure from the delegate can
     * open and read the configuration. This pins that the assertions above are reachable in this
     * Jenkins version at all, so a failure of T-08-14/15 is a grant problem and not a broken
     * expectation about core's configure screen.
     */
    @Test
    public void directConfigurePermissionOpensAndReadsConfiguration() throws Exception {
        JenkinsRule.WebClient wc = webClient().login("c1");

        Page screen = get(wc, jobX.getUrl() + "configure");
        assertEquals("a direct Item/Configure holder must be able to open the configure screen",
                200, status(screen));
        assertNotNull("the configure screen must render the item config form; forms on the page: "
                + formActions((HtmlPage) screen), configForm((HtmlPage) screen));

        Page xml = get(wc, jobX.getUrl() + "config.xml");
        assertEquals("a direct Item/Configure holder must be able to read config.xml",
                200, status(xml));
        assertTrue(xml.getWebResponse().getContentAsString().contains("base-x"));
    }

    // ---------------------------------------------------------------- helpers

    private JenkinsRule.WebClient webClient() {
        return j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
    }

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private boolean hasPermissionAs(String userId, FreeStyleProject target, Permission permission) {
        try (ACLContext ignored = as(userId)) {
            return target.hasPermission(permission);
        }
    }

    /** Creates a CONFIGURE grant request on batch-x as u1 and approves it as a1. */
    private Grant grantConfigureOnJobX() {
        GrantRequest request;
        try (ACLContext ignored = as("u1")) {
            request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.JOB, "batch-x"),
                    Arrays.asList(GrantAction.CONFIGURE), WINDOW_MINUTES,
                    "scheduled maintenance", "a1");
        }
        Grant grant;
        try (ACLContext ignored = as("a1")) {
            grant = GrantRequestService.get().approve(request.getId(), "ok");
        }
        assertNotNull("the approval must produce a grant", grant);
        assertTrue("fixture: the grant must be active for CONFIGURE on the scoped job",
                GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE));
        return grant;
    }

    private Page get(JenkinsRule.WebClient wc, String relative) throws Exception {
        return wc.getPage(new WebRequest(new URL(j.getURL(), relative), HttpMethod.GET));
    }

    private static int status(Page page) {
        return page.getWebResponse().getStatusCode();
    }

    private static HtmlForm configForm(HtmlPage page) {
        return page.getForms().stream()
                .filter(form -> form.getActionAttribute().contains("configSubmit"))
                .findFirst()
                .orElse(null);
    }

    private static List<String> formActions(HtmlPage page) {
        return page.getForms().stream()
                .map(HtmlForm::getActionAttribute)
                .collect(Collectors.toList());
    }

    private static String excerpt(String body) {
        return body.length() <= 400 ? body : body.substring(0, 400) + "...";
    }
}
