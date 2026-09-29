package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.Failure;
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
import java.util.Locale;
import java.util.concurrent.Future;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The other half of D-34: a lock that cannot be opened is not a feature, and the lock must not
 * be wider than the decision.
 *
 * <p>D-34 (SPEC item 8) makes a newly created job start with {@code approvalRequired},
 * {@code blockTimer} and {@code blockUpstream} all on, so that creating a job does not put it
 * into service; {@link NewJobActivationLockTest} measures that lock. This class measures what
 * happens next and what stays untouched:
 *
 * <ul>
 *   <li>T-08-26 — turning {@code blockTimer} off in the job's configuration brings the job into
 *       service: it then builds on its own cron schedule while {@code approvalRequired} stays on,
 *       so people still need an approval but the schedule runs. This is the way out D-34 names.</li>
 *   <li>T-08-27 — the same for {@code blockUpstream}: with the switch off, an upstream job's
 *       {@code build} step starts the job again, so a seed/parent chain can be brought back.</li>
 *   <li>T-08-28 — SCM-triggered runs (multibranch, polling) still pass on a fully locked new job:
 *       D-34 locks the timer and upstream doors only, and SPEC 6 keeps the SCM cause open.</li>
 *   <li>T-08-29 — the human path remains refused with guidance (D-31, unchanged by D-34); this
 *       row is what keeps T-08-26/27 from being satisfied by a gate that blocks nothing.</li>
 * </ul>
 *
 * <p>History of these rows: before D-34, T-08-26/27 asserted that a <em>newly created</em> job
 * still builds from a timer and from an upstream call — the D-31 safety net for unattended CI.
 * D-34 reverses exactly that contract (creation must not activate), so the rows were rewritten
 * to measure the property they were really protecting — that a job's automatic triggers can be
 * live — at the point where D-34 now puts it: after somebody turns the switches off.
 *
 * <p>Fixture: every row creates its job through the product path (a {@code createItem} POST) with
 * run control on, then states the job-level switches explicitly (a single
 * {@link BatchControlJobProperty}, see {@link BatchControlFixtures}) and asserts them before any
 * cause is fired. Unlocking through the property is the same convention T-OS-05/06 use for an
 * administrator flipping a switch on a job; the recording of that configuration change is SPEC
 * item 9 and is covered by T-09-01/02. So no row can pass by measuring a job whose state it did
 * not establish, and the rows stay meaningful both before and after D-34 lands.
 *
 * <p>Written from docs/SPEC.md (items 6, 8, 9 and D-25, D-31, D-34), docs/DECISIONS.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class NewJobAutomationSafetyTest {

    private static final String MINIMAL_FREESTYLE_XML =
            "<?xml version='1.1' encoding='UTF-8'?><project>"
                    + "<description>generated job under run control</description>"
                    + "<builders/><publishers/><buildWrappers/></project>";

    private JenkinsRule j;

    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
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
     * T-08-26 (D-34 activation path, SPEC 6 timer policy): a job created under run control starts
     * locked out of its own schedule; turning {@code blockTimer} off in its configuration brings
     * it into service, and it then builds from its cron without any request —
     * {@code approvalRequired} stays on, so the switch releases the schedule and nothing else.
     * Without this row D-34 would be a lock with no key.
     */
    @Test
    public void t_08_26_unlockedJobBuildsFromTimerCause() throws Exception {
        FreeStyleProject generated = newJobUnlockedForAutomation("auto-timer");

        // matrix note 4: cron firing is reproduced by scheduling with a TimerTriggerCause
        Future<FreeStyleBuild> firing =
                generated.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        assertNotNull(firing, "D-34: a job whose blockTimer has been turned off must build from its timer");
        j.assertBuildStatusSuccess(firing);
        j.waitUntilNoActivity();
        assertEquals(1, generated.getBuilds().size(), "the timer run must be the job's build #1");
        assertTrue(isApprovalRequired(generated), "the unlock must release the schedule only: approvalRequired stays on");
    }

    /**
     * T-08-27 (D-34 activation path, SPEC 6 upstream policy): the same for the upstream door.
     * With {@code blockUpstream} turned off, an upstream job's {@code build} step starts the job
     * again, so a seed/parent chain can be brought back into service. The upstream job is created
     * while run control is off so that only the downstream job's gating is measured.
     */
    @Test
    public void t_08_27_unlockedJobBuildsFromUpstreamChain() throws Exception {
        setRunControl(false);
        WorkflowJob upstream = j.createProject(WorkflowJob.class, "auto-upstream");
        upstream.setDefinition(new CpsFlowDefinition(
                "build job: 'auto-downstream', wait: true", true));
        setRunControl(true);

        FreeStyleProject generated = newJobUnlockedForAutomation("auto-downstream");

        j.buildAndAssertSuccess(upstream);
        j.waitUntilNoActivity();

        FreeStyleBuild downstream = generated.getBuildByNumber(1);
        assertNotNull(downstream, "D-34: a job whose blockUpstream has been turned off must build when an "
                + "upstream job calls it");
        j.assertBuildStatusSuccess(downstream);
        assertTrue(isApprovalRequired(generated), "the unlock must release the upstream door only: approvalRequired stays on");
    }

    /**
     * T-08-28 (D-34 scope + SPEC 6a as amended by D-46 (b)): an SCM cause is an unattended cause.
     * On a fully locked new job it is refused until the job is activated — clearing no switch
     * could open it (D-46) — and once the job is activated it builds with {@code blockTimer} and
     * {@code blockUpstream} still on: the D-34 lock does not bleed into the SCM cause, which no
     * job switch governs. (Before D-46 the row asserted the SCM cause passed on a new job with no
     * activation; note 103.)
     */
    @Test
    public void t_08_28_lockedNewJobBuildsFromScmCauseOnceActivated() throws Exception {
        FreeStyleProject generated = newLockedJobUnderRunControl("auto-scm");

        assertNull(generated.scheduleBuild2(0,
                        new SCMTrigger.SCMTriggerCause("simulated polling detected changes")),
                "D-46: an SCM cause must not start a new job that is not activated");
        assertEquals(0, j.jenkins.getQueue().getItems().length, "the queue must stay empty");
        j.waitUntilNoActivity();
        assertEquals(1, generated.getNextBuildNumber(), "nextBuildNumber must not move");
        assertTrue(generated.getBuilds().isEmpty(), "no build may have run");

        BatchControlFixtures.activate(generated);
        BatchControlJobProperty property = generated.getProperty(BatchControlJobProperty.class);
        assertTrue(property.isBlockTimer() && property.isBlockUpstream(), "premise: the D-34 switches are still on");
        Future<FreeStyleBuild> polling = generated.scheduleBuild2(0,
                new SCMTrigger.SCMTriggerCause("simulated polling detected changes"));
        assertNotNull(polling, "SPEC 6: an SCM cause of an activated job must pass, whatever blockTimer/blockUpstream say");
        j.assertBuildStatusSuccess(polling);
        j.waitUntilNoActivity();
        assertEquals(1, generated.getBuilds().size(), "the SCM run must be the job's build #1");
    }

    /**
     * T-08-29 (the intended effect of D-31, unchanged by D-34): on the very same kind of job, the
     * human paths are refused — both the HTTP build POST and a user-caused submission. Without
     * this row the pass-through rows above are satisfied by a build that gates nothing at all.
     *
     * Assertion technique: SPEC 6 requires the refusal of a human-originated cause to carry
     * guidance and a link to the run-request screen ("조용한 실패 금지" — no silent failure), so
     * the refusal surfaces out of the queue decision as {@link Failure}, which Jenkins renders
     * as the guidance page for the HTTP path. This row therefore asserts that the exception is
     * raised and that its message carries both halves of that acceptance criterion, instead of
     * calling {@code scheduleBuild2} and letting the guidance escape as a test error.
     */
    @Test
    public void t_08_29_humanRunOfTheNewJobIsBlocked() throws Exception {
        FreeStyleProject generated = newLockedJobUnderRunControl("auto-human");

        JenkinsRule.WebClient u1 = j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false)
                .login("u1");
        Page response = u1.getPage(new WebRequest(
                u1.createCrumbedUrl(generated.getUrl() + "build"), HttpMethod.POST));
        assertTrue(response.getWebResponse().getStatusCode() >= 400, "D-31: a person pressing Build on a new job must need an approved request, "
                        + "got HTTP " + response.getWebResponse().getStatusCode());
        assertTrue(response.getWebResponse().getContentAsString()
                        .toLowerCase(Locale.ROOT).contains("approval"), "SPEC 6 forbids a silent failure: the refused POST must explain that an "
                        + "approval is required");

        Failure guidance = null;
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            Future<FreeStyleBuild> admitted = generated.scheduleBuild2(0, new Cause.UserIdCause());
            assertNull(admitted, "a user-caused submission must be refused at queue entry");
        } catch (Failure expectedGuidance) {
            guidance = expectedGuidance;
        }
        assertNotNull(guidance, "SPEC 6: a human-originated cause must be refused with guidance, so the "
                + "queue decision raises Failure rather than dropping the submission silently");
        String message = guidance.getMessage();
        assertNotNull(message, "the guidance Failure must carry a message");
        assertTrue(message.toLowerCase(Locale.ROOT).contains("approval"), "the guidance must state that an approval is required, got: " + message);
        assertTrue(message.contains(generated.getUrl() + "batch-control"), "the guidance must point at the job's own run-request screen, got: " + message);

        // matrix common blocking baseline
        assertEquals(0, j.jenkins.getQueue().getItems().length, "the queue must stay empty");
        j.waitUntilNoActivity();
        assertEquals(1, generated.getNextBuildNumber(), "nextBuildNumber must not move");
        assertTrue(generated.getBuilds().isEmpty(), "no build may have run");
        assertNull(generated.getLastBuild(), "neither human path may leave a build behind");
    }

    // ---------------------------------------------------------------- helpers

    private void setRunControl(boolean enabled) throws Exception {
        cfg.setRunControlEnabled(enabled);
        cfg.save();
    }

    /**
     * Creates a job through the product creation path (a {@code createItem} POST) while run
     * control is on, so the job is the D-34 job and not a hand-configured stand-in.
     */
    private FreeStyleProject createJobUnderRunControl(String name) throws Exception {
        JenkinsRule.WebClient admin = j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false)
                .login("admin");
        URL url = new URL(admin.createCrumbedUrl("createItem").toExternalForm() + "&name=" + name);
        WebRequest create = new WebRequest(url, HttpMethod.POST);
        create.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        create.setRequestBody(MINIMAL_FREESTYLE_XML);
        int code = admin.getPage(create).getWebResponse().getStatusCode();
        assertTrue(code < 400, "creating " + name + " must succeed, got HTTP " + code);

        FreeStyleProject created = j.jenkins.getItemByFullName(name, FreeStyleProject.class);
        assertNotNull(created, "the job " + name + " must have been created");
        return created;
    }

    /**
     * The D-34 state of a newly created job, stated explicitly: all three switches on. Whether
     * the plugin applies that by itself is {@link NewJobActivationLockTest}'s assertion, not this
     * class's — here the fixture establishes the state (a single property, matrix note 42) and
     * asserts it before any cause is fired, so the row measures the queue gate on a locked job
     * and can never pass by measuring an unprotected one.
     */
    private FreeStyleProject newLockedJobUnderRunControl(String name) throws Exception {
        FreeStyleProject created = createJobUnderRunControl(name);
        BatchControlJobProperty locked = new BatchControlJobProperty(true);
        locked.setBlockTimer(true);
        locked.setBlockUpstream(true);
        BatchControlFixtures.setBatchControl(created, locked);

        assertTrue(isApprovalRequired(created), "fixture: " + name + " must be approval-required before a cause is fired");
        assertTrue(locked.isBlockTimer() && locked.isBlockUpstream(), "fixture: " + name + " must be locked out of timer and upstream runs");
        return created;
    }

    /**
     * The same job after somebody brought it into service: the two automation switches turned off
     * in the job's configuration while {@code approvalRequired} stays on, and (SPEC item 6a) an
     * approved activation. This is the way out
     * D-34 names ("turn the switch off in the job configuration"), expressed the way T-OS-05/06
     * express an administrator flipping a job switch.
     */
    private FreeStyleProject newJobUnlockedForAutomation(String name) throws Exception {
        FreeStyleProject created = createJobUnderRunControl(name);
        BatchControlJobProperty unlocked = new BatchControlJobProperty(true);
        unlocked.setBlockTimer(false);
        unlocked.setBlockUpstream(false);
        BatchControlFixtures.setBatchControl(created, unlocked);

        assertTrue(isApprovalRequired(created), "fixture: " + name + " must still be approval-required after the unlock");
        assertFalse(unlocked.isBlockTimer(), "fixture: blockTimer must be off for the activation rows");
        assertFalse(unlocked.isBlockUpstream(), "fixture: blockUpstream must be off for the activation rows");
        // SPEC item 6a (#15): clearing the switches alone no longer brings the job into service;
        // an approved activation is the other half of the AND (T-06a-01/02, note 91)
        BatchControlFixtures.activate(created);
        return created;
    }

    private static boolean isApprovalRequired(Job<?, ?> target) {
        BatchControlJobProperty property = target.getProperty(BatchControlJobProperty.class);
        return property != null && property.isApprovalRequired();
    }
}
