package io.jenkins.plugins.batchcontrol;

import hudson.model.ChoiceParameterDefinition;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.runRequestIds;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression rows for security-08 S-10 (SPEC item 5: the approved build runs with the parameters
 * stored at request time, and there is no path that changes them after approval). A scripted
 * submission of {@code job/<name>/batch-control/submit} without the browser's {@code json} field
 * must still store the parameters: the raw parameter fields when given (named like
 * {@code buildWithParameters}), otherwise the job's defaults as explicit values, so a later change
 * of a default does not change what the approved build runs with. Matrix rows T-SEC-44, T-SEC-45.
 *
 * <p>Written from docs/SPEC.md, docs/reports/security-08.md and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class ScriptedSubmissionParametersTest {

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        job = j.createFreeStyleProject("param-x");
        job.addProperty(parameters("2000-01-01"));
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * T-SEC-44 (S-10): a scripted submission with raw parameter fields ({@code DATE}, {@code MODE})
     * and no {@code json} stores those values, and the approved build runs with exactly them.
     */
    @Test
    public void t_sec_44_rawParameterFieldsAreStored() throws Exception {
        String id = scriptedSubmit("DATE", "2026-09-01", "MODE", "partial");

        Map<String, String> stored = RunRequestService.get().load(id).getParameters();
        assertEquals("2026-09-01", stored.get("DATE"), "the raw DATE field must be stored; stored " + stored);
        assertEquals("partial", stored.get("MODE"), "the raw MODE field must be stored; stored " + stored);

        FreeStyleBuild build = approveAndRun(id);
        assertEquals("2026-09-01", value(build, "DATE"));
        assertEquals("partial", value(build, "MODE"));
    }

    /**
     * T-SEC-45 (S-10): a scripted submission with no parameter fields stores the job's defaults as
     * explicit values; after the job's default changes, the approved build still runs with the
     * stored value, not with the default at build time.
     */
    @Test
    public void t_sec_45_noParameterFieldsStoreExplicitDefaults() throws Exception {
        String id = scriptedSubmit();

        Map<String, String> stored = RunRequestService.get().load(id).getParameters();
        assertEquals("2000-01-01", stored.get("DATE"), "the DATE default must be stored explicitly; stored " + stored);
        assertEquals("full", stored.get("MODE"), "the MODE default (first choice) must be stored explicitly; stored " + stored);

        job.removeProperty(ParametersDefinitionProperty.class);
        job.addProperty(parameters("1999-12-31"));
        assertNotNull(job.getProperty(BatchControlJobProperty.class), "fixture: the job still requires approval");

        FreeStyleBuild build = approveAndRun(id);
        assertEquals("2000-01-01", value(build, "DATE"),
                "the approved build must run with the value stored at request time, not the default at build time");
        assertEquals("full", value(build, "MODE"));
    }

    // ---------------------------------------------------------------- helpers

    private static ParametersDefinitionProperty parameters(String dateDefault) {
        return new ParametersDefinitionProperty(
                new StringParameterDefinition("DATE", dateDefault),
                new ChoiceParameterDefinition("MODE", new String[] {"full", "partial"}, "how much to run"));
    }

    /** POSTs reason, approvers and the given raw fields, without the browser's {@code json} blob. */
    private String scriptedSubmit(String... nameValues) throws Exception {
        Set<String> before = runRequestIds();
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("reason", "month-end batch run"));
        params.add(new NameValuePair("approvers", "a1"));
        for (int i = 0; i < nameValues.length; i += 2) {
            params.add(new NameValuePair(nameValues[i], nameValues[i + 1]));
        }
        WebResponse response = ApproverFormFixtures.post(j, "u1", job.getUrl() + "batch-control/submit", params);
        assertSuccess(response, "the scripted submission");
        Set<String> after = runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "exactly one request must be created");
        return after.iterator().next();
    }

    private FreeStyleBuild approveAndRun(String id) throws Exception {
        assertSuccess(decideRun(j, "a1", id, "approve", "ok"), "the approval");
        j.waitUntilNoActivity();
        RunRequest reloaded = RunRequestService.get().load(id);
        assertEquals(RequestStatus.EXECUTED, reloaded.getStatus(), "fixture: the approved request runs");
        assertEquals(1, job.getBuilds().size(), "fixture: exactly one build");
        return job.getBuildByNumber(1);
    }

    private static String value(FreeStyleBuild build, String name) {
        ParametersAction action = build.getAction(ParametersAction.class);
        assertNotNull(action, "the build must carry its parameters");
        assertNotNull(action.getParameter(name), "the build must carry parameter " + name);
        Object v = action.getParameter(name).getValue();
        return v instanceof String ? (String) v : String.valueOf(v);
    }
}
