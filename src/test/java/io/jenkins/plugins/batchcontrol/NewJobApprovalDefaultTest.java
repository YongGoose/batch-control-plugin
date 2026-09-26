package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.cli.CLICommandInvoker;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import javaposse.jobdsl.plugin.ExecuteDslScripts;
import javaposse.jobdsl.plugin.GlobalJobDslSecurityConfiguration;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SPEC item 8, D-31: while run control is enabled, every newly created job starts with
 * {@code approvalRequired=true}, independently of who created it and of the path the creation
 * took. Matrix rows T-08-19 (UI "New Item"), T-08-20 (REST {@code createItem} with a
 * config.xml), T-08-21 (CLI {@code create-job}), T-08-22 (copy of an uncontrolled job),
 * T-08-23 (Job DSL seed run), T-08-24 (creator independence: administrator vs. a plain user
 * inside an active grant window, the D-17 case D-31 widens) and T-08-25 (the negative row:
 * with run control off the default is not forced on anything).
 *
 * T-08-25 is what makes the positive rows falsifiable: an implementation that simply stamps
 * {@code approvalRequired=true} on every new job would satisfy T-08-19..24 while breaking the
 * D-12 promise that an installed-but-disabled control changes nothing.
 *
 * Every row asserts the substance twice: the stored job property (what SPEC 8 names) and the
 * behavioural consequence (a human POST {@code /build} is refused with the matrix blocking
 * baseline, or admitted when the control is off). The automation causes that must keep passing
 * on such a job are the subject of {@link NewJobAutomationSafetyTest}.
 *
 * Written from docs/SPEC.md (items 6, 8, D-17, D-31), docs/DECISIONS.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class NewJobApprovalDefaultTest {

    private static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");

    private static final String MINIMAL_FREESTYLE_XML =
            "<?xml version='1.1' encoding='UTF-8'?><project>"
                    + "<description>created for the D-31 default</description>"
                    + "<builders/><publishers/><buildWrappers/></project>";

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private BatchControlGlobalConfiguration cfg;

    @Before
    public void setUp() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());

        GlobalMatrixAuthorizationStrategy delegate = new GlobalMatrixAuthorizationStrategy();
        delegate.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            delegate.add(Jenkins.READ, PermissionEntry.user(userId));
            delegate.add(Item.READ, PermissionEntry.user(userId));
        }
        delegate.add(Item.BUILD, PermissionEntry.user("u1"));
        delegate.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        delegate.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        delegate.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(new BatchControlAuthorizationStrategy(delegate));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @After
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-08-19 (D-31): the administrator creates a job the ordinary way — the "New Item" form
     * POST, which carries a mode and no configuration at all. The job must still start
     * approval-required, and a manual run of it must be refused.
     */
    @Test
    public void t_08_19_uiNewItemByAdminDefaultsToApprovalRequired() throws Exception {
        JenkinsRule.WebClient admin = webClient().login("admin");
        WebRequest create = new WebRequest(admin.createCrumbedUrl("createItem"), HttpMethod.POST);
        create.setRequestParameters(Arrays.asList(
                new NameValuePair("name", "ui-new"),
                new NameValuePair("mode", FreeStyleProject.class.getName())));
        int code = admin.getPage(create).getWebResponse().getStatusCode();
        assertTrue("the administrator must be able to create a job, got HTTP " + code, code < 400);

        FreeStyleProject created = job("ui-new");
        assertApprovalRequiredByDefault(created,
                "an administrator's ordinary New Item creation");
        assertHumanRunBlocked(created);
    }

    /**
     * T-08-20 (D-31): a REST {@code createItem} POST carrying a config.xml that says nothing
     * about approvals. The default must be applied to the resulting job.
     */
    @Test
    public void t_08_20_restConfigXmlCreationDefaultsToApprovalRequired() throws Exception {
        JenkinsRule.WebClient admin = webClient().login("admin");
        assertTrue("the REST creation must succeed",
                createFromXml(admin, "", "rest-new", MINIMAL_FREESTYLE_XML) < 400);

        FreeStyleProject created = job("rest-new");
        assertApprovalRequiredByDefault(created, "a REST createItem with a config.xml body");
        assertHumanRunBlocked(created);
    }

    /**
     * T-08-21 (D-31): the CLI {@code create-job} path (the path seed scripts and automation
     * outside the UI use) gets the same default.
     */
    @Test
    public void t_08_21_cliCreateJobDefaultsToApprovalRequired() throws Exception {
        CLICommandInvoker.Result result = new CLICommandInvoker(j, "create-job")
                .asUser("admin")
                .withStdin(new ByteArrayInputStream(
                        MINIMAL_FREESTYLE_XML.getBytes(StandardCharsets.UTF_8)))
                .invokeWithArgs("cli-new");
        assertEquals("create-job must succeed: " + result.stderr(), 0, result.returnCode());

        FreeStyleProject created = job("cli-new");
        assertApprovalRequiredByDefault(created, "a CLI create-job");
        assertHumanRunBlocked(created);
    }

    /**
     * T-08-22 (D-31): copying an existing job produces a new job, so the default applies to the
     * copy. The source is created while run control is off and is therefore provably
     * uncontrolled, which is what makes this row measure the copy and not an inherited setting.
     */
    @Test
    public void t_08_22_copiedJobDefaultsToApprovalRequired() throws Exception {
        setRunControl(false);
        FreeStyleProject source = j.createFreeStyleProject("copy-source");
        assertNotControlled(source, "test precondition: the source job must be uncontrolled");
        setRunControl(true);

        JenkinsRule.WebClient admin = webClient().login("admin");
        WebRequest copy = new WebRequest(admin.createCrumbedUrl("createItem"), HttpMethod.POST);
        copy.setRequestParameters(Arrays.asList(
                new NameValuePair("name", "copy-new"),
                new NameValuePair("mode", "copy"),
                new NameValuePair("from", "copy-source")));
        int code = admin.getPage(copy).getWebResponse().getStatusCode();
        assertTrue("copying the job must succeed, got HTTP " + code, code < 400);

        FreeStyleProject created = job("copy-new");
        assertApprovalRequiredByDefault(created, "a copy of an uncontrolled job");
        assertHumanRunBlocked(created);
        assertNotControlled(source,
                "the source job must not be changed by copying it");
    }

    /**
     * T-08-23 (D-31): a Job DSL seed run creates the job. This is the automatic-generation case
     * the decision weighed: the generated job does start controlled, and what keeps CI alive is
     * the SPEC 6 cause policy asserted in {@link NewJobAutomationSafetyTest}, not an exemption
     * here. The seed job itself is created while run control is off so that its own build is
     * not part of what this row measures.
     */
    @Test
    public void t_08_23_jobDslGeneratedJobDefaultsToApprovalRequired() throws Exception {
        GlobalConfiguration.all().get(GlobalJobDslSecurityConfiguration.class)
                .setUseScriptSecurity(false);

        setRunControl(false);
        FreeStyleProject seed = j.createFreeStyleProject("dsl-seed");
        ExecuteDslScripts dsl = new ExecuteDslScripts();
        dsl.setScriptText("job('dsl-generated') { description('generated by the seed job') }");
        seed.getBuildersList().add(dsl);
        setRunControl(true);

        j.buildAndAssertSuccess(seed);
        j.waitUntilNoActivity();

        FreeStyleProject generated = job("dsl-generated");
        assertApprovalRequiredByDefault(generated, "a Job DSL seed run");
        assertHumanRunBlocked(generated);
    }

    /**
     * T-08-24 (D-31, widening D-17): the outcome must not depend on who creates the job. An
     * administrator and a plain user working inside an active CREATE grant window both create a
     * job in the same folder, and both jobs must start approval-required — the D-17 case (user
     * inside a grant window) keeps working and the administrator no longer escapes it.
     */
    @Test
    public void t_08_24_creatorDoesNotChangeTheDefault() throws Exception {
        Folder team = j.jenkins.createProject(Folder.class, "team");
        team.createProject(Folder.class, "batch");

        JenkinsRule.WebClient admin = webClient().login("admin");
        assertTrue("the administrator must be able to create inside the folder",
                createFromXml(admin, "job/team/job/batch/", "admin-made", MINIMAL_FREESTYLE_XML) < 400);

        grantTo("u1", new GrantScope(GrantScope.Type.FOLDER, "team/batch"),
                Arrays.asList(GrantAction.CREATE, GrantAction.CONFIGURE), 30);
        JenkinsRule.WebClient u1 = webClient().login("u1");
        assertTrue("u1 must be able to create inside the granted folder",
                createFromXml(u1, "job/team/job/batch/", "user-made", MINIMAL_FREESTYLE_XML) < 400);

        FreeStyleProject byAdmin = job("team/batch/admin-made");
        FreeStyleProject byUser = job("team/batch/user-made");
        assertApprovalRequiredByDefault(byUser,
                "a plain user's creation inside an active grant window (D-17)");
        assertApprovalRequiredByDefault(byAdmin,
                "an administrator's creation outside any grant window (D-31)");
        assertEquals("D-31: the default must not depend on who created the job",
                approvalRequired(byUser), approvalRequired(byAdmin));
        assertHumanRunBlocked(byAdmin);
    }

    /**
     * T-08-25 (D-31 negative, D-12): with run control off no creation path may force the
     * default. Without this row an implementation that always sets {@code approvalRequired=true}
     * passes every positive row while breaking the promise that a disabled control changes
     * nothing.
     */
    @Test
    public void t_08_25_runControlOffDoesNotForceTheDefault() throws Exception {
        setRunControl(false);

        JenkinsRule.WebClient admin = webClient().login("admin");
        WebRequest uiCreate = new WebRequest(admin.createCrumbedUrl("createItem"), HttpMethod.POST);
        uiCreate.setRequestParameters(Arrays.asList(
                new NameValuePair("name", "off-ui"),
                new NameValuePair("mode", FreeStyleProject.class.getName())));
        assertTrue(admin.getPage(uiCreate).getWebResponse().getStatusCode() < 400);
        assertTrue("the REST creation must succeed",
                createFromXml(admin, "", "off-rest", MINIMAL_FREESTYLE_XML) < 400);

        FreeStyleProject viaUi = job("off-ui");
        FreeStyleProject viaRest = job("off-rest");
        assertNotControlled(viaUi,
                "with run control off a new job must not be forced to approvalRequired=true");
        assertNotControlled(viaRest,
                "with run control off a new job must not be forced to approvalRequired=true");

        // and the behavioural half: the jobs simply build
        assertHumanRunAllowed(viaUi);
        assertHumanRunAllowed(viaRest);
    }

    // ---------------------------------------------------------------- helpers

    private JenkinsRule.WebClient webClient() {
        return j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
    }

    private void setRunControl(boolean enabled) throws Exception {
        cfg.setRunControlEnabled(enabled);
        cfg.save();
    }

    private FreeStyleProject job(String fullName) {
        FreeStyleProject found = j.jenkins.getItemByFullName(fullName, FreeStyleProject.class);
        assertNotNull("the job " + fullName + " must have been created", found);
        return found;
    }

    /** POSTs {@code createItem} with an XML body under the given container URL prefix. */
    private int createFromXml(JenkinsRule.WebClient wc, String containerUrl, String name, String xml)
            throws Exception {
        URL url = new URL(wc.createCrumbedUrl(containerUrl + "createItem").toExternalForm()
                + "&name=" + name);
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private static boolean approvalRequired(Job<?, ?> target) {
        BatchControlJobProperty property = target.getProperty(BatchControlJobProperty.class);
        return property != null && property.isApprovalRequired();
    }

    /** SPEC 8 / D-31: the stored default on a job created while run control is on. */
    private void assertApprovalRequiredByDefault(Job<?, ?> target, String path) {
        BatchControlJobProperty property = target.getProperty(BatchControlJobProperty.class);
        assertNotNull("D-31: a job created by " + path + " while run control is on must carry "
                + "the plugin job property", property);
        assertTrue("D-31: approvalRequired must default to true for a job created by " + path,
                property.isApprovalRequired());
    }

    private void assertNotControlled(Job<?, ?> target, String message) {
        assertFalse(message, approvalRequired(target));
    }

    /** The behavioural half of the default: a human pressing Build needs an approved request. */
    private void assertHumanRunBlocked(Job<?, ?> target) throws Exception {
        int nextBuildNumberBefore = target.getNextBuildNumber();
        Page response = postBuild("u1", target);
        assertTrue("a manual run of a newly created, controlled job must be refused, got HTTP "
                        + response.getWebResponse().getStatusCode(),
                response.getWebResponse().getStatusCode() >= 400);

        // matrix common blocking baseline
        assertEquals("the queue must stay empty", 0, j.jenkins.getQueue().getItems().length);
        j.waitUntilNoActivity();
        assertEquals("nextBuildNumber must not move",
                nextBuildNumberBefore, target.getNextBuildNumber());
        assertTrue("no build may have run", target.getBuilds().isEmpty());
    }

    private void assertHumanRunAllowed(Job<?, ?> target) throws Exception {
        Page response = postBuild("u1", target);
        assertTrue("with run control off a manual run must be admitted, got HTTP "
                        + response.getWebResponse().getStatusCode(),
                response.getWebResponse().getStatusCode() < 400);
        j.waitUntilNoActivity();
        assertNotNull("the manual run must have produced build #1", target.getBuildByNumber(1));
    }

    private Page postBuild(String userId, Job<?, ?> target) throws Exception {
        JenkinsRule.WebClient wc = webClient().login(userId);
        return wc.getPage(new WebRequest(
                wc.createCrumbedUrl(target.getUrl() + "build"), HttpMethod.POST));
    }

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private Grant grantTo(String userId, GrantScope scope, List<GrantAction> actions, int minutes) {
        GrantRequest request;
        try (ACLContext ignored = as(userId)) {
            request = GrantRequestService.get().create(scope, actions, minutes,
                    "batch maintenance window", "a1");
        }
        try (ACLContext ignored = as("a1")) {
            return GrantRequestService.get().approve(request.getId(), "ok");
        }
    }
}
