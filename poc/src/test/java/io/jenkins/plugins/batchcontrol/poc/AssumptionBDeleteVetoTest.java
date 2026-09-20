package io.jenkins.plugins.batchcontrol.poc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.cli.CLICommandInvoker;
import hudson.model.Failure;
import hudson.model.FreeStyleProject;
import java.net.URL;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Design assumption B: throwing {@link Failure} from
 * {@link hudson.model.listeners.ItemListener#onCheckDelete} rejects deletion on the
 * UI, REST, and CLI paths.
 */
@WithJenkins
class AssumptionBDeleteVetoTest {

    @AfterEach
    void tearDown() {
        PocDeleteVetoListener.reset();
    }

    /** UI path: the job page Delete action posts to doDelete; issue that same POST. */
    @Test
    void uiDeleteIsRejected(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("victim");
        PocDeleteVetoListener.vetoEnabled = true;
        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
            wc.getOptions().setThrowExceptionOnScriptError(false);
            WebRequest req = new WebRequest(new URL(j.getURL(), "job/victim/doDelete"), HttpMethod.POST);
            wc.addCrumb(req);
            Page resp = wc.getPage(req);
            int status = resp.getWebResponse().getStatusCode();
            String body = resp.getWebResponse().getContentAsString();
            System.out.println("[PoC-B] POST doDelete status=" + status);
            assertTrue(status >= 400, "deletion must be rejected with an error status, got " + status);
            assertTrue(body.contains(PocDeleteVetoListener.message),
                    "the Failure message must be shown to the user");
        }
        assertNotNull(j.jenkins.getItem("victim"), "job must still exist");
    }

    /** REST path: HTTP DELETE on the job URL. */
    @Test
    void restHttpDeleteIsRejected(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("victim");
        PocDeleteVetoListener.vetoEnabled = true;
        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
            WebRequest req = new WebRequest(new URL(j.getURL(), "job/victim/"), HttpMethod.DELETE);
            Page resp = wc.getPage(req);
            int status = resp.getWebResponse().getStatusCode();
            System.out.println("[PoC-B] HTTP DELETE status=" + status);
            assertTrue(status >= 400, "deletion must be rejected with an error status, got " + status);
        }
        assertNotNull(j.jenkins.getItem("victim"), "job must still exist");
    }

    /** CLI path: delete-job command. */
    @Test
    void cliDeleteJobIsRejected(JenkinsRule j) throws Exception {
        j.createFreeStyleProject("victim");
        PocDeleteVetoListener.vetoEnabled = true;
        CLICommandInvoker.Result result = new CLICommandInvoker(j, "delete-job").invokeWithArgs("victim");
        System.out.println("[PoC-B] CLI delete-job exit=" + result.returnCode());
        System.out.println("[PoC-B] CLI delete-job stderr=" + result.stderr());
        assertNotEquals(0, result.returnCode(), "CLI deletion must fail");
        assertNotNull(j.jenkins.getItem("victim"), "job must still exist");
    }

    /** Programmatic sanity: Item.delete() itself propagates the Failure. */
    @Test
    void programmaticDeleteIsRejected(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("victim");
        PocDeleteVetoListener.vetoEnabled = true;
        assertThrows(Failure.class, p::delete);
        assertNotNull(j.jenkins.getItem("victim"));
    }
}
