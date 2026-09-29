package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.transform.stream.StreamSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.get;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.secureWithRunControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6 (#36) — build-token-root. {@code /buildByToken/build?job=&token=} and
 * {@code /buildByToken/buildWithParameters} schedule a build anonymously on the strength of the
 * job's authentication token. They are manual runs and must not bypass the gate.
 * Rows T-06-31 .. T-06-32.
 */
@WithJenkins
public class PluginInteractionBuildTokenRootTest {

    private static final String TOKEN = "batch-token-31";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        secureWithRunControl(j);
    }

    /**
     * T-06-31: an anonymous {@code /buildByToken/build} and {@code /buildByToken/buildWithParameters}
     * call with the right token does not queue a run of an approval-required job.
     */
    @Test
    public void t_06_31_buildByTokenIsBlocked() throws Exception {
        FreeStyleProject job = withToken(j.createFreeStyleProject("btr-x"));
        setBatchControl(job, new BatchControlJobProperty(true));
        FreeStyleProject parameterised = j.createFreeStyleProject("btr-p");
        parameterised.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("BATCH_DATE", "2000-01-01")));
        parameterised = withToken(parameterised);
        setBatchControl(parameterised, new BatchControlJobProperty(true));

        get(j, null, "buildByToken/build?job=btr-x&token=" + TOKEN);
        assertBlocked(j, job, 1, 0);

        get(j, null, "buildByToken/buildWithParameters?job=btr-p&token=" + TOKEN + "&BATCH_DATE=2026-09-28");
        assertBlocked(j, parameterised, 1, 0);
    }

    /**
     * T-06-32 (false-positive guard of T-06-31): the same call on an uncontrolled job with the same
     * token does build, so T-06-31 measures the gate and not a token that never took effect.
     */
    @Test
    public void t_06_32_buildByTokenRunsOnUncontrolledJob() throws Exception {
        FreeStyleProject free = uncontrolled(withToken(j.createFreeStyleProject("btr-free")));
        // SPEC 6a / D-46: a build-token-root submission is an unattended RemoteCause and needs
        // the job activated regardless of approvalRequired, even on an otherwise uncontrolled
        // job (matrix note 109). The row's point is unchanged: the token path works on a job
        // the gate lets through.
        BatchControlFixtures.activate(free);

        get(j, null, "buildByToken/build?job=btr-free&token=" + TOKEN);
        j.waitUntilNoActivity();

        assertEquals(1, free.getBuilds().size(), "fixture: build-token-root must build an uncontrolled job with the right token");
        assertNotNull(free.getBuildByNumber(1));
    }

    /**
     * T-06-72 (e2e-run3 DEF-33, PR-04): a script caller refused by the gate on
     * {@code /buildByToken/build} is told why. The answer is not 2xx and carries a non-empty
     * plain-text body saying the job needs an approved run request (SPEC 6 usability line: every
     * refusal tells the user in plain words why and what to do instead). Nothing is queued
     * (note 144).
     */
    @Test
    public void t_06_72_buildByTokenRefusalExplainsItself() throws Exception {
        FreeStyleProject job = withToken(j.createFreeStyleProject("btr-msg"));
        setBatchControl(job, new BatchControlJobProperty(true));

        org.htmlunit.Page answer = get(j, null, "buildByToken/build?job=btr-msg&token=" + TOKEN);
        int code = answer.getWebResponse().getStatusCode();
        String body = answer.getWebResponse().getContentAsString();
        String type = String.valueOf(answer.getWebResponse().getContentType());
        assertTrue(code < 200 || code >= 300, "the refused token call must not answer success, got " + code);
        assertTrue(body != null && !body.trim().isEmpty(), "the refusal must carry a body, got HTTP " + code + " with an"
                + " empty body");
        assertTrue(type.startsWith("text/plain"), "a script caller's refusal must be plain text, got " + type + ": "
                + body);
        assertMentionsApprovedRunRequest("buildByToken/build", body);
        assertBlocked(j, job, 1, 0);
    }

    /**
     * T-06-73 (e2e-run3 DEF-34, B5-06): core's own remote trigger {@code GET /job/X/build?token=}
     * called by an authenticated requester on an approval-required job must not answer the 302 core
     * gives when it has scheduled a build: a script would treat the refused run as started. The
     * answer is neither 2xx nor 3xx and says the job needs an approved run request; no build is
     * queued (note 145).
     */
    @Test
    public void t_06_73_coreTokenTriggerRefusalIsNotARedirect() throws Exception {
        FreeStyleProject job = withToken(j.createFreeStyleProject("core-token"));
        setBatchControl(job, new BatchControlJobProperty(true));

        org.jvnet.hudson.test.JenkinsRule.WebClient wc = j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false).withRedirectEnabled(false).login("u1");
        org.htmlunit.Page answer = wc.getPage(new org.htmlunit.WebRequest(
                new java.net.URL(j.getURL(), job.getUrl() + "build?token=" + TOKEN), org.htmlunit.HttpMethod.GET));
        int code = answer.getWebResponse().getStatusCode();
        String text = UsabilityFixtures.text(answer);
        assertTrue(code >= 400, "the refused token trigger must answer neither 2xx nor 3xx (a redirect reads as a"
                + " started run), got " + code + " Location=" + answer.getWebResponse().getResponseHeaderValue("Location"));
        assertTrue(text != null && !text.trim().isEmpty(), "the refusal must carry a message");
        assertMentionsApprovedRunRequest("job/core-token/build?token=", text);
        assertBlocked(j, job, 1, 0);
    }

    private static void assertMentionsApprovedRunRequest(String what, String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        assertTrue(lower.contains("approv") && lower.contains("request"), what + ": the refusal must say that the job"
                + " needs an approved run request: " + (text.length() > 800 ? text.substring(0, 800) : text));
    }

    /** Installs the job's authentication token (core's {@code authToken} config element). */
    private FreeStyleProject withToken(FreeStyleProject job) throws Exception {
        String xml = job.getConfigFile().asString();
        assertTrue(xml.contains("</project>"), "fixture: unexpected config.xml shape");
        String withToken = xml.replace("</project>", "  <authToken>" + TOKEN + "</authToken>\n</project>");
        job.updateByXml(new StreamSource(new ByteArrayInputStream(withToken.getBytes(StandardCharsets.UTF_8))));
        FreeStyleProject reloaded = j.jenkins.getItemByFullName(job.getFullName(), FreeStyleProject.class);
        assertNotNull(reloaded.getAuthToken(), "fixture: the job must carry an authentication token");
        assertEquals(TOKEN, reloaded.getAuthToken().getToken(), "fixture: the token must be the one installed");
        return reloaded;
    }
}
