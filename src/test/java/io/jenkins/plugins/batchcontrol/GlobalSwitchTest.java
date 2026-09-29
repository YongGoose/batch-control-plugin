package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.time.YearMonth;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 1 (global switches). Matrix rows T-01-01 .. T-01-06.
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 * Expected API contract is listed in the test-author report for this slice.
 */
@WithJenkins
public class GlobalSwitchTest {

    private JenkinsRule j;

    @BeforeEach
    void setUpJenkins(JenkinsRule rule) {
        this.j = rule;
    }

    /** T-01-01: both switches off (fresh install) + approvalRequired job -> Build Now runs as before. */
    @Test
    public void t_01_01_switchesOffBuildRunsNormally() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("batch-job");
        p.addProperty(new BatchControlJobProperty(true));

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getPage(new WebRequest(wc.createCrumbedUrl(p.getUrl() + "build"), HttpMethod.POST));
        j.waitUntilNoActivity();

        FreeStyleBuild build = p.getBuildByNumber(1);
        assertNotNull(build, "with both switches off the build must run exactly as before installation");
        j.assertBuildStatusSuccess(build);
    }

    /**
     * T-01-15 (security-18 S-18-06; SPEC 1 and CLAUDE.md: with the switch off, existing Jenkins
     * behaviour does not change): with run control off, a build of a job carrying
     * {@code approvalRequired=true} lists no Batch Control action in its {@code api/json}
     * {@code actions} array. The array itself must be non-empty (core's CauseAction), so an
     * unreadable answer cannot pass. Note 152.
     */
    @Test
    public void t_01_15_runControlOffBuildApiListsNoBatchControlAction() throws Exception {
        assertFalse(BatchControlGlobalConfiguration.get().isRunControlEnabled(), "fixture: run control is off");
        FreeStyleProject p = j.createFreeStyleProject("api-off");
        p.addProperty(new BatchControlJobProperty(true));
        FreeStyleBuild build = j.buildAndAssertSuccess(p);

        org.htmlunit.Page page = j.createWebClient().goTo(build.getUrl() + "api/json", "application/json");
        net.sf.json.JSONObject json = net.sf.json.JSONObject.fromObject(page.getWebResponse().getContentAsString());
        net.sf.json.JSONArray actions = json.getJSONArray("actions");
        boolean sawCore = false;
        for (int i = 0; i < actions.size(); i++) {
            Object action = actions.get(i);
            if (!(action instanceof net.sf.json.JSONObject)) {
                continue;
            }
            String cls = ((net.sf.json.JSONObject) action).optString("_class", "");
            sawCore |= cls.equals("hudson.model.CauseAction");
            assertFalse(cls.startsWith("io.jenkins.plugins.batchcontrol."), "with run control off a build's api/json must"
                    + " list no Batch Control action, found " + cls + " in " + actions);
        }
        assertTrue(sawCore, "fixture: the build's api/json must list core's CauseAction: " + actions);

        // api/json renders an action that exports nothing as an anonymous {} entry, so the same
        // check is made on the public Actionable API that api/json is built from.
        for (hudson.model.Action action : build.getAllActions()) {
            assertFalse(action.getClass().getName().startsWith("io.jenkins.plugins.batchcontrol."), "with run control"
                    + " off a build must carry no Batch Control action, found " + action.getClass().getName());
        }
    }

    /**
     * T-01-16 (security-19 S-19-03; SPEC 1 and CLAUDE.md: with the switch off, existing Jenkins
     * behaviour does not change): while change control is off, the response a POST endpoint
     * receives is not wrapped by any Batch Control class. Observed through a test root action
     * that walks the public servlet wrapper chain of its response ({@code ServletResponseWrapper
     * #getResponse}); its answer is the one it wrote. Premise: with change control on, the walk
     * does see the plugin's wrapper (the D-35b/D-48 guard needs it then), so a walk that sees
     * nothing cannot pass. Note 156.
     */
    @Test
    public void t_01_16_changeControlOffLeavesPostResponsesUnwrapped() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.save();
        String on = PluginInteractionFixtures.post(j, null, "s19-wrap/probe").getWebResponse().getContentAsString();
        assertTrue(on.startsWith("probe-ok"), "fixture: the probe must answer its own text: " + on);
        assertTrue(on.contains("io.jenkins.plugins.batchcontrol."), "premise: with change control on the walk must see"
                + " the plugin's response wrapper, or this row cannot observe it: " + on);

        cfg.setChangeControlEnabled(false);
        cfg.save();
        org.htmlunit.Page off = PluginInteractionFixtures.post(j, null, "s19-wrap/probe");
        String body = off.getWebResponse().getContentAsString();
        assertEquals(200, off.getWebResponse().getStatusCode(), "the POST answer must be unchanged");
        assertTrue(body.startsWith("probe-ok"), "the POST answer must be the endpoint's own text: " + body);
        assertFalse(body.contains("io.jenkins.plugins.batchcontrol."), "with change control off no Batch Control class may"
                + " wrap a POST response: " + body);
    }

    /** Test-only root action: answers "probe-ok" plus the class chain of the response it receives. */
    @org.jvnet.hudson.test.TestExtension("t_01_16_changeControlOffLeavesPostResponsesUnwrapped")
    public static class WrapProbe implements hudson.model.RootAction {
        @Override
        public String getIconFileName() {
            return null;
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getUrlName() {
            return "s19-wrap";
        }

        @org.kohsuke.stapler.verb.POST
        public void doProbe(org.kohsuke.stapler.StaplerRequest2 req, org.kohsuke.stapler.StaplerResponse2 rsp)
                throws java.io.IOException {
            StringBuilder chain = new StringBuilder();
            jakarta.servlet.ServletResponse r = rsp;
            while (r != null) {
                chain.append(' ').append(r.getClass().getName());
                r = r instanceof jakarta.servlet.ServletResponseWrapper
                        ? ((jakarta.servlet.ServletResponseWrapper) r).getResponse() : null;
            }
            rsp.setContentType("text/plain;charset=UTF-8");
            rsp.getWriter().print("probe-ok" + chain);
        }
    }

    /** T-01-02: admin turns runControlEnabled false -> true; ChangeRecord(CONFIG_TOGGLE, admin, false->true). */
    @Test
    public void t_01_02_enableRunControlLeavesConfigToggleRecord() throws Exception {
        enableSecurityWithAdmin();
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        assertFalse(cfg.isRunControlEnabled(), "fresh install must default to run control off");

        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            cfg.setRunControlEnabled(true);
            cfg.save();
        }

        ChangeRecord toggle = findConfigToggle("runControlEnabled", "false -> true");
        assertNotNull(toggle, "toggling the switch must leave a CONFIG_TOGGLE change record");
        assertEquals("admin", toggle.getUser());
        assertNotNull(toggle.getAt(), "the record must carry the toggle time");
    }

    /** T-01-03: both switches off -> job reconfigure and delete succeed exactly as before (no veto, no block). */
    @Test
    public void t_01_03_switchesOffConfigureAndDeleteUnchanged() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("legacy-job");
        JenkinsRule.WebClient wc = j.createWebClient();

        HtmlForm form = wc.getPage(p, "configure").getFormByName("config");
        form.getTextAreaByName("description").setText("updated by test");
        j.submit(form);
        assertEquals("updated by test", p.getDescription());

        wc.getPage(new WebRequest(wc.createCrumbedUrl(p.getUrl() + "doDelete"), HttpMethod.POST));
        assertNull(j.jenkins.getItemByFullName("legacy-job"), "delete must succeed as before while both switches are off");
    }

    /** T-01-04: runControlEnabled=true, changeControlEnabled=false -> no change-control blocking and no change-control UI. */
    @Test
    public void t_01_04_runControlOnlyNoChangeControlUiOrBlocking() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.save();

        FreeStyleProject p = j.createFreeStyleProject("plain-job");
        JenkinsRule.WebClient wc = j.createWebClient();

        HtmlForm form = wc.getPage(p, "configure").getFormByName("config");
        form.getTextAreaByName("description").setText("run control only");
        j.submit(form);
        assertEquals("run control only", p.getDescription(), "configure must not be blocked while change control is off");

        String jobPageText = wc.getPage(p).asNormalizedText();
        assertFalse(jobPageText.contains("Request Grant"), "change-control UI must not appear when changeControlEnabled=false");
        String rootPageText = wc.goTo("").asNormalizedText();
        assertFalse(rootPageText.contains("Request Grant"), "change-control UI must not appear on the root page either");

        wc.getPage(new WebRequest(wc.createCrumbedUrl(p.getUrl() + "doDelete"), HttpMethod.POST));
        assertNull(j.jenkins.getItemByFullName("plain-job"), "delete must not be vetoed while change control is off");
    }

    /** T-01-05: changeControlEnabled=true, runControlEnabled=false, approvalRequired job -> build runs, no run-control UI. */
    @Test
    public void t_01_05_changeControlOnlyBuildRunsAndNoRunControlUi() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.save();

        FreeStyleProject p = j.createFreeStyleProject("batch-job");
        p.addProperty(new BatchControlJobProperty(true));

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getPage(new WebRequest(wc.createCrumbedUrl(p.getUrl() + "build"), HttpMethod.POST));
        j.waitUntilNoActivity();
        FreeStyleBuild build = p.getBuildByNumber(1);
        assertNotNull(build, "run control is off, so the build must not be blocked");
        j.assertBuildStatusSuccess(build);

        String jobPageText = wc.getPage(p).asNormalizedText();
        assertTrue(jobPageText.contains("Build Now"), "Build Now must remain when run control is off");
        assertFalse(jobPageText.contains("Request Run"), "run-control UI must not appear when runControlEnabled=false");
    }

    /** T-01-06: admin turns runControlEnabled true -> false; ChangeRecord(CONFIG_TOGGLE, admin, true->false). */
    @Test
    public void t_01_06_disableRunControlLeavesConfigToggleRecord() throws Exception {
        enableSecurityWithAdmin();
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true); // precondition, set as SYSTEM
        cfg.save();

        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            cfg.setRunControlEnabled(false);
            cfg.save();
        }

        ChangeRecord toggle = findConfigToggle("runControlEnabled", "true -> false");
        assertNotNull(toggle, "toggling the switch off must leave a CONFIG_TOGGLE change record");
        assertEquals("admin", toggle.getUser());
        assertNotNull(toggle.getAt());
    }

    private void enableSecurityWithAdmin() {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin"));
    }

    /** Finds the latest CONFIG_TOGGLE record for the given setting key and old/new detail. */
    private ChangeRecord findConfigToggle(String target, String detail) {
        List<ChangeRecord> records = FileStore.get().listChangeRecords(YearMonth.now());
        return records.stream()
                .filter(rec -> rec.getType() == ChangeType.CONFIG_TOGGLE)
                .filter(rec -> target.equals(rec.getTarget()))
                .filter(rec -> detail.equals(rec.getDetail()))
                .reduce((first, second) -> second)
                .orElse(null);
    }
}
