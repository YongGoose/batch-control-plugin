package io.jenkins.plugins.batchcontrol;

import hudson.cli.CLICommandInvoker;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.User;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import java.util.Collections;
import jenkins.security.QueueItemAuthenticatorConfiguration;
import org.jenkinsci.plugins.authorizeproject.AuthorizeProjectProperty;
import org.jenkinsci.plugins.authorizeproject.ProjectQueueItemAuthenticator;
import org.jenkinsci.plugins.authorizeproject.strategy.SpecificUsersAuthorizationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertApprovedRunQueuedExactlyOnce;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.post;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.requestAndApprove;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.secureWithRunControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6, acceptance line "With authorize-project configured, a build authorised as another
 * user does not bypass the gate" (#36). The job is configured to run as {@code admin}
 * (authorize-project's "run as specific user"); u1, who holds Job/Build and BatchControl/Request
 * only, triggers it. Rows T-06-40 .. T-06-42.
 */
@WithJenkins
public class PluginInteractionAuthorizeProjectTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        secureWithRunControl(j);
        User.getById("admin", true);
        String strategyId = j.jenkins.getDescriptorOrDie(SpecificUsersAuthorizationStrategy.class).getId();
        QueueItemAuthenticatorConfiguration.get().getAuthenticators()
                .add(new ProjectQueueItemAuthenticator(Collections.singletonMap(strategyId, true)));
        assertTrue(ProjectQueueItemAuthenticator.isConfigured(), "fixture: authorize-project's queue item authenticator must be configured");
    }

    /**
     * T-06-40: a job authorised to run as admin is still refused for u1's manual run, through the
     * build URL and through the CLI.
     */
    @Test
    @Tag("core")
    public void t_06_40_buildAuthorisedAsAnotherUserIsBlocked() throws Exception {
        FreeStyleProject job = runAsAdmin(j.createFreeStyleProject("ap-x"));
        setBatchControl(job, new BatchControlJobProperty(true));

        post(j, "u1", job.getUrl() + "build");
        assertBlocked(j, job, 1, 0);

        CLICommandInvoker.Result result = new CLICommandInvoker(j, "build").asUser("u1").invokeWithArgs("ap-x");
        assertTrue(result.returnCode() != 0, "the blocked CLI build must not exit 0");
        assertBlocked(j, job, 1, 0);
    }

    /**
     * T-06-41 (false-positive guard of T-06-40): on an uncontrolled job with the same
     * authorize-project configuration u1's build does run — the refusal in T-06-40 is the gate,
     * not authorize-project or a missing Job/Build permission.
     */
    @Test
    public void t_06_41_sameConfigurationBuildsOnUncontrolledJob() throws Exception {
        FreeStyleProject free = uncontrolled(runAsAdmin(j.createFreeStyleProject("ap-free")));

        post(j, "u1", free.getUrl() + "build");
        j.waitUntilNoActivity();

        assertEquals(1, free.getBuilds().size(), "fixture: u1 must be able to build an uncontrolled job configured by authorize-project");
    }

    /** T-06-42: an approved request on the authorize-project job is queued exactly once and starts. */
    @Test
    @Tag("core")
    public void t_06_42_approvedRunStarts() throws Exception {
        FreeStyleProject job = runAsAdmin(j.createFreeStyleProject("ap-approved"));
        setBatchControl(job, new BatchControlJobProperty(true));

        requestAndApprove(job);
        FreeStyleBuild build = (FreeStyleBuild) assertApprovedRunQueuedExactlyOnce(j, job);
        j.assertBuildStatusSuccess(build);
    }

    private static FreeStyleProject runAsAdmin(FreeStyleProject job) throws Exception {
        AuthorizeProjectProperty property = new AuthorizeProjectProperty(new SpecificUsersAuthorizationStrategy("admin"));
        job.addProperty(property);
        assertSame(property, job.getProperty(AuthorizeProjectProperty.class), "fixture: the authorize-project property must be the one the job reads back");
        return job;
    }
}
