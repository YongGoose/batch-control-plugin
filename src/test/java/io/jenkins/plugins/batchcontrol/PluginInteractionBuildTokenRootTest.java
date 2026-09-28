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

        get(j, null, "buildByToken/build?job=btr-free&token=" + TOKEN);
        j.waitUntilNoActivity();

        assertEquals(1, free.getBuilds().size(), "fixture: build-token-root must build an uncontrolled job with the right token");
        assertNotNull(free.getBuildByNumber(1));
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
