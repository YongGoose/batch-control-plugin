package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.Future;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The safety net of D-31: making every new job approval-required by default must not stop
 * automatically generated CI. SPEC 6 refuses only human-originated causes and Pipeline Replay,
 * and lets timer, upstream, SCM and unclassified causes through, so a job that a multibranch
 * scan, a Job DSL seed or a timer owns keeps building on its own triggers; the only new friction
 * is a person pressing Build.
 *
 * Matrix rows T-08-26 (timer cause still builds), T-08-27 (upstream chain still builds),
 * T-08-28 (SCM trigger cause still builds) and T-08-29 (the human path is blocked — the intended
 * effect of D-31). The four rows sit in one class on purpose: together they are the statement
 * "D-31 does not stop automation, it stops people", and reading one without the others gives
 * half the contract.
 *
 * Fixture: every row creates its job through the product path (a {@code createItem} POST) with
 * run control on, so the job is the D-31 job and not a hand-configured stand-in. Whether the
 * default was applied by the plugin is T-08-19..24's assertion, not this class's; here the
 * fixture guarantees the controlled state (applying the property explicitly if the default has
 * not landed yet) and asserts it before firing any cause, so a row can never pass by measuring
 * an uncontrolled job.
 *
 * Written from docs/SPEC.md (items 6, 8, D-31), docs/DECISIONS.md and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
public class NewJobAutomationSafetyTest {

    private static final String MINIMAL_FREESTYLE_XML =
            "<?xml version='1.1' encoding='UTF-8'?><project>"
                    + "<description>generated job under run control</description>"
                    + "<builders/><publishers/><buildWrappers/></project>";

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private BatchControlGlobalConfiguration cfg;

    @Before
    public void setUp() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to("a1"));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-08-26 (D-31 + SPEC 6 timer policy): a newly created, controlled job still builds from
     * its own cron schedule without any request. This is the row that stands between D-31 and a
     * night of stopped batch jobs.
     */
    @Test
    public void t_08_26_newJobStillBuildsFromTimerCause() throws Exception {
        FreeStyleProject generated = newJobUnderRunControl("auto-timer");

        // matrix note 4: cron firing is reproduced by scheduling with a TimerTriggerCause
        Future<FreeStyleBuild> firing =
                generated.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        assertNotNull("SPEC 6: a timer cause must still pass on a newly created controlled job",
                firing);
        j.assertBuildStatusSuccess(firing);
        j.waitUntilNoActivity();
        assertEquals("the timer run must be the job's build #1", 1, generated.getBuilds().size());
    }

    /**
     * T-08-27 (D-31 + SPEC 6 upstream policy): a newly created, controlled job still builds when
     * an upstream job calls it, so a seed/parent chain keeps working. The upstream job is created
     * while run control is off so that only the downstream job's gating is measured.
     */
    @Test
    public void t_08_27_newJobStillBuildsFromUpstreamChain() throws Exception {
        setRunControl(false);
        WorkflowJob upstream = j.createProject(WorkflowJob.class, "auto-upstream");
        upstream.setDefinition(new CpsFlowDefinition(
                "build job: 'auto-downstream', wait: true", true));
        setRunControl(true);

        FreeStyleProject generated = newJobUnderRunControl("auto-downstream");

        j.buildAndAssertSuccess(upstream);
        j.waitUntilNoActivity();

        FreeStyleBuild downstream = generated.getBuildByNumber(1);
        assertNotNull("SPEC 6: an upstream cause must still pass on a newly created controlled job",
                downstream);
        j.assertBuildStatusSuccess(downstream);
    }

    /**
     * T-08-28 (D-31 + SPEC 6 cause policy): a newly created, controlled job still builds from an
     * SCM trigger, which is how multibranch and polling jobs run.
     */
    @Test
    public void t_08_28_newJobStillBuildsFromScmCause() throws Exception {
        FreeStyleProject generated = newJobUnderRunControl("auto-scm");

        Future<FreeStyleBuild> polling = generated.scheduleBuild2(0,
                new SCMTrigger.SCMTriggerCause("simulated polling detected changes"));
        assertNotNull("SPEC 6: an SCM trigger cause must still pass on a newly created "
                + "controlled job", polling);
        j.assertBuildStatusSuccess(polling);
        j.waitUntilNoActivity();
        assertEquals("the SCM run must be the job's build #1", 1, generated.getBuilds().size());
    }

    /**
     * T-08-29 (the intended effect of D-31): on the very same kind of job, the human paths are
     * refused — both the HTTP build POST and a user-caused submission. Without this row the three
     * pass-through rows above are satisfied by a build that gates nothing at all.
     */
    @Test
    public void t_08_29_humanRunOfTheNewJobIsBlocked() throws Exception {
        FreeStyleProject generated = newJobUnderRunControl("auto-human");

        JenkinsRule.WebClient u1 = j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false)
                .login("u1");
        Page response = u1.getPage(new WebRequest(
                u1.createCrumbedUrl(generated.getUrl() + "build"), HttpMethod.POST));
        assertTrue("D-31: a person pressing Build on a new job must need an approved request, "
                        + "got HTTP " + response.getWebResponse().getStatusCode(),
                response.getWebResponse().getStatusCode() >= 400);

        Future<FreeStyleBuild> userCaused;
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            userCaused = generated.scheduleBuild2(0, new Cause.UserIdCause());
        }
        assertNull("a user-caused submission must be refused at queue entry", userCaused);

        // matrix common blocking baseline
        assertEquals("the queue must stay empty", 0, j.jenkins.getQueue().getItems().length);
        j.waitUntilNoActivity();
        assertEquals("nextBuildNumber must not move", 1, generated.getNextBuildNumber());
        assertTrue("no build may have run", generated.getBuilds().isEmpty());
    }

    // ---------------------------------------------------------------- helpers

    private void setRunControl(boolean enabled) throws Exception {
        cfg.setRunControlEnabled(enabled);
        cfg.save();
    }

    /**
     * Creates a job through the product creation path while run control is on, and returns it in
     * a provably controlled state. D-31 is expected to have applied {@code approvalRequired=true}
     * by itself (T-08-19..24 assert exactly that); if it has not, the fixture applies the
     * property explicitly so that the cause-policy rows still measure the queue gate instead of
     * an unprotected job. The state is asserted either way, so no row can pass vacuously.
     */
    private FreeStyleProject newJobUnderRunControl(String name) throws Exception {
        JenkinsRule.WebClient admin = j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false)
                .login("admin");
        URL url = new URL(admin.createCrumbedUrl("createItem").toExternalForm() + "&name=" + name);
        WebRequest create = new WebRequest(url, HttpMethod.POST);
        create.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        create.setRequestBody(MINIMAL_FREESTYLE_XML);
        int code = admin.getPage(create).getWebResponse().getStatusCode();
        assertTrue("creating " + name + " must succeed, got HTTP " + code, code < 400);

        FreeStyleProject created = j.jenkins.getItemByFullName(name, FreeStyleProject.class);
        assertNotNull("the job " + name + " must have been created", created);

        BatchControlJobProperty property = created.getProperty(BatchControlJobProperty.class);
        if (property != null && !property.isApprovalRequired()) {
            created.removeProperty(BatchControlJobProperty.class);
            property = null;
        }
        if (property == null) {
            created.addProperty(new BatchControlJobProperty(true));
        }
        assertTrue("fixture: " + name + " must be approval-required before a cause is fired",
                isApprovalRequired(created));
        return created;
    }

    private static boolean isApprovalRequired(Job<?, ?> target) {
        BatchControlJobProperty property = target.getProperty(BatchControlJobProperty.class);
        return property != null && property.isApprovalRequired();
    }
}
