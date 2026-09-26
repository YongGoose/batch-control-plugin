package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.GlobalMatrixAuthorizationStrategy;
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
import java.io.File;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8 restart durability of grants. Matrix rows T-08-04 (still valid after a restart
 * before expiry) and T-08-07 (gone immediately after a restart past expiry).
 *
 * Split from GrantServiceTest because these rows need JenkinsSessionExtension; the downtime is
 * modeled by moving BatchClock between the two sessions (matrix notes 1 and 3).
 *
 * Written from docs/SPEC.md, docs/ARCHITECTURE.md section 5 and docs/TEST-MATRIX.md only.
 */
public class GrantRestartTest {

    private static final Instant T0 = Instant.parse("2026-09-20T09:00:00Z");
    private static final String JOB = "batch-x";

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    private String grantId;

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /** T-08-04: an active grant survives a restart while still inside its window. */
    @Test
    public void t_08_04_activeGrantSurvivesRestartBeforeExpiry() throws Throwable {
        session.then(r -> {
            prepare(r);
            Grant grant = grantU1Configure(30);
            grantId = grant.getId();

            assertTrue(new File(r.jenkins.getRootDir(),
                            "batch-control/grants/" + grantId + ".xml").isFile(), "the grant must be persisted under batch-control/grants/<id>.xml");
            assertEquals(200, postConfigXml(r, "u1", "before-restart"), "sanity: the grant must work before the restart");
        });

        // the controller is down for 10 minutes: still inside the 30-minute window
        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(10)), ZoneOffset.UTC));

        session.then(r -> {
            assertTrue(GrantService.get().hasActiveGrant("u1", JOB, Item.CONFIGURE), "the grant must still be active after the restart (before expiry)");
            assertTrue(GrantService.get().listActive().stream()
                    .anyMatch(g -> g.getId().equals(grantId)));
            assertEquals(200, postConfigXml(r, "u1", "after-restart"), "u1 must still be able to save the config after the restart");
            FreeStyleProject job = r.jenkins.getItemByFullName(JOB, FreeStyleProject.class);
            assertEquals("after-restart", job.getDescription());
        });
    }

    /** T-08-07: a grant whose expiry passed during the downtime is gone immediately after the restart. */
    @Test
    public void t_08_07_grantExpiredDuringDowntimeIsGoneAfterRestart() throws Throwable {
        session.then(r -> {
            prepare(r);
            grantId = grantU1Configure(30).getId();
            assertEquals(200, postConfigXml(r, "u1", "inside-window"));
        });

        // the controller is down for 31 minutes: the 30-minute window ends while it is down
        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(31)), ZoneOffset.UTC));

        session.then(r -> {
            assertFalse(GrantService.get().hasActiveGrant("u1", JOB, Item.CONFIGURE), "a grant expired during the downtime must be gone immediately");
            assertTrue(GrantService.get().listActive().stream()
                    .noneMatch(g -> g.getId().equals(grantId)));
            assertEquals(403, postConfigXml(r, "u1", "too-late"), "the very first config POST after the restart must be denied");
            FreeStyleProject job = r.jenkins.getItemByFullName(JOB, FreeStyleProject.class);
            assertEquals("inside-window", job.getDescription(), "the description must be unchanged after the denied write");
        });
    }

    // ---------------------------------------------------------------- helpers

    /** Installs a persistable wrapped matrix strategy, change control and the target job. */
    private void prepare(JenkinsRule r) throws Exception {
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));

        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        GlobalMatrixAuthorizationStrategy delegate = new GlobalMatrixAuthorizationStrategy();
        delegate.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            delegate.add(Jenkins.READ, PermissionEntry.user(userId));
            delegate.add(Item.READ, PermissionEntry.user(userId));
        }
        delegate.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        delegate.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        r.jenkins.setAuthorizationStrategy(new BatchControlAuthorizationStrategy(delegate));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        FreeStyleProject job = r.createFreeStyleProject(JOB);
        job.setDescription("base");
    }

    private Grant grantU1Configure(int minutes) {
        GrantRequest request;
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.JOB, JOB),
                    Arrays.asList(GrantAction.CONFIGURE), minutes, "restart durability window", "a1");
        }
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            return GrantRequestService.get().approve(request.getId(), "ok");
        }
    }

    private int postConfigXml(JenkinsRule r, String userId, String newDescription) throws Exception {
        FreeStyleProject job = r.jenkins.getItemByFullName(JOB, FreeStyleProject.class);
        String xml = job.getConfigFile().asString()
                .replace("<description>" + job.getDescription() + "</description>",
                        "<description>" + newDescription + "</description>");
        JenkinsRule.WebClient wc = r.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false)
                .login(userId);
        WebRequest request = new WebRequest(
                wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }
}
