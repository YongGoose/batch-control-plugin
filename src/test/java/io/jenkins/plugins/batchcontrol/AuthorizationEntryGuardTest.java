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
import org.jenkinsci.plugins.matrixauth.inheritance.NonInheritingStrategy;
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
 * named in its build log. Matrix rows T-02-51 .. T-02-65 (notes 175, 176).
 *
 * <p>D-58a (security-27) replaced the principal rule with a per-item rule: an item in the scope of an
 * active grant, or whose configuration was changed under a grant and not reviewed since by an HTTP
 * save of a native Item/Configure or Overall/Administer holder, is guarded; any widening of access on
 * it is reverted whoever makes it, except an administrator's HTTP save.
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
     * T-02-52 (D-58a): bob edits the script inside the window, the window ends, then the build
     * runs. The job stays guarded because its configuration was changed under the grant and not
     * reviewed since, so the entry is still reverted and recorded.
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
     * T-02-54 (D-58a, revised): on the guarded item the script gives Job/Configure to alice, who
     * never held a grant. Guarding is per item, so the entry is reverted and recorded.
     */
    @Test
    public void t_02_54_entryForAnyPrincipalOnAGuardedItemIsReverted() throws Exception {
        grantBob();
        int violations = violations().size();
        editScriptAsBob(entryFor("user", "alice"));
        j.buildAndAssertSuccess(pipe);

        assertNoEntryFor("alice");
        assertEquals(violations + 1, violations().size(), "the reverted save must be recorded as GRANT_VIOLATION");
    }

    /** T-02-57 (D-58a, S-27-01): the script gives Job/Configure to {@code anonymous}; it is reverted. */
    @Test
    public void t_02_57_anonymousEntryIsReverted() throws Exception {
        grantBob();
        int violations = violations().size();
        editScriptAsBob(entryFor("user", "anonymous"));
        j.buildAndAssertSuccess(pipe);

        assertNoEntryFor("anonymous");
        assertEquals(violations + 1, violations().size(), "the reverted save must be recorded as GRANT_VIOLATION");
    }

    /**
     * T-02-58 (D-58a, S-27-02): a script (SYSTEM, not an HTTP request) saves the guarded job with
     * two AuthorizationMatrixProperty elements, the second giving carol Job/Configure. Afterwards
     * the job carries at most one authorization property, carol has no entry and no Configure, and
     * the save is recorded.
     */
    @Test
    public void t_02_58_secondAuthorizationPropertyIsReverted() throws Exception {
        grantBob();
        int violations = violations().size();
        String xml = current().getConfigFile().asString();
        String two = xml.replace("<properties/>", "<properties>" + propertyXml("Inherit", "alice")
                + propertyXml("Inherit", "carol") + "</properties>");
        assertFalse(two.equals(xml), "fixture: the job config must have an empty <properties/>: " + xml);
        saveAsScript(two);

        long count = current().getAllProperties().stream().filter(p -> p instanceof AuthorizationMatrixProperty).count();
        assertTrue(count <= 1, "the job must not keep two authorization properties, found " + count);
        assertNoEntryFor("carol");
        assertFalse(has(current(), "carol", Item.CONFIGURE), "carol must not gain Configure through a second property");
        assertTrue(violations().size() > violations, "the reverted save must be recorded as GRANT_VIOLATION");
    }

    /**
     * T-02-59 (D-58a, S-27-04): the guarded job's property is nonInheriting (only admin listed); a
     * script save switches it to inheriting from the parent. The switch is reverted: the property is
     * still nonInheriting, and the save is recorded.
     */
    @Test
    public void t_02_59_wideningInheritanceChangeIsReverted() throws Exception {
        baselineNonInheriting();
        grantBob();
        int violations = violations().size();
        String xml = current().getConfigFile().asString();
        String widened = xml.replace(NON_INHERITING, INHERITING);
        assertFalse(widened.equals(xml), "fixture: the baseline must be nonInheriting: " + xml);
        saveAsScript(widened);

        AuthorizationMatrixProperty amp = current().getProperty(AuthorizationMatrixProperty.class);
        assertTrue(amp != null && amp.getInheritanceStrategy() instanceof NonInheritingStrategy,
                "the widening inheritance change must be reverted to nonInheriting: " + (amp == null ? "no property"
                        : amp.getInheritanceStrategy()));
        assertTrue(violations().size() > violations, "the reverted save must be recorded as GRANT_VIOLATION");
    }

    /**
     * T-02-60 (D-58a, S-27-04): a script save removes the guarded job's nonInheriting property.
     * The removal is reverted: the property is back, still nonInheriting, and the save is recorded.
     */
    @Test
    public void t_02_60_removingThePropertyIsReverted() throws Exception {
        baselineNonInheriting();
        grantBob();
        int violations = violations().size();
        String xml = current().getConfigFile().asString();
        int start = xml.indexOf("<hudson.security.AuthorizationMatrixProperty>");
        int end = xml.indexOf("</hudson.security.AuthorizationMatrixProperty>") + "</hudson.security.AuthorizationMatrixProperty>".length();
        assertTrue(start > 0 && end > start, "fixture: the baseline property must be in config.xml: " + xml);
        saveAsScript(xml.substring(0, start) + xml.substring(end));

        AuthorizationMatrixProperty amp = current().getProperty(AuthorizationMatrixProperty.class);
        assertTrue(amp != null && amp.getInheritanceStrategy() instanceof NonInheritingStrategy,
                "removing the property must be reverted: " + (amp == null ? "no property" : amp.getInheritanceStrategy()));
        assertTrue(violations().size() > violations, "the reverted save must be recorded as GRANT_VIOLATION");
    }

    /**
     * T-02-61 (D-58a, S-27-03): bob edits the script inside his window, the administrator renames
     * the job, and the build runs under the new name. Guarding follows the rename: bob's entry is
     * reverted and recorded.
     */
    @Test
    public void t_02_61_guardingFollowsARename() throws Exception {
        grantBob();
        int violations = violations().size();
        editScriptAsBob(entryFor("user", "bob"));
        current().renameTo("pipe-renamed");
        WorkflowJob renamed = j.jenkins.getItemByFullName("pipe-renamed", WorkflowJob.class);
        j.buildAndAssertSuccess(renamed);

        AuthorizationMatrixProperty amp = renamed.getProperty(AuthorizationMatrixProperty.class);
        assertTrue(amp == null || amp.getGrantedPermissionEntries().values().stream()
                .noneMatch(s -> s.stream().anyMatch(pe -> "bob".equals(pe.getSid()))),
                "after the rename the widening must still be reverted");
        assertTrue(violations().size() > violations, "the reverted save must be recorded as GRANT_VIOLATION");
    }

    /**
     * T-02-62 (D-58a, S-27-05): bob holds a FOLDER CONFIGURE grant on {@code team}, which makes the
     * folder guarded. carol, who holds Job/Create natively (not an administrator), creates
     * {@code team/new} through {@code createItem} with a payload giving herself Job/Configure. The
     * new item's authorization entries are removed and the creation is recorded.
     */
    @Test
    public void t_02_62_creationInsideAGuardedFolderLosesItsEntries() throws Exception {
        BatchControlMatrixAuthorizationStrategy strategy = (BatchControlMatrixAuthorizationStrategy) j.jenkins.getAuthorizationStrategy();
        strategy.add(Item.CREATE, PermissionEntry.user("carol"));
        j.jenkins.createProject(com.cloudbees.hudson.plugins.folder.Folder.class, "team");
        StrategyFixtures.grant("bob", GrantScope.Type.FOLDER, "team", Arrays.asList(GrantAction.CONFIGURE));
        int violations = violations().size();

        String payload = "<?xml version='1.1' encoding='UTF-8'?><project><properties>" + propertyXml("Inherit", "carol")
                + "</properties><builders/><publishers/><buildWrappers/></project>";
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("carol");
        WebRequest req = new WebRequest(new java.net.URL(wc.createCrumbedUrl("job/team/createItem").toExternalForm()
                + "&name=new"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        req.setRequestBody(payload);
        wc.getPage(req);

        hudson.model.FreeStyleProject created = j.jenkins.getItemByFullName("team/new", hudson.model.FreeStyleProject.class);
        assertTrue(created != null, "fixture: carol's creation must have made team/new");
        AuthorizationMatrixProperty amp = created.getProperty(AuthorizationMatrixProperty.class);
        assertTrue(amp == null || amp.getGrantedPermissionEntries().values().stream()
                .noneMatch(s -> s.stream().anyMatch(pe -> "carol".equals(pe.getSid()))),
                "the new item's authorization entries must be removed inside a guarded folder");
        assertTrue(violations().size() > violations, "the removal must be recorded as GRANT_VIOLATION");
    }

    /**
     * T-02-63 (D-58a, S-27-07): bob plants the script inside his window and no build runs; the
     * first build runs 31 days after the window ended. The item was never reviewed, so it is still
     * guarded and bob's entry is reverted.
     */
    @Test
    public void t_02_63_plantedScriptIsStillRevertedAfterThirtyOneDays() throws Exception {
        grantBob();
        editScriptAsBob(entryFor("user", "bob"));
        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(WINDOW_MINUTES)).plus(Duration.ofDays(31)),
                ZoneOffset.UTC));
        j.buildAndAssertSuccess(pipe);

        assertNoEntryFor("bob");
        assertFalse(has(current(), "bob", Item.CONFIGURE), "bob must hold no Configure");
    }

    /**
     * T-02-64 (D-58b (3) review): bob plants the script inside his window; after the window c1
     * (native Item/Configure) POSTs {@code <job>/batch-control/markReviewed}. A GUARD_REVIEWED
     * record names c1, and the build's entry for bob is then kept without a GRANT_VIOLATION.
     */
    @Test
    public void t_02_64_explicitReviewClearsTheGuard() throws Exception {
        grantBob();
        editScriptAsBob(entryFor("user", "bob"));
        afterWindow();
        int reviewsBefore = records("GUARD_REVIEWED").size();
        int code = postForm("c1", current().getUrl() + "batch-control/markReviewed", null);
        assertTrue(code < 400, "c1's explicit review must succeed, got " + code);
        assertReviewedBy("c1", reviewsBefore);
        int violations = violations().size();

        j.buildAndAssertSuccess(current());

        assertTrue(entryExists("bob"), "after the review a Jenkinsfile widening is kept");
        assertEquals(violations, violations().size(), "no GRANT_VIOLATION after the review");
    }

    /** T-02-64b (D-58b (3)): the administrator reviews through the monitor; the widening is then kept. */
    @Test
    public void t_02_64b_administratorReviewThroughTheMonitor() throws Exception {
        grantBob();
        editScriptAsBob(entryFor("user", "bob"));
        afterWindow();
        int reviewsBefore = records("GUARD_REVIEWED").size();
        int code = postForm("admin", "manage/administrativeMonitor/batch-control-strategy/markReviewed", "item=pipe");
        assertTrue(code < 400, "the administrator's review must succeed, got " + code);
        assertReviewedBy("admin", reviewsBefore);

        j.buildAndAssertSuccess(current());
        assertTrue(entryExists("bob"), "after the review a Jenkinsfile widening is kept");
    }

    /** T-02-69 (D-58b (3)): bob, whose Configure comes only from his grant, cannot mark the job reviewed (403). */
    @Test
    public void t_02_69_grantOnlyUserCannotMarkReviewed() throws Exception {
        grantBob();
        editScriptAsBob(entryFor("user", "bob"));
        int reviewsBefore = records("GUARD_REVIEWED").size();
        assertEquals(403, postForm("bob", current().getUrl() + "batch-control/markReviewed", null),
                "a user whose Configure comes from a grant must not review");
        assertEquals(reviewsBefore, records("GUARD_REVIEWED").size(), "no GUARD_REVIEWED record");
        afterWindow();
        j.buildAndAssertSuccess(current());
        assertNoEntryFor("bob");
    }

    /** T-02-70 (D-58b (3)): c1's description edit (an ordinary HTTP save) does not clear the state. */
    @Test
    public void t_02_70_descriptionEditDoesNotClearTheState() throws Exception {
        grantBob();
        editScriptAsBob(entryFor("user", "bob"));
        afterWindow();
        assertTrue(post("c1", withDescription(current().getConfigFile().asString(), "edited")) < 400,
                "fixture: c1's description edit must be saved");
        j.buildAndAssertSuccess(current());
        assertNoEntryFor("bob");
    }

    /**
     * T-02-71 (D-58b (2)): bob, whose Run/Replay comes only from his CONFIGURE grant, replays build
     * #1 with a script that gives him Job/Configure. The job becomes guarded: the replay's entry is
     * reverted, and after the window a build of that script (kept by a script save) still has it
     * reverted.
     */
    @Test
    public void t_02_71_replayByAGrantHolderMarksTheJob() throws Exception {
        WorkflowJob other = j.jenkins.createProject(WorkflowJob.class, "replay-me");
        other.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        j.buildAndAssertSuccess(other);
        StrategyFixtures.grant("bob", GrantScope.Type.JOB, "replay-me", Arrays.asList(GrantAction.CONFIGURE));
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("bob");
        List<org.htmlunit.util.NameValuePair> params = new java.util.ArrayList<>();
        String script = entryFor("user", "bob");
        params.add(new org.htmlunit.util.NameValuePair("mainScript", script));
        params.add(new org.htmlunit.util.NameValuePair("json", "{\"mainScript\":" + jsonString(script) + "}"));
        WebRequest req = new WebRequest(wc.createCrumbedUrl(other.getUrl() + "1/replay/run"), HttpMethod.POST);
        req.setRequestParameters(params);
        wc.getPage(req);
        j.waitUntilNoActivity();
        assertTrue(other.getBuildByNumber(2) != null, "fixture: bob's replay must have run as #2");
        assertFalse(entryOn(other, "bob"), "the replay's entry must be reverted");

        afterWindow();
        other.setDefinition(new CpsFlowDefinition(script, true));
        j.buildAndAssertSuccess(other);
        assertFalse(entryOn(other, "bob"), "the replayed job stays guarded after the window");
    }

    /**
     * T-02-72 (D-58b (1)): bob holds a FOLDER CONFIGURE grant on {@code outer}; {@code outer/inner/deep}
     * is a Pipeline job two levels below whose script gives alice Job/Configure; the build's entry
     * is reverted, because guarding covers descendants.
     */
    @Test
    public void t_02_72_jobInANestedFolderIsGuarded() throws Exception {
        com.cloudbees.hudson.plugins.folder.Folder outer = j.jenkins.createProject(com.cloudbees.hudson.plugins.folder.Folder.class, "outer");
        com.cloudbees.hudson.plugins.folder.Folder inner = outer.createProject(com.cloudbees.hudson.plugins.folder.Folder.class, "inner");
        WorkflowJob deep = inner.createProject(WorkflowJob.class, "deep");
        deep.setDefinition(new CpsFlowDefinition(entryFor("user", "alice"), true));
        StrategyFixtures.grant("bob", GrantScope.Type.FOLDER, "outer", Arrays.asList(GrantAction.CONFIGURE));
        j.buildAndAssertSuccess(deep);
        assertFalse(entryOn(deep, "alice"), "a job two levels below the guarded folder is guarded");
    }

    /**
     * T-02-73 (D-58b (4)): a script saves the guarded job with 101 AuthorizationMatrixProperty
     * elements (the nonInheriting baseline plus 100, the last giving carol Job/Configure); after the revert exactly one authorization
     * property remains and carol has no entry.
     */
    @Test
    public void t_02_73_manyPropertiesEndAsOneMergedProperty() throws Exception {
        baselineNonInheriting();
        grantBob();
        StringBuilder props = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            props.append(propertyXml(i == 99 ? "carol" : "admin"));
        }
        String xml = current().getConfigFile().asString();
        String close = "</hudson.security.AuthorizationMatrixProperty>";
        int at = xml.indexOf(close) + close.length();
        assertTrue(at > close.length(), "fixture: the baseline property must be in config.xml: " + xml);
        String many = xml.substring(0, at) + props + xml.substring(at); // the baseline plus 100 more: 101 in all
        saveAsScript(many);

        long count = current().getAllProperties().stream().filter(p -> p instanceof AuthorizationMatrixProperty).count();
        assertEquals(1, count, "after the revert exactly one merged authorization property must remain");
        assertNoEntryFor("carol");
    }

    /**
     * T-02-65 (D-58a, items no grant touched): a second job that no grant ever covered or changed
     * gets a script giving bob Job/Configure (set by the administrator); the build's entry is kept.
     */
    @Test
    public void t_02_65_itemNoGrantTouchedKeepsAJenkinsfileWidening() throws Exception {
        grantBob();
        WorkflowJob free = j.jenkins.createProject(WorkflowJob.class, "free");
        free.setDefinition(new CpsFlowDefinition(entryFor("user", "bob"), true));
        int violations = violations().size();
        j.buildAndAssertSuccess(free);

        AuthorizationMatrixProperty amp = free.getProperty(AuthorizationMatrixProperty.class);
        assertTrue(amp != null && amp.getGrantedPermissionEntries().values().stream()
                .anyMatch(s -> s.stream().anyMatch(pe -> "bob".equals(pe.getSid()))),
                "on an item no grant touched a Jenkinsfile widening is kept");
        assertEquals(violations, violations().size(), "no GRANT_VIOLATION on an item no grant touched");
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

    /**
     * T-02-66 (D-58a (5)): bob plants the script inside his window and the window ends. c1 (native
     * Item/Configure, not an administrator) saves the job through HTTP adding carol: 403, reverted.
     * That save widened, so it is not the review: the build then still has bob's entry reverted.
     */
    @Test
    public void t_02_66_revertedNativeWideningDoesNotClearTheState() throws Exception {
        grantBob();
        editScriptAsBob(entryFor("user", "bob"));
        afterWindow();
        String xml = current().getConfigFile().asString();
        String widened = xml.replace("<properties/>", "<properties>" + propertyXml("carol") + "</properties>");
        assertFalse(widened.equals(xml), "fixture: the job config must have an empty <properties/>: " + xml);
        assertEquals(403, post("c1", widened), "c1's widening on the guarded job must answer 403");
        assertNoEntryFor("carol");

        j.buildAndAssertSuccess(current());
        assertNoEntryFor("bob");
    }

    /**
     * T-02-67 (D-58a (1)(5)): bob holds a FOLDER CONFIGURE grant on {@code team}; carol (native
     * Job/Create, not an administrator) creates the Pipeline job {@code team/p2} whose script gives
     * her Job/Configure. The window ends; the item stays guarded, so the build's entry is reverted.
     */
    @Test
    public void t_02_67_itemCreatedInAGuardedFolderIsGuarded() throws Exception {
        BatchControlMatrixAuthorizationStrategy strategy = (BatchControlMatrixAuthorizationStrategy) j.jenkins.getAuthorizationStrategy();
        strategy.add(Item.CREATE, PermissionEntry.user("carol"));
        j.jenkins.createProject(com.cloudbees.hudson.plugins.folder.Folder.class, "team");
        StrategyFixtures.grant("bob", GrantScope.Type.FOLDER, "team", Arrays.asList(GrantAction.CONFIGURE));

        String script = entryFor("user", "carol").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("'", "&apos;");
        String payload = "<?xml version='1.1' encoding='UTF-8'?><flow-definition><definition class=\""
                + "org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition\"><script>" + script
                + "</script><sandbox>true</sandbox></definition></flow-definition>";
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("carol");
        WebRequest req = new WebRequest(new java.net.URL(wc.createCrumbedUrl("job/team/createItem").toExternalForm()
                + "&name=p2"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        req.setRequestBody(payload);
        wc.getPage(req);
        WorkflowJob p2 = j.jenkins.getItemByFullName("team/p2", WorkflowJob.class);
        assertTrue(p2 != null, "fixture: carol's creation must have made team/p2");

        afterWindow();
        j.buildAndAssertSuccess(p2);
        AuthorizationMatrixProperty amp = p2.getProperty(AuthorizationMatrixProperty.class);
        assertTrue(amp == null || amp.getGrantedPermissionEntries().values().stream()
                .noneMatch(s -> s.stream().anyMatch(pe -> "carol".equals(pe.getSid()))),
                "an item created inside a guarded folder by a non-administrator stays guarded after the window");
    }

    // ---------------------------------------------------------------- helpers

    private int postForm(String user, String path, String query) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        java.net.URL url = new java.net.URL(wc.createCrumbedUrl(path).toExternalForm() + (query == null ? "" : "&" + query));
        return wc.getPage(new WebRequest(url, HttpMethod.POST)).getWebResponse().getStatusCode();
    }

    /** Records of the type named {@code type} in the months of T0, of T0 plus 40 days, and of now. */
    private static List<ChangeRecord> records(String type) {
        java.util.Set<java.time.YearMonth> months = new java.util.LinkedHashSet<>(Arrays.asList(
                java.time.YearMonth.from(T0.atZone(ZoneOffset.UTC)),
                java.time.YearMonth.from(T0.plus(Duration.ofDays(40)).atZone(ZoneOffset.UTC)),
                java.time.YearMonth.now()));
        List<ChangeRecord> out = new java.util.ArrayList<>();
        for (java.time.YearMonth month : months) {
            for (ChangeRecord r : io.jenkins.plugins.batchcontrol.store.FileStore.get().listChangeRecords(month)) {
                if (r.getType() != null && type.equals(r.getType().name()) && !out.contains(r)) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    private static void assertReviewedBy(String user, int before) {
        List<ChangeRecord> reviews = records("GUARD_REVIEWED");
        assertEquals(before + 1, reviews.size(), "the review must write one GUARD_REVIEWED record");
        assertEquals(user, reviews.get(reviews.size() - 1).getUser(), "the GUARD_REVIEWED record must name the reviewer");
    }

    private static boolean entryOn(WorkflowJob job, String sid) {
        AuthorizationMatrixProperty amp = job.getProperty(AuthorizationMatrixProperty.class);
        return amp != null && amp.getGrantedPermissionEntries().values().stream()
                .anyMatch(set -> set.stream().anyMatch(pe -> sid.equals(pe.getSid())));
    }

    private static String jsonString(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

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

    private static final String NON_INHERITING =
            "<inheritanceStrategy class=\"org.jenkinsci.plugins.matrixauth.inheritance.NonInheritingStrategy\"/>";
    private static final String INHERITING =
            "<inheritanceStrategy class=\"org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy\"/>";

    private static String propertyXml(String user) {
        return propertyXml("Inherit", user);
    }

    /** An authorization property giving {@code user} Job/Configure, inheriting ("Inherit") or not ("NonInherit"). */
    private static String propertyXml(String inheritance, String user) {
        return "<hudson.security.AuthorizationMatrixProperty>"
                + ("NonInherit".equals(inheritance) ? NON_INHERITING : INHERITING)
                + "<permission>USER:hudson.model.Item.Configure:" + user + "</permission>"
                + "</hudson.security.AuthorizationMatrixProperty>";
    }

    /** Before any grant: the administrator's nonInheriting property naming only admin (D-58a baseline). */
    private void baselineNonInheriting() throws Exception {
        String xml = current().getConfigFile().asString();
        // admin configures; bob (the requester), a1 (the approver) and c1 keep Job/Read, so the grant fixture still sees the job
        String property = propertyXml("NonInherit", "admin").replace("</hudson.security.AuthorizationMatrixProperty>",
                "<permission>USER:hudson.model.Item.Read:bob</permission>"
                        + "<permission>USER:hudson.model.Item.Read:a1</permission>"
                        + "<permission>USER:hudson.model.Item.Read:c1</permission>"
                        + "</hudson.security.AuthorizationMatrixProperty>");
        String with = xml.replace("<properties/>", "<properties>" + property + "</properties>");
        assertFalse(with.equals(xml), "fixture: the job config must have an empty <properties/>: " + xml);
        current().updateByXml((javax.xml.transform.Source) new javax.xml.transform.stream.StreamSource(
                new java.io.StringReader(with)));
        AuthorizationMatrixProperty amp = current().getProperty(AuthorizationMatrixProperty.class);
        assertTrue(amp != null && amp.getInheritanceStrategy() instanceof NonInheritingStrategy,
                "fixture: the baseline property must be nonInheriting");
    }

    /** A save of the job's config.xml by a script (SYSTEM, not an HTTP request). */
    private void saveAsScript(String xml) throws Exception {
        try (hudson.security.ACLContext ignored = hudson.security.ACL.as2(hudson.security.ACL.SYSTEM2)) {
            current().updateByXml((javax.xml.transform.Source) new javax.xml.transform.stream.StreamSource(
                    new java.io.StringReader(xml)));
        } catch (RuntimeException | java.io.IOException refused) {
            // the guard may refuse the save outright; the assertions read the result either way
        }
    }

    /** Sets the job description in a config.xml text, whatever form the element has. */
    private static String withDescription(String xml, String text) {
        if (xml.contains("<description/>")) {
            return xml.replace("<description/>", "<description>" + text + "</description>");
        }
        if (xml.matches("(?s).*<description>.*?</description>.*")) {
            return xml.replaceFirst("(?s)<description>.*?</description>", "<description>" + text + "</description>");
        }
        return xml.replaceFirst("(<flow-definition[^>]*>)", "$1<description>" + text + "</description>");
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
