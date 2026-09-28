package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.LinkedHashMap;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared fixture for the {@code PluginInteraction*Test} family (SPEC item 6 and 9 acceptance lines
 * citing #34 / #36). Each class of the family exercises one third-party plugin so that a slow
 * plugin does not slow the others down.
 *
 * <p>Users:
 * <ul>
 *   <li>{@code admin} — Overall/Administer.</li>
 *   <li>{@code u1} — Overall/Read, Job/Read, <b>Job/Build</b> and BatchControl/Request. Job/Build
 *       matters: without it core refuses the build URL with 403 on its own, and a blocking row
 *       would measure core's permission check instead of the run gate.</li>
 *   <li>{@code a1} — the configured approver.</li>
 * </ul>
 *
 * <p>Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
final class PluginInteractionFixtures {

    private PluginInteractionFixtures() {
        // utility class
    }

    /** Dummy realm + mock strategy + run control on with approver a1. */
    static void secureWithRunControl(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        assertTrue(BatchControlGlobalConfiguration.get().isRunControlEnabled(), "fixture: run control must be on");
    }

    static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    /** u1 requests a run of {@code job}, a1 approves it; returns the request. */
    static RunRequest requestAndApprove(Job<?, ?> job) {
        RunRequest request;
        try (ACLContext ignored = as("u1")) {
            request = RunRequestService.get().create(job, new LinkedHashMap<>(), "plugin interaction run", "a1");
        }
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(request.getId(), "ok");
        }
        return request;
    }

    /**
     * An approved request produces exactly one build, it carries the plugin's ApprovedCause and
     * nothing else is left in the queue.
     */
    static Run<?, ?> assertApprovedRunQueuedExactlyOnce(JenkinsRule j, Job<?, ?> job) throws Exception {
        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size(), job.getFullName() + ": the approved request must run exactly once");
        assertEquals(2, job.getNextBuildNumber(), job.getFullName() + ": exactly one build number may be consumed");
        Run<?, ?> build = job.getBuildByNumber(1);
        assertNotNull(build, job.getFullName() + ": build #1 must exist");
        assertNotNull(build.getCause(ApprovedCause.class), job.getFullName() + ": the run must carry the plugin's ApprovedCause");
        assertEquals(0, j.jenkins.getQueue().getItems().length, "nothing may be left in the queue");
        return build;
    }

    /**
     * The matrix's standard blocking triple: queue empty, next build number unchanged, and no
     * new build after the instance settles.
     */
    static void assertBlocked(JenkinsRule j, Job<?, ?> target, int nextBuildNumberBefore, int buildsBefore)
            throws Exception {
        assertEquals(0, j.jenkins.getQueue().getItems().length, target.getFullName() + ": the queue must stay empty");
        j.waitUntilNoActivity();
        assertEquals(nextBuildNumberBefore, target.getNextBuildNumber(), target.getFullName() + ": nextBuildNumber must not move");
        assertEquals(buildsBefore, target.getBuilds().size(), target.getFullName() + ": no new build may exist");
        assertEquals(0, j.jenkins.getQueue().getItems().length, target.getFullName() + ": the queue must still be empty after settling");
    }

    /** POST (with crumb) to {@code relativeUrl} as {@code userId}; returns the page, never throws on 4xx. */
    static Page post(JenkinsRule j, String userId, String relativeUrl) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        if (userId != null) {
            wc.login(userId);
        }
        return wc.getPage(new WebRequest(wc.createCrumbedUrl(relativeUrl), HttpMethod.POST));
    }

    /** GET {@code relativeUrl} as {@code userId} (null = anonymous); never throws on 4xx. */
    static Page get(JenkinsRule j, String userId, String relativeUrl) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.setRedirectEnabled(true);
        if (userId != null) {
            wc.login(userId);
        }
        return wc.goTo(relativeUrl, null);
    }
}
