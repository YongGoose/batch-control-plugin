package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * SPEC item 8 (JIT temporary permission grants). Matrix rows T-08-01, T-08-02, T-08-03,
 * T-08-05, T-08-09, T-08-10, T-08-11, T-08-12 and the T-08-13 service-side guard.
 * (T-08-04 and T-08-07 need a restart and live in GrantRestartTest; T-08-06 and T-08-08
 * are AdministrativeMonitor rows and live in GrantMonitorsTest.)
 *
 * The delegate is a persistable GlobalMatrixAuthorizationStrategy wrapped in the plugin's
 * delegating BatchControlAuthorizationStrategy (ARCHITECTURE section 4): with no active
 * grant it must behave exactly like the delegate; grants apply only through the wrapper.
 *
 * Time never passes for real: BatchClock is fixed/moved (matrix note 1) and
 * ExpiryPeriodicWork.doRun() is invoked directly (matrix note 2).
 *
 * Written from docs/SPEC.md, docs/ARCHITECTURE.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class GrantServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-20T00:00:00Z");

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private FreeStyleProject jobX;
    private FreeStyleProject jobY;
    private BatchControlGlobalConfiguration cfg;

    @Before
    public void setUp() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());

        GlobalMatrixAuthorizationStrategy delegate = new GlobalMatrixAuthorizationStrategy();
        delegate.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "u2", "a1", "m1"}) {
            delegate.add(Jenkins.READ, PermissionEntry.user(userId));
            delegate.add(Item.READ, PermissionEntry.user(userId));
        }
        delegate.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        delegate.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        delegate.add(BatchControlPermissions.MANAGE, PermissionEntry.user("m1"));
        // u2 holds a direct Item/Delete from the delegate: the delete-veto row (T-08-10)
        delegate.add(Item.DELETE, PermissionEntry.user("u2"));
        j.jenkins.setAuthorizationStrategy(new BatchControlAuthorizationStrategy(delegate));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        jobX = j.createFreeStyleProject("batch-x");
        jobX.setDescription("base");
        jobY = j.createFreeStyleProject("batch-y");
        jobY.setDescription("base");

        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @After
    public void resetClock() {
        BatchClock.reset();
    }

    /** T-08-01: an approved Grant(u1, JOB X, CONFIGURE, 30min) lets u1 save X's config immediately. */
    @Test
    public void t_08_01_grantAllowsConfigureOnScopedJob() throws Exception {
        JenkinsRule.WebClient wc = webClient().login("u1");
        assertEquals("before the grant the delegate must deny (Item/Read only)",
                403, postConfigXml(wc, jobX, "changed-before-grant"));

        Grant grant = grantTo("u1", new GrantScope(GrantScope.Type.JOB, "batch-x"),
                Arrays.asList(GrantAction.CONFIGURE), 30);
        assertNotNull(grant);
        assertEquals("u1", grant.getUser());
        assertEquals("the grant must expire exactly durationMinutes after it was granted",
                T0.plus(Duration.ofMinutes(30)), grant.getExpiresAt());
        assertTrue(grant.isActiveAt(T0.plus(Duration.ofMinutes(29))));
        assertFalse("isActiveAt must be false past the expiry instant",
                grant.isActiveAt(T0.plus(Duration.ofMinutes(31))));

        assertEquals("with the active grant the config POST must succeed",
                200, postConfigXml(wc, jobX, "changed-inside-window"));
        assertEquals("the change must actually be saved",
                "changed-inside-window", jobX.getDescription());
    }

    /** T-08-02: the same grant gives no permission outside its scope (job Y stays 403). */
    @Test
    public void t_08_02_grantDoesNotCoverOtherJobs() throws Exception {
        grantTo("u1", new GrantScope(GrantScope.Type.JOB, "batch-x"),
                Arrays.asList(GrantAction.CONFIGURE), 30);

        JenkinsRule.WebClient wc = webClient().login("u1");
        assertEquals("a JOB-scoped grant must never leak to another job",
                403, postConfigXml(wc, jobY, "should-not-save"));
        assertEquals("job Y must be untouched", "base", jobY.getDescription());
    }

    /** T-08-03: past the expiry instant the very first permission check is denied (no timer involved). */
    @Test
    public void t_08_03_expiredGrantDeniedFromFirstCheck() throws Exception {
        grantTo("u1", new GrantScope(GrantScope.Type.JOB, "batch-x"),
                Arrays.asList(GrantAction.CONFIGURE), 30);

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(31)), ZoneOffset.UTC));

        JenkinsRule.WebClient wc = webClient().login("u1");
        assertEquals("the first check after expiry must already deny (check-time comparison, no timer)",
                403, postConfigXml(wc, jobX, "too-late"));
        assertEquals("base", jobX.getDescription());
        assertFalse(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE));
    }

    /** T-08-05: a Manage holder revokes the active grant; denial is immediate and a GRANT_REVOKE record remains. */
    @Test
    public void t_08_05_manageRevokeIsImmediateAndRecorded() throws Exception {
        Grant grant = grantTo("u1", new GrantScope(GrantScope.Type.JOB, "batch-x"),
                Arrays.asList(GrantAction.CONFIGURE), 30);
        assertTrue(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE));

        try (ACLContext ignored = as("m1")) {
            GrantService.get().revoke(grant.getId());
        }

        JenkinsRule.WebClient wc = webClient().login("u1");
        assertEquals("revocation must deny immediately, well before the expiry time",
                403, postConfigXml(wc, jobX, "after-revoke"));
        assertEquals("base", jobX.getDescription());
        assertFalse(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE));
        assertTrue("the revoked grant must not be listed as active",
                GrantService.get().listActive().stream().noneMatch(g -> g.getId().equals(grant.getId())));

        ChangeRecord record = lastRecord(ChangeType.GRANT_REVOKE);
        assertNotNull("revocation must leave a ChangeRecord(GRANT_REVOKE)", record);
        assertEquals("the record must carry the revoker", "m1", record.getUser());
        assertNotNull(record.getAt());
    }

    /** T-08-09: durationMinutes above maxGrantMinutes (default 240) rejects the grant request; so do 0 and negatives. */
    @Test
    public void t_08_09_durationAboveMaxOrNonPositiveRejected() throws Exception {
        assertRejected("a duration above maxGrantMinutes must reject creation", () -> {
            try (ACLContext ignored = as("u1")) {
                GrantRequestService.get().create(new GrantScope(GrantScope.Type.JOB, "batch-x"),
                        Arrays.asList(GrantAction.CONFIGURE), 300, "long maintenance", "a1");
            }
        });
        assertRejected("a zero duration must reject creation", () -> {
            try (ACLContext ignored = as("u1")) {
                GrantRequestService.get().create(new GrantScope(GrantScope.Type.JOB, "batch-x"),
                        Arrays.asList(GrantAction.CONFIGURE), 0, "zero duration", "a1");
            }
        });
        assertRejected("a negative duration must reject creation", () -> {
            try (ACLContext ignored = as("u1")) {
                GrantRequestService.get().create(new GrantScope(GrantScope.Type.JOB, "batch-x"),
                        Arrays.asList(GrantAction.CONFIGURE), -10, "negative duration", "a1");
            }
        });
        assertTrue("no request may be stored after rejected creations",
                GrantRequestService.get().list().isEmpty());
    }

    /** T-08-10: a CONFIGURE-only grant never covers DELETE; the un-granted action stays denied. */
    @Test
    public void t_08_10_ungrantedActionStaysDenied() throws Exception {
        grantTo("u1", new GrantScope(GrantScope.Type.JOB, "batch-x"),
                Arrays.asList(GrantAction.CONFIGURE), 30);

        JenkinsRule.WebClient wc = webClient().login("u1");
        Page page = wc.getPage(new WebRequest(
                wc.createCrumbedUrl(jobX.getUrl() + "doDelete"), HttpMethod.POST));
        assertTrue("delete must stay denied for a CONFIGURE-only grant, got HTTP "
                + page.getWebResponse().getStatusCode(),
                page.getWebResponse().getStatusCode() >= 400);
        assertNotNull("the job must survive the denied delete",
                j.jenkins.getItemByFullName("batch-x"));
        assertFalse(GrantService.get().hasActiveGrant("u1", "batch-x", Item.DELETE));
    }

    /**
     * T-08-10 (delete-veto semantics): with change control on, a non-admin whose DELEGATE grants
     * Item/Delete still cannot delete without an active DELETE grant (ItemListener.onCheckDelete veto).
     * With a DELETE grant the same user succeeds; the admin always passes.
     */
    @Test
    public void t_08_10_deleteVetoBlocksDelegateDeleteWithoutGrant() throws Exception {
        // u2 holds direct Item/Delete from the delegate but has no grant
        JenkinsRule.WebClient wc = webClient().login("u2");
        Page vetoed = wc.getPage(new WebRequest(
                wc.createCrumbedUrl(jobY.getUrl() + "doDelete"), HttpMethod.POST));
        assertTrue("without an active DELETE grant the delete must be vetoed even though the "
                + "delegate grants Item/Delete, got HTTP " + vetoed.getWebResponse().getStatusCode(),
                vetoed.getWebResponse().getStatusCode() >= 400);
        assertNotNull("the job must survive the vetoed delete",
                j.jenkins.getItemByFullName("batch-y"));

        // with an active DELETE grant the same user may delete
        grantTo("u2", new GrantScope(GrantScope.Type.JOB, "batch-y"),
                Arrays.asList(GrantAction.DELETE), 30);
        wc.getPage(new WebRequest(wc.createCrumbedUrl(jobY.getUrl() + "doDelete"), HttpMethod.POST));
        assertNull("with the DELETE grant the delete must pass",
                j.jenkins.getItemByFullName("batch-y"));

        // the admin is never vetoed (SPEC section 1: admin bypass is out of scope)
        FreeStyleProject adminTarget = j.createFreeStyleProject("admin-target");
        JenkinsRule.WebClient adminWc = webClient().login("admin");
        adminWc.getPage(new WebRequest(
                adminWc.createCrumbedUrl(adminTarget.getUrl() + "doDelete"), HttpMethod.POST));
        assertNull("the admin must be able to delete without any grant",
                j.jenkins.getItemByFullName("admin-target"));
    }

    /** T-08-11: a FOLDER-scoped [CREATE, CONFIGURE] grant works inside the folder only. */
    @Test
    public void t_08_11_folderScopeWorksInsideFolderOnly() throws Exception {
        Folder team = j.jenkins.createProject(Folder.class, "team");
        Folder batch = team.createProject(Folder.class, "batch");
        Folder other = team.createProject(Folder.class, "other");
        FreeStyleProject inner = batch.createProject(FreeStyleProject.class, "inner");
        inner.setDescription("base");

        grantTo("u1", new GrantScope(GrantScope.Type.FOLDER, "team/batch"),
                Arrays.asList(GrantAction.CREATE, GrantAction.CONFIGURE), 30);

        JenkinsRule.WebClient wc = webClient().login("u1");

        // CREATE inside the folder (checked on the parent folder's ACL) must pass
        int createInside = postCreateItem(wc, "job/team/job/batch/", "new-inner");
        assertTrue("creating a job inside the granted folder must succeed, got HTTP " + createInside,
                createInside < 400);
        assertNotNull(j.jenkins.getItemByFullName("team/batch/new-inner"));

        // CONFIGURE inside the folder must pass
        assertEquals(200, postConfigXml(wc, inner, "changed-inside"));
        assertEquals("changed-inside", inner.getDescription());

        // outside the folder scope: CREATE denied (sibling folder and root)
        assertEquals("creating outside the folder scope must be denied",
                403, postCreateItem(wc, "job/team/job/other/", "escape-job"));
        assertNull(j.jenkins.getItemByFullName("team/other/escape-job"));
        assertEquals("creating at the root must be denied",
                403, postCreateItem(wc, "", "root-escape"));
        assertNull(j.jenkins.getItemByFullName("root-escape"));

        // outside the folder scope: CONFIGURE denied
        assertEquals(403, postConfigXml(wc, jobX, "should-not-save"));
        assertEquals("base", jobX.getDescription());
    }

    /** T-08-12: a PENDING GrantRequest past pendingTimeoutHours becomes EXPIRED via the periodic work. */
    @Test
    public void t_08_12_pendingGrantRequestExpires() throws Exception {
        cfg.setPendingTimeoutHours(1);
        cfg.save();

        GrantRequest request;
        try (ACLContext ignored = as("u1")) {
            request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.JOB, "batch-x"),
                    Arrays.asList(GrantAction.CONFIGURE), 30, "maintenance window", "a1");
        }
        assertEquals(RequestStatus.PENDING, request.getStatus());

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofHours(2)), ZoneOffset.UTC));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();

        assertEquals("a PENDING grant request past the timeout must become EXPIRED",
                RequestStatus.EXPIRED, GrantRequestService.get().load(request.getId()).getStatus());
        try (ACLContext ignored = as("a1")) {
            assertRejected("an expired grant request must not be approvable",
                    () -> GrantRequestService.get().approve(request.getId(), "too late"));
        }
        assertFalse("no grant may exist for the expired request",
                GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE));
    }

    /**
     * Supporting check for SPEC 8 + SPEC 3: grant request approver validation mirrors run
     * requests (the T-08-13 HTTP 403 row itself lives in GrantWebTest).
     */
    @Test
    public void grantRequestApproverValidationMirrorsRunRequests() throws Exception {
        assertRejected("an approver outside the global list must reject grant request creation", () -> {
            try (ACLContext ignored = as("u1")) {
                GrantRequestService.get().create(new GrantScope(GrantScope.Type.JOB, "batch-x"),
                        Arrays.asList(GrantAction.CONFIGURE), 30, "maintenance", "u2");
            }
        });
        assertTrue(GrantRequestService.get().list().isEmpty());
    }

    // ---------------------------------------------------------------- helpers

    private JenkinsRule.WebClient webClient() {
        return j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
    }

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    /** Creates a grant request as {@code userId} and approves it as a1, returning the Grant. */
    private Grant grantTo(String userId, GrantScope scope, List<GrantAction> actions, int minutes) {
        GrantRequest request;
        try (ACLContext ignored = as(userId)) {
            request = GrantRequestService.get().create(scope, actions, minutes, "scheduled maintenance", "a1");
        }
        try (ACLContext ignored = as("a1")) {
            return GrantRequestService.get().approve(request.getId(), "ok");
        }
    }

    /** POSTs the job's config.xml with the description swapped in; returns the HTTP status. */
    private int postConfigXml(JenkinsRule.WebClient wc, FreeStyleProject target, String newDescription)
            throws Exception {
        String xml = target.getConfigFile().asString()
                .replace("<description>" + target.getDescription() + "</description>",
                        "<description>" + newDescription + "</description>");
        WebRequest request = new WebRequest(
                wc.createCrumbedUrl(target.getUrl() + "config.xml"), HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    /** POSTs createItem with a minimal freestyle config under the given container URL prefix. */
    private int postCreateItem(JenkinsRule.WebClient wc, String containerUrl, String name) throws Exception {
        java.net.URL url = new java.net.URL(
                wc.createCrumbedUrl(containerUrl + "createItem").toExternalForm() + "&name=" + name);
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody("<?xml version='1.1' encoding='UTF-8'?>"
                + "<project><builders/><publishers/><buildWrappers/></project>");
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private ChangeRecord lastRecord(ChangeType type) {
        return FileStore.get().listChangeRecords(YearMonth.now()).stream()
                .filter(rec -> rec.getType() == type)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    /** SPEC validation failures surface as IllegalArgumentException or hudson.model.Failure. */
    private static void assertRejected(String message, ThrowingRunnable action) {
        boolean rejected = false;
        try {
            action.run();
        } catch (RuntimeException expected) {
            rejected = true;
        } catch (Throwable other) {
            throw new AssertionError(message + " - unexpected exception " + other, other);
        }
        assertTrue(message, rejected);
    }
}
