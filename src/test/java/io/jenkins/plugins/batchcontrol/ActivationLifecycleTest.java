package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.Cause;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Items;
import hudson.model.Queue;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.io.File;
import java.util.Arrays;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import jenkins.branch.BranchSource;
import jenkins.scm.impl.SingleSCMSource;
import hudson.scm.NullSCM;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.activate;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6a (#15, D-39): an activation is per job and survives configuration edits; renaming
 * or moving a job keeps it; deleting a job removes it; computed children (D-32) need none.
 * Matrix rows T-06a-27..31.
 *
 * <p>The store layout (ARCHITECTURE "Activation store") is checked from outside: activation
 * state lives in {@code batch-control/activations/*.xml}, one file per job, next to the
 * {@code .schema} marker. The file name encoding is not pinned; the count is (note 94).
 *
 * <p>Written from docs/SPEC.md item 6a and docs/ARCHITECTURE.md "Activation store" only (no
 * src/main knowledge).
 */
@WithJenkins
public class ActivationLifecycleTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-06a-27 (P0): an activation survives configuration edits. The activated job's config.xml
     * is edited (description) and blockTimer is turned on and off again; it stays activated and
     * its timer runs — no re-approval per edit (D-39 rejected alternative ①).
     */
    @Test
    public void t_06a_27_activationSurvivesConfigurationEdits() throws Exception {
        FreeStyleProject job = activatedJob("life-edit");

        JenkinsRule.WebClient admin = j.createWebClient().login("admin");
        WebRequest edit = new WebRequest(admin.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        edit.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        String xml = job.getConfigFile().asString();
        String edited = xml.matches("(?s).*<description(/>|>).*")
                ? xml.replaceFirst("(?s)<description/>|<description>.*?</description>", "<description>edited</description>")
                : xml.replaceFirst("<project>", "<project><description>edited</description>");
        edit.setRequestBody(edited);
        admin.getPage(edit);
        job = j.jenkins.getItemByFullName("life-edit", FreeStyleProject.class);
        assertEquals("edited", job.getDescription(), "premise: the config.xml edit took effect");

        BatchControlJobProperty property = job.getProperty(BatchControlJobProperty.class);
        property.setBlockTimer(true);
        job.save();
        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "premise: blockTimer on blocks");
        property.setBlockTimer(false);
        job.save();

        assertTrue(isActivated(job), "configuration edits must not undo an activation");
        j.assertBuildStatusSuccess(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
    }

    /**
     * T-06a-28 (P0): renaming keeps the activation, and it moves rather than copies: a new job
     * created under the old name is not activated, and there is still one state file.
     */
    @Test
    public void t_06a_28_renameKeepsActivation() throws Exception {
        FreeStyleProject job = activatedJob("life-old");
        assertEquals(1, stateFiles(), "one activation state file after one activation");

        job.renameTo("life-new");
        FreeStyleProject renamed = j.jenkins.getItemByFullName("life-new", FreeStyleProject.class);
        assertNotNull(renamed);
        assertTrue(isActivated(renamed), "a renamed job keeps its activation");
        j.assertBuildStatusSuccess(renamed.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        assertEquals(1, stateFiles(), "the state file is relocated, not duplicated");

        FreeStyleProject squatter = clearedJob(j.createFreeStyleProject("life-old"));
        assertFalse(isActivated(squatter), "a new job under the old name must not inherit the activation");
        assertNull(squatter.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        assertBlocked(j, squatter, 1, 0);
    }

    /** T-06a-29 (P0): moving a job into a folder keeps its activation; the old path does not. */
    @Test
    public void t_06a_29_moveKeepsActivation() throws Exception {
        FreeStyleProject job = activatedJob("life-move");
        Folder folder = j.jenkins.createProject(Folder.class, "life-folder");

        Items.move(job, folder);
        FreeStyleProject moved = j.jenkins.getItemByFullName("life-folder/life-move", FreeStyleProject.class);
        assertNotNull(moved, "premise: the move took effect");
        assertTrue(isActivated(moved), "a moved job keeps its activation");
        j.assertBuildStatusSuccess(moved.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        assertEquals(1, stateFiles(), "the state file is relocated, not duplicated");

        FreeStyleProject squatter = clearedJob(j.createFreeStyleProject("life-move"));
        assertFalse(isActivated(squatter), "a new job at the old path must not inherit the activation");
    }

    /**
     * T-06a-30 (P0): deleting a job removes its activation. A job re-created under the same name
     * starts not activated and its timer is refused; the state file is gone.
     */
    @Test
    public void t_06a_30_deleteRemovesActivation() throws Exception {
        FreeStyleProject job = activatedJob("life-del");
        assertEquals(1, stateFiles());

        job.delete();
        assertEquals(0, stateFiles(), "deleting the job must remove its activation state file");

        FreeStyleProject again = clearedJob(j.createFreeStyleProject("life-del"));
        assertFalse(isActivated(again), "a re-created job must not inherit the deleted job's activation");
        assertNull(again.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        assertBlocked(j, again, 1, 0);
    }

    /**
     * T-06a-31 (P0): computed children (D-32) are not controlled and need no activation. A
     * multibranch branch job created while run control is on is not activated, yet a timer and
     * an upstream cause both start it.
     */
    @Test
    public void t_06a_31_multibranchChildNeedsNoActivation() throws Exception {
        WorkflowMultiBranchProject mb = j.jenkins.createProject(WorkflowMultiBranchProject.class, "life-mb");
        mb.getSourcesList().add(new BranchSource(new SingleSCMSource("main", new NullSCM())));
        Queue.Item indexing = mb.scheduleBuild2(0);
        assertNotNull(indexing, "branch indexing must be schedulable");
        indexing.getFuture().get();
        j.waitUntilNoActivity();
        WorkflowJob branch = mb.getItem("main");
        assertNotNull(branch, "indexing must have created the branch child job");
        assertFalse(isActivated(branch), "premise: nobody activated the branch job");

        int next = branch.getNextBuildNumber();
        assertNotNull(branch.scheduleBuild2(0, new hudson.model.CauseAction(new TimerTrigger.TimerTriggerCause())),
                "a computed child's timer cause must pass without activation");
        j.waitUntilNoActivity();
        assertNotNull(branch.getBuildByNumber(next), "the timer run must exist");

        WorkflowJob caller = uncontrolled(j.createProject(WorkflowJob.class, "life-caller"));
        caller.setDefinition(new CpsFlowDefinition("build job: 'life-mb/main', wait: false, propagate: false", true));
        j.buildAndAssertSuccess(caller);
        j.waitUntilNoActivity();
        WorkflowRun upstreamRun = branch.getBuildByNumber(next + 1);
        assertNotNull(upstreamRun, "an upstream cause must start the computed child without activation");
        assertNotNull(upstreamRun.getCause(Cause.UpstreamCause.class));
    }

    // ---------------------------------------------------------------- helpers

    private FreeStyleProject clearedJob(FreeStyleProject job) throws Exception {
        BatchControlJobProperty cleared = new BatchControlJobProperty(true);
        cleared.setBlockTimer(false);
        cleared.setBlockUpstream(false);
        setBatchControl(job, cleared);
        return job;
    }

    private FreeStyleProject activatedJob(String name) throws Exception {
        FreeStyleProject job = clearedJob(j.createFreeStyleProject(name));
        activate(job);
        return job;
    }

    /** Number of activation state files (the {@code .schema} marker excluded). */
    private int stateFiles() {
        File dir = new File(j.jenkins.getRootDir(), "batch-control/activations");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".xml") && !n.startsWith("."));
        return files == null ? 0 : files.length;
    }
}
