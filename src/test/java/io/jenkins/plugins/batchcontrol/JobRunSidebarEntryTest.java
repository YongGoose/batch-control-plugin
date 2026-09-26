package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matrix rows T-UI-16 .. T-UI-18 — finding U-02: a controlled job showed <em>two</em> sidebar
 * entries both reading "Request Run", and the one a user was most likely to click was core's own
 * build link, whose href schedules a run with no approved marker and is refused (e2e-01 UX-1).
 *
 * <p>Core's build entry cannot be hidden or re-pointed from a plugin, so the fix renamed it. These
 * rows therefore assert the pairing rather than a single caption: exactly one entry reads
 * "Request Run" and it is the one that opens the request form, while the relabelled core entry
 * says what it is and still points at the build URL.
 *
 * <p>Relationship to the existing SPEC row: T-06-15 pins the acceptance criterion of SPEC item 6
 * ("Build Now" is replaced by "Request Run") at page level and keeps passing unchanged. These rows
 * are the screen contract that tells the two entries apart (matrix note 47).
 */
@WithJenkins
public class JobRunSidebarEntryTest {

    private static final String REQUEST_RUN = "Request Run";
    private static final String DIRECT_BUILD = "Direct Build (needs approval)";
    private static final String BUILD_NOW = "Build Now";

    private JenkinsRule j;

    private BatchControlGlobalConfiguration cfg;

    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                // Item/Build matters: core only renders its build entry for a user who has it,
                // so without it this class would measure an absent entry, not a renamed one.
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to("a1"));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        job = j.createFreeStyleProject("plain-x");
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * T-UI-16: on a controlled Freestyle job exactly one entry reads "Request Run" and it opens
     * the request form; a separate, differently named entry is core's build link; and no entry
     * reads "Build Now".
     */
    @Test
    public void t_ui_16_exactlyOneRequestRunEntryAndARenamedDirectBuildEntry() throws Exception {
        assertSidebarPairing(job);
    }

    /**
     * T-UI-17: with run control off the plugin touches neither entry — core's "Build Now" is back
     * and neither plugin caption appears. This is the falsifiability twin of T-UI-16: it proves the
     * captions in that row come from the plugin and that the plugin leaves an uncontrolled
     * installation alone (SPEC item 1).
     */
    @Test
    public void t_ui_17_runControlOffLeavesCoresBuildNowEntryAlone() throws Exception {
        cfg.setRunControlEnabled(false);
        cfg.save();
        assertFalse(BatchControlGlobalConfiguration.get().isRunControlEnabled(), "fixture: run control must really be off");
        assertTrue(job.getProperty(BatchControlJobProperty.class).isApprovalRequired(), "fixture: the job still asks for approval - only the global switch moved, so"
                + " this row measures the switch");

        HtmlPage page = jobPage("u1", job);
        String text = page.asNormalizedText();

        assertTrue(text.contains(BUILD_NOW), "with run control off core's own Build Now caption must be back; the page read: "
                + excerpt(text));
        assertFalse(text.contains(DIRECT_BUILD), "the plugin's relabelled caption must not appear while run control is off");
        assertTrue(captionedEntries(page, REQUEST_RUN).isEmpty(), "no Request Run entry may appear while run control is off");
    }

    /**
     * T-UI-18: a parameterised Pipeline job behaves the same. This is the build-with-parameters
     * label path — core asks for the same message key with a different default caption — and the
     * other {@code ParameterizedJob} implementation, which uses a different constant from
     * Freestyle.
     */
    @Test
    public void t_ui_18_parameterisedPipelineJobBehavesTheSame() throws Exception {
        WorkflowJob pipeline = j.createProject(WorkflowJob.class, "pipe-x");
        pipeline.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        pipeline.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("DATE", "2026-09-27", "batch date")));
        BatchControlFixtures.setBatchControl(pipeline, new BatchControlJobProperty(true));
        assertTrue(pipeline.isParameterized(), "fixture: the pipeline must be parameterised, or this row does not exercise the"
                + " build-with-parameters caption at all");

        assertSidebarPairing(pipeline);

        // Core's parameterised caption must not survive either: it is the same message key.
        assertFalse(jobPage("u1", pipeline).asNormalizedText().contains("Build with Parameters"), "core's parameterised build caption must be replaced as well");
    }

    // ---------------------------------------------------------------- shared assertion

    /**
     * The U-02 contract on one job page: one "Request Run" entry into the plugin's form, one
     * differently named entry at core's build URL, and no "Build Now".
     */
    private void assertSidebarPairing(Job<?, ?> target) throws Exception {
        HtmlPage page = jobPage("u1", target);
        String text = page.asNormalizedText();

        List<HtmlAnchor> requestRun = captionedEntries(page, REQUEST_RUN);
        assertEquals(1, requestRun.size(), target.getFullName() + ": exactly one sidebar entry may read \"" + REQUEST_RUN
                + "\", but " + requestRun.size() + " did: " + hrefs(requestRun));
        assertTrue(requestRun.get(0).getHrefAttribute().endsWith(target.getUrl() + "batch-control"), target.getFullName() + ": the Request Run entry must open the plugin's request"
                + " form, but pointed at " + requestRun.get(0).getHrefAttribute());

        List<HtmlAnchor> directBuild = captionedEntries(page, DIRECT_BUILD);
        assertEquals(1, directBuild.size(), target.getFullName() + ": core's build entry must be present exactly once under its"
                + " own caption, but " + directBuild.size() + " entries matched: " + hrefs(directBuild));
        assertTrue(directBuild.get(0).getHrefAttribute().contains("build"), target.getFullName() + ": the relabelled entry must still be core's build link,"
                + " but pointed at " + directBuild.get(0).getHrefAttribute());
        assertNotNull(directBuild.get(0).getHrefAttribute(), "the relabelled entry must carry an href");

        assertFalse(text.contains(BUILD_NOW), target.getFullName() + ": the Build Now caption must not be visible (SPEC item 6);"
                + " the page read: " + excerpt(text));

        // The two entries must be different links: the whole finding was two entries with the same
        // caption going to different places, and a fix that merged them would lose the request form.
        assertFalse(requestRun.get(0).getHrefAttribute().equals(directBuild.get(0).getHrefAttribute()), target.getFullName() + ": the two entries must be distinct links");
    }

    // ---------------------------------------------------------------- helpers

    private HtmlPage jobPage(String userId, Job<?, ?> target) throws Exception {
        HtmlPage page = j.createWebClient().withThrowExceptionOnFailingStatusCode(false)
                .login(userId).getPage(target);
        assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: " + userId + " must be able to open " + target.getFullName());
        return page;
    }

    private static List<HtmlAnchor> captionedEntries(HtmlPage page, String caption) {
        return page.getAnchors().stream()
                .filter(a -> a.asNormalizedText().trim().contains(caption))
                .collect(Collectors.toList());
    }

    private static List<String> hrefs(List<HtmlAnchor> anchors) {
        return anchors.stream().map(HtmlAnchor::getHrefAttribute).collect(Collectors.toList());
    }

    private static String excerpt(String text) {
        return text.length() <= 600 ? text : text.substring(0, 600) + "...";
    }
}
