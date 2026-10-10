package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.ExtensionList;
import hudson.model.Computer;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.AuthorizationMatrixProperty;
import hudson.security.AuthorizationStrategy;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.Permission;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import hudson.slaves.DumbSlave;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Set;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.matrixauth.AuthorizationMatrixNodeProperty;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.WINDOW_MINUTES;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.has;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 2, D-35a (#30): with the Batch Control matrix-auth strategy installed, folder, job
 * and agent authorization properties are configurable and effective, an existing job property
 * survives a save of the job page, and a grant is layered over the per-item ACL and ends at its
 * expiry. Matrix rows T-02-10 (PoC-5 row 1), T-02-11 (PoC-5 row 2 plus the matrix half of row 6)
 * and T-02-12 (PoC-5 row 9, option A).
 *
 * <p>Time never passes for real: BatchClock is fixed and moved (matrix note 1).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-35a and docs/POC-RESULTS.md PoC-5 only
 * (no src/main knowledge).
 */
@WithJenkins
public class MatrixStrategyTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    private BatchControlMatrixAuthorizationStrategy install() {
        BatchControlMatrixAuthorizationStrategy s = StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy());
        j.jenkins.setAuthorizationStrategy(s);
        assertSame(BatchControlMatrixAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "fixture: the Batch Control matrix strategy must be the installed strategy");
        assertTrue(j.jenkins.getAuthorizationStrategy() instanceof ProjectMatrixAuthorizationStrategy,
                "D-35a: the Batch Control matrix strategy is a ProjectMatrixAuthorizationStrategy");
        return s;
    }

    /**
     * T-02-10 (PoC-5 row 1): matrix-auth's type checks pass. The job, folder and agent
     * authorization property descriptors are applicable, the job configure page renders the
     * project-security section, and a folder property and an agent property are effective.
     * Guard: the same descriptors are NOT applicable under a plain GlobalMatrixAuthorizationStrategy,
     * so the assertion measures the strategy's type, not a constant.
     */
    @Test
    public void t_02_10_matrixPropertiesConfigurableAndEffective() throws Exception {
        // guard first: a non-project matrix hides the per-item properties
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new GlobalMatrixAuthorizationStrategy()));
        assertFalse(ExtensionList.lookupSingleton(AuthorizationMatrixProperty.DescriptorImpl.class).isApplicable(FreeStyleProject.class),
                "guard: under a plain global matrix the job property must not be applicable, or this row measures nothing");

        install();
        Folder f = j.jenkins.createProject(Folder.class, "f");
        FreeStyleProject p = f.createProject(FreeStyleProject.class, "job");

        assertTrue(ExtensionList.lookupSingleton(AuthorizationMatrixProperty.DescriptorImpl.class).isApplicable(FreeStyleProject.class),
                "the job authorization property must be configurable under the Batch Control matrix strategy");
        assertTrue(ExtensionList.lookupSingleton(
                        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.DescriptorImpl.class)
                .isApplicable(Folder.class), "the folder authorization property must be configurable");
        assertTrue(ExtensionList.lookupSingleton(AuthorizationMatrixNodeProperty.DescriptorImpl.class).isApplicable(DumbSlave.class),
                "the agent authorization property must be configurable");
        assertTrue(j.createWebClient().login("admin").getPage(p, "configure").getWebResponse()
                .getContentAsString().contains("useProjectSecurity"),
                "the job configure page must render matrix-auth's project-based security section");

        // folder property: dave (no global entry) may read items under f only
        assertFalse(has(p, "dave", Item.READ), "premise: dave holds nothing before the folder property");
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty fp =
                new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(
                        new HashMap<Permission, Set<String>>());
        fp.add(Item.READ, PermissionEntry.user("dave"));
        f.addProperty(fp);
        assertTrue(has(p, "dave", Item.READ), "the folder property must be inherited by the job");

        // agent property
        DumbSlave agent = j.createSlave("agent-1", null, null);
        assertFalse(agent.toComputer().getACL().hasPermission2(
                hudson.model.User.getById("alice", true).impersonate2(), Computer.CONFIGURE), "premise: alice cannot configure the agent");
        AuthorizationMatrixNodeProperty np = new AuthorizationMatrixNodeProperty(new HashMap<>());
        np.add(Computer.CONFIGURE, PermissionEntry.user("alice"));
        agent.getNodeProperties().add(np);
        assertTrue(agent.toComputer().getACL().hasPermission2(
                hudson.model.User.getById("alice", true).impersonate2(), Computer.CONFIGURE),
                "the agent authorization property must be effective");
    }

    /**
     * T-02-11 (PoC-5 row 2, and the matrix half of row 6): an existing job authorization
     * property is effective and kept by a UI save of the job page (under the withdrawn wrapper
     * the save silently dropped it); the security page's save path builds the Batch Control
     * subclass again, not the plain parent.
     */
    @Test
    @Tag("core")
    public void t_02_11_jobPropertySurvivesUiSaveAndSecurityFormKeepsSubclass() throws Exception {
        install();
        FreeStyleProject p = j.createFreeStyleProject("job");
        FreeStyleProject other = j.createFreeStyleProject("other");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        p.addProperty(amp);
        assertTrue(has(p, "alice", Item.CONFIGURE), "premise: the job property is effective");
        assertFalse(has(other, "alice", Item.CONFIGURE), "guard: the property is per job, not global");

        JenkinsRule.WebClient wc = j.createWebClient().login("admin");
        j.submit(wc.getPage(p, "configure").getFormByName("config"));

        assertNotNull(p.getProperty(AuthorizationMatrixProperty.class), "a UI save of the job must keep its authorization property");
        assertTrue(has(p, "alice", Item.CONFIGURE), "the kept property must still be effective after the UI save");
        assertFalse(has(other, "alice", Item.CONFIGURE));

        // security page: the strategy's own descriptor is offered, and its save path keeps the subclass
        AuthorizationStrategy installed = j.jenkins.getAuthorizationStrategy();
        assertTrue(AuthorizationStrategy.all().contains(installed.getDescriptor()),
                "the Batch Control matrix strategy must be listed on the security page");
        assertTrue(wc.goTo("manage/configureSecurity").getWebResponse().getContentAsString().contains("hudson.model.Hudson.Administer"),
                "the security page must render the matrix editor for the Batch Control strategy");
        JSONObject form = new JSONObject().element("data", new JSONObject()
                .element("USER:admin", new JSONObject().element("hudson.model.Hudson.Administer", true)));
        AuthorizationStrategy saved = installed.getDescriptor().newInstance((org.kohsuke.stapler.StaplerRequest2) null, form);
        assertSame(BatchControlMatrixAuthorizationStrategy.class, saved.getClass(),
                "a save of the security page must build the Batch Control subclass, not the plain parent");
    }

    /**
     * T-02-12 (PoC-5 row 9, option A; SPEC 8 expiry), rewritten for D-71: a grant is layered over
     * the per-item ACL. bob's CONFIGURE window on the job {@code f/job}, which has its own
     * authorization property, reaches it; the job's own entry stays effective; the grant is
     * refused from the first check at expiry. Guards: the window reaches neither a job outside
     * the folder nor the folder {@code f} itself. The former fixture reached {@code f/job}
     * through a FOLDER window on {@code f}; D-71 withdraws that reach, so carol's window on
     * {@code f} now confers Configure on {@code f} only and nothing on {@code f/job} (note 260).
     */
    @Test
    @Tag("core")
    public void t_02_12_grantLayeredOverPerItemAclAndExpires() throws Exception {
        install();
        StrategyFixtures.changeControlOn();
        Folder f = j.jenkins.createProject(Folder.class, "f");
        FreeStyleProject p = f.createProject(FreeStyleProject.class, "job");
        FreeStyleProject outside = j.createFreeStyleProject("outside");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        p.addProperty(amp);

        assertFalse(has(p, "bob", Item.CONFIGURE), "premise: bob has no Configure before the grant");
        StrategyFixtures.grant("bob", "f/job", Arrays.asList(GrantAction.CONFIGURE));

        assertTrue(has(p, "bob", Item.CONFIGURE), "a window on the job must reach a job that has its own property");
        assertTrue(has(p, "alice", Item.CONFIGURE), "the job's own entry must stay effective under the grant layer");
        assertFalse(has(outside, "bob", Item.CONFIGURE), "guard: the grant must not reach another job");
        assertFalse(has(f, "bob", Item.CONFIGURE), "guard: a window on a job confers nothing on its folder");

        assertFalse(has(f, "carol", Item.CONFIGURE), "premise: carol has no Configure on f before her window");
        StrategyFixtures.grant("carol", "f", Arrays.asList(GrantAction.CONFIGURE));
        assertTrue(has(f, "carol", Item.CONFIGURE), "a window on the folder confers Configure on the folder itself");
        assertFalse(has(p, "carol", Item.CONFIGURE), "D-71: a window on the folder must not reach the job inside it");

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(WINDOW_MINUTES + 1)), ZoneOffset.UTC));
        assertFalse(has(p, "bob", Item.CONFIGURE), "past the expiry the grant must be refused from the first check (no timer)");
        assertTrue(has(p, "alice", Item.CONFIGURE), "the job's own entry is unaffected by the expiry");
        assertFalse(has(f, "carol", Item.CONFIGURE), "past the expiry carol's window is refused too");
    }
}
