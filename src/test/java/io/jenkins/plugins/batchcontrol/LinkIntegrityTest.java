package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.LinkedHashMap;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "No link leads to a 404 or 403 page" (SPEC 6 usability line) and "a link inside the Batch
 * Control screens is shown only to a user who may open its target" (SPEC 2, #31), on the run
 * dashboard and the history screen. Matrix rows T-02-45 (e2e-03 DEF-11, note 126) and T-06-69
 * (DEF-23, note 127).
 *
 * <p>Users: {@code u1} requester with Item/Read and ViewHistory; {@code a2} the designated
 * approver (Approve + ViewHistory, no Item/Read); {@code a3} another approver, not designated on
 * the request, with the same permissions as a2.
 *
 * <p>Written from docs/SPEC.md, docs/reports/e2e-03.md and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class LinkIntegrityTest {

    private static final String[] SCREENS = {"batch-control/dashboard/", "batch-control/history/"};

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.VIEW_HISTORY).everywhere().to("u1")
                .grant(Jenkins.READ, BatchControlPermissions.APPROVE,
                        BatchControlPermissions.VIEW_HISTORY).everywhere().to("a2", "a3"));
    }

    /**
     * T-02-45 (DEF-11): an approved run of a job that a2/a3 cannot read appears on the dashboard
     * and the history screen. Every link those screens offer a2 and a3 opens (none answers 404 or
     * 403) — in particular the request id is a link only for a viewer who may open the request.
     * Control: u1, who may open everything, is offered the request link, and it opens.
     */
    @Test
    public void t_02_45_screensOfferNoLinkTheViewerCannotOpen() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a2", "a3"));
        cfg.save();
        FreeStyleProject job = j.createFreeStyleProject("secret-job");
        setBatchControl(job, new BatchControlJobProperty(true));

        RunRequest request;
        try (ACLContext ignored = as("u1")) {
            request = RunRequestService.get().create(job, new LinkedHashMap<>(), "secret run", "a2");
        }
        try (ACLContext ignored = as("a2")) {
            RunRequestService.get().approve(request.getId(), "ok");
        }
        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size(), "fixture: the approved run must have executed");

        String requestPath = "batch-control/requests/" + request.getId();
        for (String screen : SCREENS) {
            HtmlPage reader = UsabilityFixtures.htmlPage(j, "u1", screen);
            assertTrue(UsabilityFixtures.hasLinkTo(j, reader, requestPath), screen + ": control: u1 must be offered the request link "
                    + requestPath + "; anchors were " + UsabilityFixtures.resolvedHrefs(reader));
            UsabilityFixtures.assertNoDeadLinks(j, "u1", reader, screen + " as u1");

            for (String viewer : new String[] {"a2", "a3"}) {
                HtmlPage page = UsabilityFixtures.htmlPage(j, viewer, screen);
                assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: " + viewer + " reaches " + screen);
                assertTrue(page.asNormalizedText().contains("secret-job"), screen + ": fixture: the run must be shown to " + viewer);
                UsabilityFixtures.assertNoDeadLinks(j, viewer, page, screen + " as " + viewer);
            }
        }
    }

    /**
     * T-06-69 (DEF-23): once a build has been deleted (log rotation), the dashboard and the
     * history screen still show its run (SPEC 4) but no longer link to its build page, which would
     * be a 404. Control: the surviving build of the same job is still a link.
     */
    @Test
    public void t_06_69_deletedBuildIsNoLongerALink() throws Exception {
        // created while run control is off: activated at creation (D-45), uncontrolled
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("rotated-job"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true); // runs are recorded while a switch is on (SPEC 9)
        cfg.save();
        j.buildAndAssertSuccess(job);
        j.buildAndAssertSuccess(job);
        job.getBuildByNumber(1).delete();
        assertEquals(null, job.getBuildByNumber(1), "fixture: build #1 must be gone");

        for (String screen : SCREENS) {
            HtmlPage page = UsabilityFixtures.htmlPage(j, "u1", screen);
            assertTrue(UsabilityFixtures.hasLinkTo(j, page, "job/rotated-job/2"), screen + ": control: the surviving build #2 must be"
                    + " a link; anchors were " + UsabilityFixtures.resolvedHrefs(page));
            assertFalse(UsabilityFixtures.hasLinkTo(j, page, "job/rotated-job/1"), screen + ": the deleted build #1 must be shown as plain"
                    + " text, not as a link to a 404 page");
            UsabilityFixtures.assertNoDeadLinks(j, "u1", page, screen + " as u1");
        }
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
