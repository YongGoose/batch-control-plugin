package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import hudson.model.User;
import hudson.security.AuthorizationMatrixProperty;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import jenkins.security.QueueItemAuthenticatorConfiguration;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.authorizeproject.GlobalQueueItemAuthenticator;
import org.jenkinsci.plugins.authorizeproject.strategy.SpecificUsersAuthorizationStrategy;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.WINDOW_MINUTES;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.has;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 2, the acceptance line after D-48 (D-58, e2e-03 DEF-38): while change control is on,
 * a save of an item's authorization property that adds or widens entries for a user who holds or
 * held within the last 30 days a grant covering the item (or for a group of that user, including
 * {@code authenticated}) is reverted and recorded as GRANT_VIOLATION, whoever makes it, unless it
 * is made through an HTTP request by an Overall/Administer holder; a build's reverted save is
 * named in its build log. Matrix rows T-02-51 .. T-02-56 (note 175).
 *
 * <p>bob (StrategyFixtures) holds a JOB CONFIGURE grant on the Pipeline job {@code pipe} and
 * edits its script through {@code POST config.xml} to a {@code properties([authorizationMatrix(...)])}
 * step. Builds run as {@code batch} (a global default build authorization; batch holds Overall/Read,
 * Job/Read and Job/Build, no Configure), so D-50a/D-50b are satisfied and the exposure is the
 * script alone. alice never holds a grant. Time moves through {@link BatchClock}.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-58 and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class AuthorizationEntryGuardTest {

    private JenkinsRule j;
    private WorkflowJob pipe;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy());
        strategy.add(Jenkins.READ, PermissionEntry.user("batch"));
        strategy.add(Item.READ, PermissionEntry.user("batch"));
        strategy.add(Item.BUILD, PermissionEntry.user("batch"));
        j.jenkins.setAuthorizationStrategy(strategy);
        User.getById("batch", true).save();
        QueueItemAuthenticatorConfiguration.get().getAuthenticators()
                .add(new GlobalQueueItemAuthenticator(new SpecificUsersAuthorizationStrategy("batch")));
        StrategyFixtures.changeControlOn();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        pipe = j.jenkins.createProject(WorkflowJob.class, "pipe");
        pipe.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-02-51 (D-58): bob, inside his CONFIGURE window, changes the script to give himself
     * Job/Configure through {@code properties([authorizationMatrix(...)])} and a build runs. After
     * the build bob has no permanent entry (in memory and on disk); a GRANT_VIOLATION record names
     * bob and the job; the build log names the reverted entry; after the window bob has no
     * Configure.
     */
    @Test
    public void t_02_51_scriptEntryForGrantHolderIsRevertedAfterTheBuild() throws Exception {
        grantBob();
        int violations = violations().size();
        editScriptAsBob(entryFor("user", "bob"));
        WorkflowRun run = j.buildAndAssertSuccess(pipe);

        assertNoEntryFor("bob");
        List<ChangeRecord> v = violations();
        assertEquals(violations + 1, v.size(), "one GRANT_VIOLATION record must be written: " + describe(v));
        ChangeRecord record = v.get(v.size() - 1);
        assertEquals("pipe", record.getTarget(), "the record must name the job");
        assertTrue((record.getUser() + " " + record.getDetail()).contains("bob"), "the record must name bob: " + describe(v));
        String log = JenkinsRule.getLog(run);
        assertTrue(log.contains("bob") && log.toLowerCase(java.util.Locale.ROOT).contains("configure"),
                "the build log must name the reverted entry: " + log);

        afterWindow();
        assertFalse(has(pipe, "bob", Item.CONFIGURE), "after the window bob must hold no Configure on the job");
    }

    /**
     * T-02-52 (D-58, "held within the last 30 days"): bob edits the script inside the window, the
     * window ends, then the build runs. The entry is still reverted and recorded.
     */
    @Test
    public void t_02_52_entryIsRevertedWhenTheBuildRunsAfterTheWindow() throws Exception {
        grantBob();
        int violations = violations().size();
        editScriptAsBob(entryFor("user", "bob"));
        afterWindow();
        assertFalse(has(pipe, "bob", Item.CONFIGURE), "premise: the window has ended");

        j.buildAndAssertSuccess(pipe);

        assertNoEntryFor("bob");
        assertEquals(violations + 1, violations().size(), "the reverted save must be recorded as GRANT_VIOLATION");
        assertFalse(has(pipe, "bob", Item.CONFIGURE), "bob must hold no Configure");
    }

    /** T-02-53 (D-58): the script gives Job/Configure to the group {@code authenticated}; it is reverted. */
    @Test
    public void t_02_53_groupEntryIsReverted() throws Exception {
        grantBob();
        int violations = violations().size();
        editScriptAsBob(entryFor("group", "authenticated"));
        j.buildAndAssertSuccess(pipe);

        assertNoEntryFor("authenticated");
        assertEquals(violations + 1, violations().size(), "the reverted save must be recorded as GRANT_VIOLATION");
        afterWindow();
        assertFalse(has(pipe, "bob", Item.CONFIGURE), "bob must not keep Configure through the group");
    }

    /**
     * T-02-54 (D-58, outside the rule): the script gives Job/Configure to alice, who never held a
     * grant. The entry is kept and no GRANT_VIOLATION is written (documented as outside the rule).
     */
    @Test
    public void t_02_54_entryForAnUnguardedPrincipalIsKept() throws Exception {
        grantBob();
        int violations = violations().size();
        editScriptAsBob(entryFor("user", "alice"));
        j.buildAndAssertSuccess(pipe);

        assertTrue(entryExists("alice"), "an entry for a principal that never held a grant is outside the rule and kept");
        assertEquals(violations, violations().size(), "no GRANT_VIOLATION for an unguarded principal");
    }

    /**
     * T-02-55 (D-58 exception): the administrator gives bob a permanent Job/Configure entry
     * through an HTTP request (POST config.xml) while bob holds a grant on the job. It is kept.
     */
    @Test
    public void t_02_55_administratorsEntryThroughHttpIsKept() throws Exception {
        grantBob();
        int violations = violations().size();
        String xml = pipe.getConfigFile().asString();
        String withProperty = xml.replace("<properties/>", "<properties>" + propertyXml("bob") + "</properties>");
        assertFalse(withProperty.equals(xml), "fixture: the job config must have an empty <properties/>: " + xml);
        assertTrue(post("admin", withProperty) < 400, "fixture: the administrator's save must succeed");

        assertTrue(entryExists("bob"), "an administrator's deliberate entry through an HTTP request must be kept");
        assertEquals(violations, violations().size(), "no GRANT_VIOLATION for the administrator's save");
    }

    /** T-02-56 (D-58, SPEC 1): with change control off the same script entry for bob is kept. */
    @Test
    public void t_02_56_changeControlOffRevertsNothing() throws Exception {
        grantBob();
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(false);
        cfg.save();
        pipe.setDefinition(new CpsFlowDefinition(entryFor("user", "bob"), true));
        j.buildAndAssertSuccess(pipe);

        assertTrue(entryExists("bob"), "with change control off nothing is reverted");
    }

    // ---------------------------------------------------------------- helpers

    private void grantBob() throws Exception {
        StrategyFixtures.grant("bob", GrantScope.Type.JOB, "pipe", Arrays.asList(GrantAction.CONFIGURE));
        assertTrue(has(pipe, "bob", Item.CONFIGURE), "premise: the grant confers Configure on the job");
    }

    private void afterWindow() {
        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(WINDOW_MINUTES + 1)), ZoneOffset.UTC));
    }

    /** A Pipeline script whose properties step gives Job/Configure to {@code kind} {@code name}. */
    private static String entryFor(String kind, String name) {
        return "properties([authorizationMatrix(entries: [" + kind + "(name: '" + name
                + "', permissions: ['Job/Configure'])])])\necho 'authorization set'";
    }

    private void editScriptAsBob(String script) throws Exception {
        String xml = pipe.getConfigFile().asString();
        String escaped = script.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("'", "&apos;").replace("\"", "&quot;");
        String edited = xml.replace("<script>echo &apos;hello&apos;</script>", "<script>" + escaped + "</script>");
        assertFalse(edited.equals(xml), "fixture: the script element must be replaced: " + xml);
        assertTrue(post("bob", edited) < 400, "fixture: bob's script edit inside his window must be saved");
        assertTrue(((CpsFlowDefinition) pipe.getDefinition()).getScript().contains("authorizationMatrix"),
                "fixture: the new script must be stored");
    }

    private int post(String user, String xml) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        WebRequest req = new WebRequest(wc.createCrumbedUrl(pipe.getUrl() + "config.xml"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml");
        req.setRequestBody(xml);
        return wc.getPage(req).getWebResponse().getStatusCode();
    }

    private static String propertyXml(String user) {
        return "<hudson.security.AuthorizationMatrixProperty>"
                + "<inheritanceStrategy class=\"org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy\"/>"
                + "<permission>USER:hudson.model.Item.Configure:" + user + "</permission>"
                + "</hudson.security.AuthorizationMatrixProperty>";
    }

    private WorkflowJob current() {
        return j.jenkins.getItemByFullName("pipe", WorkflowJob.class);
    }

    private boolean entryExists(String sid) throws Exception {
        AuthorizationMatrixProperty amp = current().getProperty(AuthorizationMatrixProperty.class);
        if (amp == null) {
            return false;
        }
        for (Map.Entry<Permission, Set<PermissionEntry>> e : amp.getGrantedPermissionEntries().entrySet()) {
            if (e.getValue().stream().anyMatch(pe -> sid.equals(pe.getSid()))) {
                return true;
            }
        }
        return false;
    }

    private void assertNoEntryFor(String sid) throws Exception {
        assertFalse(entryExists(sid), "the entry for " + sid + " must be reverted in memory");
        assertFalse(current().getConfigFile().asString().contains(":" + sid + "</permission>"),
                "the entry for " + sid + " must not be stored in config.xml");
    }

    private static List<ChangeRecord> violations() {
        return StrategyFixtures.records(ChangeType.GRANT_VIOLATION);
    }

    private static String describe(List<ChangeRecord> records) {
        StringBuilder out = new StringBuilder("[");
        for (ChangeRecord r : records) {
            out.append(r.getUser()).append(' ').append(r.getTarget()).append(' ').append(r.getDetail()).append("; ");
        }
        return out.append(']').toString();
    }
}
