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
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.runRequestIds;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunMultipartOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every submission form stores a value for every parameter the job defines (issue #40). Whether the Request
 * Run submission is urlencoded or multipart, and whether its {@code json} field carries all, some or none of
 * the parameters, the stored request holds a value for each parameter of the job; a missing one gets the
 * job's default at request time, stored explicitly. The approver sees all of them, and the approved build
 * runs with exactly the stored values even after the job's defaults change. Matrix rows T-05-145 ..
 * T-05-148 (note 309).
 *
 * <p>Basis: SPEC 5 (the approved build's parameters match the stored ones exactly; no path changes them
 * after approval; the approver checks the parameters before deciding), security-08 S-10 (rows T-SEC-44/45:
 * the explicit-defaults rule for a submission without {@code json}), and the Wave A contract for #40 frozen
 * by the main session on 2026-10-10.
 *
 * <p>Fixture as in {@link ScriptedSubmissionParametersTest}: job {@code param-x} with {@code DATE} (string,
 * default 2000-01-01) and {@code MODE} (choice full, partial; default full). After the request the defaults
 * are changed to {@code DATE} 1999-12-31 and {@code MODE} partial (choices reordered), so a build that took
 * the default at build time would show it. The multipart shape is the browser's
 * ({@link ApproverFormFixtures#submitRunMultipart}).
 *
 * <p>Written from docs/SPEC.md item 5, docs/TEST-MATRIX.md (T-SEC-44/45), the issue text of #40 and the Wave A
 * contract only (no src/main knowledge).
 */
@WithJenkins
public class JsonSubmissionDefaultsTest {

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
        job.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("DATE", "2000-01-01"),
                new ChoiceParameterDefinition("MODE", new String[] {"full", "partial"}, "how much to run")));
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * T-05-145 (#40): a multipart submission whose {@code json} has no {@code parameter} (and no raw
     * parameter parts) stores DATE=2000-01-01 and MODE=full explicitly; a1's request page shows both and
     * not "No parameters."; after the defaults change the approved build runs with DATE=2000-01-01,
     * MODE=full. Guard first: a multipart submission whose {@code json} carries both parameters
     * (DATE=2026-09-01, MODE=partial) stores exactly them.
     */
    @Test
    public void t_05_145_multipartJsonWithoutParametersStoresExplicitDefaults() throws Exception {
        String full = multipart(entry("DATE", "2026-09-01"), entry("MODE", "partial"));
        assertEquals(map("DATE", "2026-09-01", "MODE", "partial"), stored(full),
                "guard: a json blob with every parameter stores exactly the submitted values");

        String none = multipart();
        assertEquals(map("DATE", "2000-01-01", "MODE", "full"), stored(none),
                "#40: a json blob without 'parameter' stores every parameter, missing ones as the job's defaults at request time");
        assertApproverSees(none, "DATE", "2000-01-01", "MODE", "full");

        changeDefaults();
        FreeStyleBuild build = approveAndRun(none);
        assertEquals("2000-01-01", value(build, "DATE"), "#40: the build runs with the stored DATE, not the default at build time");
        assertEquals("full", value(build, "MODE"), "#40: the build runs with the stored MODE, not the default at build time");
    }

    /**
     * T-05-146 (#40): a multipart submission whose {@code json} carries only DATE=2026-09-01 stores that and
     * MODE=full (the default at request time) explicitly; a1 sees both; after the defaults change the
     * approved build runs with DATE=2026-09-01, MODE=full. Guard first: a scripted submission without
     * {@code json} and without parameter fields stores both defaults (the T-SEC-45 rule).
     */
    @Test
    public void t_05_146_multipartJsonWithSomeParametersStoresTheMissingDefaults() throws Exception {
        String scripted = urlencoded(null);
        assertEquals(map("DATE", "2000-01-01", "MODE", "full"), stored(scripted),
                "guard (T-SEC-45): a submission without json stores both defaults explicitly");

        String some = multipart(entry("DATE", "2026-09-01"));
        assertEquals(map("DATE", "2026-09-01", "MODE", "full"), stored(some),
                "#40: a json blob with only some parameters stores the missing ones as the job's defaults at request time");
        assertApproverSees(some, "DATE", "2026-09-01", "MODE", "full");

        changeDefaults();
        FreeStyleBuild build = approveAndRun(some);
        assertEquals("2026-09-01", value(build, "DATE"));
        assertEquals("full", value(build, "MODE"), "#40: the build runs with the stored MODE, not the default at build time");
    }

    /**
     * T-05-147 (#40): an urlencoded submission with a {@code json} field that has no {@code parameter}
     * stores DATE=2000-01-01 and MODE=full explicitly, and after the defaults change the approved build
     * runs with them. Guard first: the same urlencoded submission without {@code json} stores both (T-SEC-45).
     */
    @Test
    public void t_05_147_urlencodedJsonWithoutParametersStoresExplicitDefaults() throws Exception {
        String scripted = urlencoded(null);
        assertEquals(map("DATE", "2000-01-01", "MODE", "full"), stored(scripted),
                "guard (T-SEC-45): an urlencoded submission without json stores both defaults explicitly");

        JSONObject json = new JSONObject();
        json.put("reason", "month-end batch run");
        json.put("approvers", JSONArray.fromObject(new String[] {"a1"}));
        String withJson = urlencoded(json);
        assertEquals(map("DATE", "2000-01-01", "MODE", "full"), stored(withJson),
                "#40: an urlencoded json blob without 'parameter' stores every parameter as the job's default at request time");

        changeDefaults();
        FreeStyleBuild build = approveAndRun(withJson);
        assertEquals("2000-01-01", value(build, "DATE"));
        assertEquals("full", value(build, "MODE"));
    }

    /**
     * T-05-148 (#40, the remaining form): a multipart submission with neither {@code json} nor parameter
     * parts stores DATE=2000-01-01 and MODE=full explicitly, and after the defaults change the approved build
     * runs with them. Guard first: a multipart submission whose {@code json} carries both parameters stores
     * them.
     */
    @Test
    public void t_05_148_multipartWithoutJsonStoresExplicitDefaults() throws Exception {
        String full = multipart(entry("DATE", "2026-09-01"), entry("MODE", "partial"));
        assertEquals(map("DATE", "2026-09-01", "MODE", "partial"), stored(full), "guard: the full multipart submission");

        List<NameValuePair> fields = new ArrayList<>();
        fields.add(new NameValuePair("reason", "month-end batch run"));
        fields.add(new NameValuePair("approvers", "a1"));
        String bare = created(true, fields, "the multipart submission without json");
        assertEquals(map("DATE", "2000-01-01", "MODE", "full"), stored(bare),
                "#40: a multipart submission without json stores every parameter as the job's default at request time");

        changeDefaults();
        FreeStyleBuild build = approveAndRun(bare);
        assertEquals("2000-01-01", value(build, "DATE"));
        assertEquals("full", value(build, "MODE"));
    }

    // ------------------------------------------------------------------ helpers

    private static String[] entry(String name, String value) {
        return new String[] {name, value};
    }

    /** The browser's multipart shape: raw name/value parts and a json {@code parameter} entry per given parameter. */
    private String multipart(String[]... parameters) throws Exception {
        List<NameValuePair> parts = new ArrayList<>();
        JSONArray json = parameters.length == 0 ? null : new JSONArray();
        for (String[] p : parameters) {
            parts.add(new NameValuePair("name", p[0]));
            parts.add(new NameValuePair("value", p[1]));
            json.add(new JSONObject().element("name", p[0]).element("value", p[1]));
        }
        return submitRunMultipartOk(j, "u1", job, "month-end batch run", parts, json, "a1");
    }

    /** An urlencoded submission of reason and approvers, with the given {@code json} field or none. */
    private String urlencoded(JSONObject json) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("reason", "month-end batch run"));
        params.add(new NameValuePair("approvers", "a1"));
        if (json != null) {
            params.add(new NameValuePair("json", json.toString()));
        }
        return created(false, params, "the urlencoded submission" + (json == null ? " without json" : " with json"));
    }

    /** Posts {@code fields} (urlencoded, or multipart) and returns the id of the single request it created (asserted). */
    private String created(boolean multipart, List<NameValuePair> fields, String what) throws Exception {
        Set<String> before = runRequestIds();
        String path = job.getUrl() + "batch-control/submit";
        WebResponse response = multipart
                ? ApproverFormFixtures.postMultipart(j, "u1", path, fields)
                : ApproverFormFixtures.post(j, "u1", path, fields);
        assertSuccess(response, what);
        Set<String> after = runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), what + " creates exactly one request, got " + after);
        return after.iterator().next();
    }

    private static Map<String, String> stored(String id) {
        return new TreeMap<>(RunRequestService.get().load(id).getParameters());
    }

    private static Map<String, String> map(String... nameValues) {
        Map<String, String> out = new TreeMap<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            out.put(nameValues[i], nameValues[i + 1]);
        }
        return out;
    }

    private void assertApproverSees(String id, String... nameValues) throws Exception {
        WebResponse page = ApproverFormFixtures.get(j, "a1", "batch-control/requests/" + id + "/");
        assertEquals(200, page.getStatusCode(), "a1 opens the request page");
        String text = RenameRefusalFixtures.visible(page.getContentAsString());
        assertFalse(text.contains("No parameters."), "#40: the approver must not be told there are no parameters: " + excerpt(text));
        for (String s : nameValues) {
            assertTrue(text.contains(s), "#40: the approver sees " + s + ": " + excerpt(text));
        }
    }

    private void changeDefaults() throws Exception {
        job.removeProperty(ParametersDefinitionProperty.class);
        job.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("DATE", "1999-12-31"),
                new ChoiceParameterDefinition("MODE", new String[] {"partial", "full"}, "how much to run")));
        assertNotNull(job.getProperty(BatchControlJobProperty.class), "fixture: the job still requires approval");
    }

    private FreeStyleBuild approveAndRun(String id) throws Exception {
        int before = job.getBuilds().size();
        assertSuccess(decideRun(j, "a1", id, "approve", "ok"), "the approval");
        j.waitUntilNoActivity();
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(id).getStatus(), "fixture: the approved request runs");
        assertEquals(before + 1, job.getBuilds().size(), "fixture: exactly one build");
        return job.getLastBuild();
    }

    private static String value(FreeStyleBuild build, String name) {
        ParametersAction action = build.getAction(ParametersAction.class);
        assertNotNull(action, "the build must carry its parameters");
        assertNotNull(action.getParameter(name), "the build must carry parameter " + name);
        Object v = action.getParameter(name).getValue();
        return v instanceof String ? (String) v : String.valueOf(v);
    }
}
