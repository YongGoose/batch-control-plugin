package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.CLICommandInvoker;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * HTTP surface of run control. Matrix rows T-SEC-01, T-SEC-02, T-SEC-05, T-SEC-06,
 * T-04-04, T-05-03, T-06-13, T-06-14, T-06-15.
 *
 * Endpoint contract (fixed by the orchestrator): root action "batch-control" with
 * POST-only requests/&lt;id&gt;/approve|reject|cancel|changeApprover, GET list/detail.
 *
 * Written from docs/SPEC.md, docs/TEST-MATRIX.md and docs/POC-RESULTS.md only (no src/main knowledge).
 */
public class RunRequestWebTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private FreeStyleProject job;      // parametrized-request target for record rows
    private FreeStyleProject plainJob; // no parameters: target of the /build guidance rows

    @Before
    public void setUp() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ).everywhere().to("u2"));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        job = j.createFreeStyleProject("batch-x");
        job.addProperty(new BatchControlJobProperty(true));
        plainJob = j.createFreeStyleProject("plain-x");
        plainJob.addProperty(new BatchControlJobProperty(true));
    }

    /** T-SEC-01: GET on the approve endpoint is rejected (POST only). */
    @Test
    public void t_sec_01_getApproveIsRejected() throws Exception {
        String id = createPending();
        JenkinsRule.WebClient wc = webClient().login("a1");

        Page page = wc.getPage(new WebRequest(
                new URL(j.getURL(), "batch-control/requests/" + id + "/approve"), HttpMethod.GET));
        int code = page.getWebResponse().getStatusCode();
        assertTrue("GET must never approve; expected 405/rejected but got " + code, code >= 400);

        assertEquals("the request must stay PENDING after the rejected GET",
                RequestStatus.PENDING, RunRequestService.get().load(id).getStatus());
        j.waitUntilNoActivity();
        assertTrue("no build may have been scheduled", job.getBuilds().isEmpty());
    }

    /** T-SEC-02: POST approve without the Approve permission is 403. */
    @Test
    public void t_sec_02_approveWithoutPermissionIs403() throws Exception {
        String id = createPending();
        JenkinsRule.WebClient wc = webClient().login("u2");

        Page page = wc.getPage(new WebRequest(
                wc.createCrumbedUrl("batch-control/requests/" + id + "/approve"), HttpMethod.POST));
        assertEquals("a user without BatchControl/Approve must get 403",
                403, page.getWebResponse().getStatusCode());

        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus());
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty());
    }

    /** T-SEC-05: a state-changing POST without a CSRF crumb is 403 even for the right user. */
    @Test
    public void t_sec_05_postWithoutCrumbIs403() throws Exception {
        String id = createPending();
        JenkinsRule.WebClient wc = webClient().login("a1");

        Page page = wc.getPage(new WebRequest(
                new URL(j.getURL(), "batch-control/requests/" + id + "/approve"), HttpMethod.POST));
        assertEquals("a POST without the crumb must be rejected with 403",
                403, page.getWebResponse().getStatusCode());

        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus());
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty());
    }

    /** T-SEC-06: GET on the other state-changing endpoints (reject, cancel) is rejected. */
    @Test
    public void t_sec_06_getOnRejectAndCancelIsRejected() throws Exception {
        String id = createPending();

        JenkinsRule.WebClient approverClient = webClient().login("a1");
        Page rejectPage = approverClient.getPage(new WebRequest(
                new URL(j.getURL(), "batch-control/requests/" + id + "/reject"), HttpMethod.GET));
        assertTrue("GET must never reject", rejectPage.getWebResponse().getStatusCode() >= 400);

        JenkinsRule.WebClient requesterClient = webClient().login("u1");
        Page cancelPage = requesterClient.getPage(new WebRequest(
                new URL(j.getURL(), "batch-control/requests/" + id + "/cancel"), HttpMethod.GET));
        assertTrue("GET must never cancel", cancelPage.getWebResponse().getStatusCode() >= 400);

        assertEquals("the request must still be PENDING after both rejected GETs",
                RequestStatus.PENDING, RunRequestService.get().load(id).getStatus());
    }

    /** T-04-04: records are append-only; no modify/delete HTTP endpoint exists (404/405). */
    @Test
    public void t_04_04_noModifyOrDeleteEndpointForRecords() throws Exception {
        String id = createPending();
        JenkinsRule.WebClient wc = webClient().login("admin");

        for (String candidate : new String[] {"delete", "doDelete", "update", "remove"}) {
            Page page = wc.getPage(new WebRequest(
                    wc.createCrumbedUrl("batch-control/requests/" + id + "/" + candidate),
                    HttpMethod.POST));
            int code = page.getWebResponse().getStatusCode();
            assertTrue("no mutation endpoint '" + candidate + "' may exist (expected 404/405, got "
                    + code + ")", code == 404 || code == 405);
        }

        Page deleteVerb = wc.getPage(new WebRequest(
                new URL(j.getURL(), "batch-control/requests/" + id + "/"), HttpMethod.DELETE));
        assertTrue("the HTTP DELETE verb must be rejected on a record",
                deleteVerb.getWebResponse().getStatusCode() >= 400);

        RunRequest survivor = RunRequestService.get().load(id);
        assertNotNull("the record must survive every mutation attempt", survivor);
        assertEquals(RequestStatus.PENDING, survivor.getStatus());
    }

    /** T-05-03: no endpoint can change the stored parameters of an APPROVED request. */
    @Test
    public void t_05_03_noParameterChangePathAfterApproval() throws Exception {
        String id = createPending();
        j.jenkins.doQuietDown(); // hold the state at APPROVED
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(id, "ok");
        }
        assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(id).getStatus());
        Map<String, String> before = new LinkedHashMap<>(RunRequestService.get().load(id).getParameters());

        JenkinsRule.WebClient wc = webClient().login("admin");
        for (String candidate : new String[] {"updateParameters", "setParameters", "parameters", "configSubmit"}) {
            Page page = wc.getPage(new WebRequest(
                    wc.createCrumbedUrl("batch-control/requests/" + id + "/" + candidate),
                    HttpMethod.POST));
            int code = page.getWebResponse().getStatusCode();
            assertTrue("no parameter-change endpoint '" + candidate + "' may exist (expected 404/405, got "
                    + code + ")", code == 404 || code == 405);
        }

        assertEquals("the stored parameters must be exactly as requested",
                before, RunRequestService.get().load(id).getParameters());
        j.jenkins.doCancelQuietDown();
        j.waitUntilNoActivity();
    }

    /** T-06-13: a blocked user-originated POST /build answers 400 with guidance and a request link. */
    @Test
    public void t_06_13_blockedBuildPostShowsGuidanceAndLink() throws Exception {
        JenkinsRule.WebClient wc = webClient().login("u1");
        Page page = wc.getPage(new WebRequest(
                wc.createCrumbedUrl(plainJob.getUrl() + "build"), HttpMethod.POST));

        assertEquals("a blocked manual run must answer HTTP 400 (silent failure is forbidden)",
                400, page.getWebResponse().getStatusCode());
        String body = page.getWebResponse().getContentAsString();
        assertTrue("the response must explain that approval is required",
                body.toLowerCase(Locale.ROOT).contains("approval"));
        assertTrue("the response must link to the request screen",
                body.contains("batch-control"));

        j.waitUntilNoActivity();
        assertTrue(plainJob.getBuilds().isEmpty());
        assertEquals(1, plainJob.getNextBuildNumber());
    }

    /** T-06-14: the blocked CLI build exits non-zero and prints the guidance (not silent). */
    @Test
    public void t_06_14_blockedCliBuildExitsNonZeroWithMessage() throws Exception {
        CLICommandInvoker.Result result = new CLICommandInvoker(j, "build")
                .asUser("u1")
                .invokeWithArgs("plain-x");

        assertNotEquals("the blocked CLI build must not exit 0", 0, result.returnCode());
        assertTrue("stderr must carry the approval guidance",
                result.stderr().toLowerCase(Locale.ROOT).contains("approval"));

        j.waitUntilNoActivity();
        assertTrue(plainJob.getBuilds().isEmpty());
    }

    /** T-06-15: the sidebar replaces "Build Now" with "Request Run" (Freestyle and Pipeline). */
    @Test
    public void t_06_15_sidebarShowsRequestRunInsteadOfBuildNow() throws Exception {
        WorkflowJob pipeline = j.createProject(WorkflowJob.class, "pipe-x");
        pipeline.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        pipeline.addProperty(new BatchControlJobProperty(true));

        JenkinsRule.WebClient wc = webClient().login("u1");
        for (hudson.model.Job<?, ?> target : new hudson.model.Job<?, ?>[] {plainJob, pipeline}) {
            HtmlPage page = wc.getPage(target);
            String visibleText = page.asNormalizedText();
            String rawHtml = page.getWebResponse().getContentAsString();

            assertFalse(target.getName() + ": the Build Now caption must not be visible",
                    visibleText.contains("Build Now"));
            assertTrue(target.getName() + ": a Request Run entry must be rendered",
                    rawHtml.contains("Request Run"));
            // DOM-presence basis (same convention as the T-02-02 note): the sidebar link
            // must point into the plugin's request screen
            assertTrue(target.getName() + ": the request link must point at batch-control",
                    rawHtml.contains("batch-control"));
        }
    }

    // ---------------------------------------------------------------- helpers

    private JenkinsRule.WebClient webClient() {
        return j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
    }

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private String createPending() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("DATE", "2026-09-01");
        try (ACLContext ignored = as("u1")) {
            return RunRequestService.get().create(job, parameters, "routine batch run", "a1").getId();
        }
    }
}
