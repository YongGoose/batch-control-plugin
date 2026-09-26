package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Red-team rows around grant-window abuse. Matrix rows T-RT-05 (D-17: jobs created inside an
 * active grant window default to approvalRequired=true when run control is on), T-RT-06
 * (writes are re-checked per write across the expiry boundary) and T-RT-20 (bounded version:
 * 30 rapid config.xml POSTs keep the change records consistent).
 *
 * Written from docs/SPEC.md (items 6, 8, 9 and D-17), docs/ARCHITECTURE.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class GrantWindowAbuseTest {

    private static final Instant T0 = Instant.parse("2026-09-20T00:00:00Z");

    private JenkinsRule j;

    private Folder batchFolder;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());

        GlobalMatrixAuthorizationStrategy delegate = new GlobalMatrixAuthorizationStrategy();
        delegate.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            delegate.add(Jenkins.READ, PermissionEntry.user(userId));
            delegate.add(Item.READ, PermissionEntry.user(userId));
        }
        delegate.add(Item.BUILD, PermissionEntry.user("u1"));
        delegate.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        delegate.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(new BatchControlAuthorizationStrategy(delegate));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        Folder team = j.jenkins.createProject(Folder.class, "team");
        batchFolder = team.createProject(Folder.class, "batch");

        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-RT-05 (D-17): a job created by u1 inside an active CREATE/CONFIGURE grant window gets
     * approvalRequired=true automatically, so a manual run after the window expired is blocked.
     * (Timer runs still follow the SPEC 6 timer policy; SPEC grants no stronger cron block, so
     * none is asserted here.)
     */
    @Test
    public void t_rt_05_jobCreatedInGrantWindowDefaultsToApprovalRequired() throws Exception {
        grantTo("u1", new GrantScope(GrantScope.Type.FOLDER, "team/batch"),
                Arrays.asList(GrantAction.CREATE, GrantAction.CONFIGURE), 30);

        // u1 plants a cron job inside the grant window
        JenkinsRule.WebClient wc = webClient().login("u1");
        URL createUrl = new URL(wc.createCrumbedUrl("job/team/job/batch/createItem")
                .toExternalForm() + "&name=nightly");
        WebRequest create = new WebRequest(createUrl, HttpMethod.POST);
        create.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        create.setRequestBody("<?xml version='1.1' encoding='UTF-8'?><project>"
                + "<triggers><hudson.triggers.TimerTrigger><spec>0 3 * * *</spec>"
                + "</hudson.triggers.TimerTrigger></triggers>"
                + "<builders/><publishers/><buildWrappers/></project>");
        int createCode = wc.getPage(create).getWebResponse().getStatusCode();
        assertTrue(createCode < 400, "creating the job inside the grant window must succeed, got HTTP " + createCode);

        FreeStyleProject planted = j.jenkins.getItemByFullName("team/batch/nightly", FreeStyleProject.class);
        assertNotNull(planted);
        BatchControlJobProperty property = planted.getProperty(BatchControlJobProperty.class);
        assertNotNull(property, "D-17: a job created inside an active grant window must automatically "
                + "carry the plugin job property");
        assertTrue(property.isApprovalRequired(), "D-17: approvalRequired must default to true for a job created inside an "
                + "active grant window");

        // the window expires; a manual run must not bypass approval
        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(31)), ZoneOffset.UTC));
        Page blocked = wc.getPage(new WebRequest(
                wc.createCrumbedUrl(planted.getUrl() + "build"), HttpMethod.POST));
        assertEquals(400, blocked.getWebResponse().getStatusCode(), "the manual run of the planted job must be blocked with guidance");
        assertTrue(blocked.getWebResponse().getContentAsString()
                        .toLowerCase(Locale.ROOT).contains("approval"), "the block must explain that approval is required");

        j.waitUntilNoActivity();
        assertTrue(planted.getBuilds().isEmpty(), "no build may have run");
        assertEquals(1, planted.getNextBuildNumber());
        assertEquals(0, j.jenkins.getQueue().getItems().length);
    }

    /**
     * T-RT-06: a batch of config saves spans the grant expiry; every write is re-checked
     * individually, so writes before the boundary succeed and writes after it are 403.
     */
    @Test
    public void t_rt_06_writesAfterExpiryAreRejectedPerWrite() throws Exception {
        FreeStyleProject[] jobs = new FreeStyleProject[4];
        String[] names = {"job-a", "job-b", "job-c", "job-d"};
        for (int i = 0; i < names.length; i++) {
            jobs[i] = batchFolder.createProject(FreeStyleProject.class, names[i]);
            jobs[i].setDescription("base");
        }

        grantTo("u1", new GrantScope(GrantScope.Type.FOLDER, "team/batch"),
                Arrays.asList(GrantAction.CONFIGURE), 30);
        JenkinsRule.WebClient wc = webClient().login("u1");

        // inside the window: the first two writes of the batch succeed
        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(10)), ZoneOffset.UTC));
        assertEquals(200, postConfigXml(wc, jobs[0], "changed"));
        assertEquals(200, postConfigXml(wc, jobs[1], "changed"));

        // the window expires mid-batch: every later write must be re-checked and rejected
        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(31)), ZoneOffset.UTC));
        assertEquals(403, postConfigXml(wc, jobs[2], "changed"), "a write after expiry must be rejected even though the batch started "
                + "inside the window (no ride-along on an entry-time check)");
        assertEquals(403, postConfigXml(wc, jobs[3], "changed"));

        assertEquals("changed", jobs[0].getDescription());
        assertEquals("changed", jobs[1].getDescription());
        assertEquals("base", jobs[2].getDescription(), "writes after expiry must not have been applied");
        assertEquals("base", jobs[3].getDescription());
    }

    /**
     * T-RT-20 (bounded): 30 rapid config.xml POSTs under an active CONFIGURE grant keep the
     * change records complete and baseline-consistent, and do not block another job's record.
     */
    @Test
    public void t_rt_20_rapidConfigSavesKeepRecordsConsistent() throws Exception {
        FreeStyleProject hot = j.createFreeStyleProject("hot-job");
        hot.setDescription("rev-00");
        FreeStyleProject side = j.createFreeStyleProject("side-job");
        side.setDescription("side-base");

        grantTo("u1", new GrantScope(GrantScope.Type.JOB, "hot-job"),
                Arrays.asList(GrantAction.CONFIGURE), 60);
        JenkinsRule.WebClient u1 = webClient().login("u1");
        JenkinsRule.WebClient admin = webClient().login("admin");

        for (int i = 1; i <= 30; i++) {
            String next = String.format("rev-%02d", i);
            assertEquals(200, postConfigXml(u1, hot, next), "save #" + i + " must succeed");
            if (i == 15) {
                // another job's change interleaves with the rapid loop
                assertEquals(200, postConfigXml(admin, side, "side-changed"));
            }
        }
        assertEquals("rev-30", hot.getDescription());

        List<ChangeRecord> hotRecords = FileStore.get().listChangeRecords(YearMonth.now()).stream()
                .filter(rec -> rec.getType() == ChangeType.CONFIGURE)
                .filter(rec -> "hot-job".equals(rec.getTarget()))
                .collect(Collectors.toList());
        assertTrue(hotRecords.size() >= 30, "all 30 rapid saves must be recorded without loss, found " + hotRecords.size());

        List<String> diffs = hotRecords.stream()
                .map(ChangeRecord::getDiff)
                .filter(diff -> diff != null)
                .collect(Collectors.toList());
        for (int i = 1; i <= 30; i++) {
            String added = String.format("rev-%02d", i);
            String removed = String.format("rev-%02d", i - 1);
            boolean chained = diffs.stream()
                    .anyMatch(diff -> diff.contains(added) && diff.contains(removed));
            assertTrue(chained, "the diff for save #" + i + " must chain against the previous baseline ("
                    + removed + " -> " + added + ")");
        }

        assertTrue(FileStore.get().listChangeRecords(YearMonth.now()).stream()
                        .anyMatch(rec -> rec.getType() == ChangeType.CONFIGURE
                                && "side-job".equals(rec.getTarget())), "the interleaved change of the other job must be recorded too "
                + "(the record pipeline must not block other jobs)");
    }

    // ---------------------------------------------------------------- helpers

    private JenkinsRule.WebClient webClient() {
        return j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
    }

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private Grant grantTo(String userId, GrantScope scope, List<GrantAction> actions, int minutes) {
        GrantRequest request;
        try (ACLContext ignored = as(userId)) {
            request = GrantRequestService.get().create(scope, actions, minutes,
                    "batch maintenance window", "a1");
        }
        try (ACLContext ignored = as("a1")) {
            return GrantRequestService.get().approve(request.getId(), "ok");
        }
    }

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
}
