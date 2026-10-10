package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.htmlunit.Page;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.customizebuildnow.BuildNowTextProperty;
import org.jenkinsci.plugins.customizebuildnow.Labels;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6, acceptance line "the guarantees above hold when other plugins that add build
 * buttons or triggers are installed ... Where another plugin replaces or relabels the build link,
 * the job page still offers Request Run" (#34, #36) — customize-build-now.
 *
 * <p>customize-build-now relabels core's build link through a job property
 * ({@link BuildNowTextProperty}); the link still targets core's {@code build} URL. Rows
 * T-06-20 .. T-06-23.
 */
@WithJenkins
public class PluginInteractionCustomizeBuildNowTest {

    private static final String CUSTOM_CAPTION = "Deploy Batch Now";

    private JenkinsRule j;

    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        secureWithRunControl(j);
        job = j.createFreeStyleProject("cbn-x");
        relabel(job);
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * T-06-20: with customize-build-now configured on an approval-required job, the job page still
     * offers "Request Run" and that entry opens job/&lt;name&gt;/batch-control.
     */
    @Test
    public void t_06_20_relabelledJobStillOffersRequestRun() throws Exception {
        HtmlPage page = jobPage("u1", job);
        List<HtmlAnchor> requestRun = page.getAnchors().stream()
                .filter(a -> a.asNormalizedText().trim().contains("Request Run"))
                .collect(Collectors.toList());
        assertFalse(requestRun.isEmpty(), "a job relabelled by customize-build-now must still offer Request Run; the page read: "
                + excerpt(page.asNormalizedText()));
        assertTrue(requestRun.stream().anyMatch(a -> stripSlash(a.getHrefAttribute()).endsWith(job.getUrl() + "batch-control")),
                "the Request Run entry must open " + job.getUrl() + "batch-control/, but the entries pointed at "
                        + requestRun.stream().map(HtmlAnchor::getHrefAttribute).collect(Collectors.toList()));
    }

    /**
     * T-06-21 (false-positive guard of T-06-20): on an uncontrolled job the same property does
     * relabel core's link, which proves customize-build-now is active in this instance and that
     * T-06-20 measures the conflict it is about.
     */
    @Test
    public void t_06_21_customCaptionTakesEffectOnUncontrolledJob() throws Exception {
        FreeStyleProject free = j.createFreeStyleProject("cbn-free");
        relabel(free);
        uncontrolled(free);
        String text = jobPage("u1", free).asNormalizedText();
        assertTrue(text.contains(CUSTOM_CAPTION), "fixture: customize-build-now must relabel the build link of an uncontrolled job; the page read: "
                + excerpt(text));
        assertFalse(text.contains("Request Run"), "an uncontrolled job must not offer Request Run");
    }

    /**
     * T-06-22: POST to the relabelled build URL (and a click on every anchor carrying the custom
     * caption) does not queue a run without approval; the refusal is not silent.
     */
    @Test
    @Tag("core")
    public void t_06_22_relabelledBuildUrlIsBlocked() throws Exception {
        Page response = post(j, "u1", job.getUrl() + "build");
        assertEquals(400, response.getWebResponse().getStatusCode(), "a blocked manual run must answer HTTP 400 (silent failure is forbidden)");
        String body = response.getWebResponse().getContentAsString();
        assertTrue(body.toLowerCase(Locale.ROOT).contains("approval"), "the response must explain that approval is required");
        assertTrue(body.contains("batch-control"), "the response must link to the request screen");
        assertBlocked(j, job, 1, 0);

        HtmlPage page = jobPage("u1", job);
        for (HtmlAnchor anchor : page.getAnchors()) {
            if (anchor.asNormalizedText().contains(CUSTOM_CAPTION)) {
                try {
                    anchor.click();
                } catch (Exception blockedResponse) {
                    // a 4xx answer from the blocked build URL is expected
                }
            }
        }
        assertBlocked(j, job, 1, 0);
    }

    /** T-06-23: an approved request on the relabelled job is queued exactly once. */
    @Test
    @Tag("core")
    public void t_06_23_approvedRunQueuedExactlyOnce() throws Exception {
        requestAndApprove(job);
        j.assertBuildStatusSuccess(assertApprovedRunQueuedExactlyOnce(j, job));
    }

    // ---------------------------------------------------------------- helpers

    private void relabel(FreeStyleProject target) throws Exception {
        Labels labels = new Labels();
        labels.setAlternateBuildNow(CUSTOM_CAPTION);
        labels.setAlternateBuildWithParams(CUSTOM_CAPTION);
        BuildNowTextProperty property = new BuildNowTextProperty(labels);
        target.addProperty(property);
        assertSame(property, target.getProperty(BuildNowTextProperty.class), "fixture: the customize-build-now property must be the one the job reads back");
    }

    private HtmlPage jobPage(String userId, FreeStyleProject target) throws Exception {
        HtmlPage page = j.createWebClient().withThrowExceptionOnFailingStatusCode(false)
                .login(userId).getPage(target);
        assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: " + userId + " must be able to open " + target.getFullName());
        return page;
    }

    private static String stripSlash(String href) {
        return href != null && href.endsWith("/") ? href.substring(0, href.length() - 1) : String.valueOf(href);
    }

    private static String excerpt(String text) {
        return text.length() <= 600 ? text : text.substring(0, 600) + "...";
    }
}
