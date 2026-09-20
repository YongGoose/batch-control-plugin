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
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * SPEC item 1 (global switches). Matrix rows T-01-01 .. T-01-06.
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 * Expected API contract is listed in the test-author report for this slice.
 */
public class GlobalSwitchTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    /** T-01-01: both switches off (fresh install) + approvalRequired job -> Build Now runs as before. */
    @Test
    public void t_01_01_switchesOffBuildRunsNormally() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("batch-job");
        p.addProperty(new BatchControlJobProperty(true));

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getPage(new WebRequest(wc.createCrumbedUrl(p.getUrl() + "build"), HttpMethod.POST));
        j.waitUntilNoActivity();

        FreeStyleBuild build = p.getBuildByNumber(1);
        assertNotNull("with both switches off the build must run exactly as before installation", build);
        j.assertBuildStatusSuccess(build);
    }

    /** T-01-02: admin turns runControlEnabled false -> true; ChangeRecord(CONFIG_TOGGLE, admin, false->true). */
    @Test
    public void t_01_02_enableRunControlLeavesConfigToggleRecord() throws Exception {
        enableSecurityWithAdmin();
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        assertFalse("fresh install must default to run control off", cfg.isRunControlEnabled());

        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            cfg.setRunControlEnabled(true);
            cfg.save();
        }

        ChangeRecord toggle = findConfigToggle("runControlEnabled", "false -> true");
        assertNotNull("toggling the switch must leave a CONFIG_TOGGLE change record", toggle);
        assertEquals("admin", toggle.getUser());
        assertNotNull("the record must carry the toggle time", toggle.getAt());
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
        assertNull("delete must succeed as before while both switches are off",
                j.jenkins.getItemByFullName("legacy-job"));
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
        assertEquals("configure must not be blocked while change control is off",
                "run control only", p.getDescription());

        String jobPageText = wc.getPage(p).asNormalizedText();
        assertFalse("change-control UI must not appear when changeControlEnabled=false",
                jobPageText.contains("Request Grant"));
        String rootPageText = wc.goTo("").asNormalizedText();
        assertFalse("change-control UI must not appear on the root page either",
                rootPageText.contains("Request Grant"));

        wc.getPage(new WebRequest(wc.createCrumbedUrl(p.getUrl() + "doDelete"), HttpMethod.POST));
        assertNull("delete must not be vetoed while change control is off",
                j.jenkins.getItemByFullName("plain-job"));
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
        assertNotNull("run control is off, so the build must not be blocked", build);
        j.assertBuildStatusSuccess(build);

        String jobPageText = wc.getPage(p).asNormalizedText();
        assertTrue("Build Now must remain when run control is off", jobPageText.contains("Build Now"));
        assertFalse("run-control UI must not appear when runControlEnabled=false",
                jobPageText.contains("Request Run"));
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
        assertNotNull("toggling the switch off must leave a CONFIG_TOGGLE change record", toggle);
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
