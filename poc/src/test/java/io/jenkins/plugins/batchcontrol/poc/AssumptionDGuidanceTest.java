package io.jenkins.plugins.batchcontrol.poc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.cli.CLICommandInvoker;
import hudson.model.FreeStyleProject;
import java.net.URL;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Design assumption D: blocked users get visible guidance instead of a silent failure.
 *
 * <p>NOTE: the original plan (manual hpi:run + browser screenshot) is replaced here by
 * WebClient HTML assertions. A visual re-check is required in Phase 5 (E2E).
 */
@WithJenkins
class AssumptionDGuidanceTest {

    @AfterEach
    void tearDown() {
        PocQueueDecisionHandler.reset();
        PocBuildNowLabelProvider.reset();
        PocGuidanceActionFactory.reset();
    }

    /** Throwing Failure from shouldSchedule surfaces the message on the blocked POST response. */
    @Test
    void blockedManualPostShowsFailureMessage(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("guarded");
        PocQueueDecisionHandler.BLOCKED_JOBS.add("guarded");
        PocQueueDecisionHandler.throwFailure = true;

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
            wc.getOptions().setThrowExceptionOnScriptError(false);
            WebRequest req = new WebRequest(new URL(j.getURL(), "job/guarded/build?delay=0sec"), HttpMethod.POST);
            wc.addCrumb(req);
            Page resp = wc.getPage(req);
            int status = resp.getWebResponse().getStatusCode();
            String body = resp.getWebResponse().getContentAsString();
            System.out.println("[PoC-D] blocked POST status=" + status);
            assertTrue(body.contains(PocQueueDecisionHandler.failureMessage),
                    "the guidance message must appear in the response page");
        }
        j.waitUntilNoActivity();
        assertEquals(0, j.jenkins.getQueue().getItems().length);
        assertNull(p.getLastBuild());
    }

    /** "Build Now" is relabeled and a guidance sidebar action is added. */
    @Test
    void buildNowRelabeledAndSidebarActionAdded(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("guarded");
        PocBuildNowLabelProvider.RELABELED_JOBS.add("guarded");
        PocGuidanceActionFactory.GUIDED_JOBS.add("guarded");

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setThrowExceptionOnScriptError(false);
            HtmlPage page = wc.getPage(p);
            String text = page.asNormalizedText();
            assertTrue(text.contains(PocBuildNowLabelProvider.LABEL),
                    "sidebar must show the replacement label");
            assertFalse(text.contains("Build Now"),
                    "the default Build Now label must be gone");
            assertTrue(text.contains(PocGuidanceActionFactory.PocRequestRunAction.DISPLAY_NAME),
                    "the guidance sidebar action must be visible");
            boolean linkPresent = page.getAnchors().stream()
                    .anyMatch(a -> a.getHrefAttribute()
                            .contains(PocGuidanceActionFactory.PocRequestRunAction.URL_NAME));
            assertTrue(linkPresent, "the guidance action link must be present in the sidebar");
        }
    }

    /** CLI users also see the guidance message, not a silent no-op. */
    @Test
    void cliBlockedShowsFailureMessage(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("guarded");
        PocQueueDecisionHandler.BLOCKED_JOBS.add("guarded");
        PocQueueDecisionHandler.throwFailure = true;

        CLICommandInvoker.Result result = new CLICommandInvoker(j, "build").invokeWithArgs("guarded");
        System.out.println("[PoC-D] CLI exit=" + result.returnCode());
        System.out.println("[PoC-D] CLI stderr=" + result.stderr());
        assertNotEquals(0, result.returnCode(), "CLI must not report success");
        assertTrue(result.stderr().contains(PocQueueDecisionHandler.failureMessage),
                "the guidance message must appear on CLI stderr");
        j.waitUntilNoActivity();
        assertNull(p.getLastBuild());
    }
}
