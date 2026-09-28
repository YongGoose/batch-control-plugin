package io.jenkins.plugins.batchcontrol;

import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleStrategyConfig;
import hudson.ExtensionList;
import hudson.model.AdministrativeMonitor;
import hudson.model.Computer;
import hudson.model.Failure;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.AuthorizationStrategy;
import hudson.slaves.DumbSlave;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.rolestrategy.RoleBasedProjectNamingStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.WINDOW_MINUTES;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.has;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 2, D-35a (#30): with the Batch Control role-strategy strategy installed, Manage
 * Roles, item and agent roles, pattern-based Create and the role naming strategy work; a grant
 * is layered over the role ACL; and the documented limitation (a Manage Roles save reinstalls
 * the plain class) fails safe and is detected by the {@code batch-control-strategy} monitor.
 * Matrix rows T-02-13 (PoC-5 row 3), T-02-14 (row 4), T-02-15 (row 5), T-02-16 (row 9 on
 * role-strategy) and T-02-17 (row 6).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-35a and docs/POC-RESULTS.md PoC-5 only
 * (no src/main knowledge).
 */
@WithJenkins
public class RoleStrategyTest {

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

    private void install() {
        j.jenkins.setAuthorizationStrategy(
                new BatchControlRoleBasedAuthorizationStrategy(StrategyFixtures.roles(), Collections.emptySet()));
        assertSame(BatchControlRoleBasedAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "fixture: the Batch Control role strategy must be the installed strategy");
    }

    /**
     * T-02-13 (PoC-5 row 3): role-strategy's management surface stays on. {@code getInstance()}
     * (REST API, pipeline steps) returns the installed strategy and the Manage Roles link and
     * page are available. Guard: under a non-role strategy {@code getInstance()} is null and
     * the link is hidden, so the row measures the strategy's type.
     */
    @Test
    public void t_02_13_roleManagementAndApisAvailable() throws Exception {
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new hudson.security.GlobalMatrixAuthorizationStrategy()));
        RoleStrategyConfig cfg = ExtensionList.lookupSingleton(RoleStrategyConfig.class);
        assertTrue(RoleBasedAuthorizationStrategy.getInstance() == null,
                "guard: without a role strategy getInstance() must be null, or this row measures nothing");

        install();
        assertSame(j.jenkins.getAuthorizationStrategy(), RoleBasedAuthorizationStrategy.getInstance(),
                "RoleBasedAuthorizationStrategy.getInstance() must return the Batch Control role strategy");
        assertNotNull(cfg.getIconFileName(), "the Manage Roles link must be shown");
        assertNotNull(cfg.getStrategy(), "the Manage Roles page must find the strategy");
        // role-strategy's table.js does not parse in HtmlUnit; the page is asserted on its markup.
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        wc.login("admin");
        org.htmlunit.Page page = wc.goTo("manage/" + cfg.getUrlName() + "/");
        assertTrue(page.getWebResponse().getStatusCode() == 200, "the Manage Roles page must open for the administrator");
        String body = page.getWebResponse().getContentAsString();
        assertTrue(body.contains("manage-roles") && body.contains("assign-roles"),
                "the role-strategy page must offer Manage Roles and Assign Roles, got:\n" + body);
        org.htmlunit.Page manage = wc.goTo("manage/" + cfg.getUrlName() + "/manage-roles");
        assertTrue(manage.getWebResponse().getStatusCode() == 200
                        && manage.getWebResponse().getContentAsString().contains("team-.*"),
                "the Manage Roles page must render the installed roles (item pattern team-.*)");
    }

    /**
     * T-02-14 (PoC-5 row 4): item roles and agent roles are effective. Guards: the item role's
     * pattern does not reach a job outside it, and a user without the role has nothing.
     */
    @Test
    public void t_02_14_itemAndAgentRolesEffective() throws Exception {
        install();
        FreeStyleProject inPattern = j.createFreeStyleProject("team-a");
        FreeStyleProject outOfPattern = j.createFreeStyleProject("other");
        assertTrue(has(inPattern, "bob", Item.CONFIGURE), "the item role must confer Configure on a matching job");
        assertFalse(has(outOfPattern, "bob", Item.CONFIGURE), "guard: the item role must not reach a non-matching job");
        assertFalse(has(inPattern, "carol", Item.CONFIGURE), "guard: carol holds no item role");

        DumbSlave agent = j.createSlave("agent-1", null, null);
        DumbSlave otherAgent = j.createSlave("build-1", null, null);
        assertTrue(agent.toComputer().getACL().hasPermission2(User.getById("bob", true).impersonate2(), Computer.CONFIGURE),
                "the agent role must confer Computer/Configure on a matching agent");
        assertFalse(otherAgent.toComputer().getACL().hasPermission2(User.getById("bob", true).impersonate2(), Computer.CONFIGURE),
                "guard: the agent role must not reach a non-matching agent");
    }

    /**
     * T-02-15 (PoC-5 row 5): pattern-based Item/Create reaches the root (so a user with only an
     * item role can create) and the role naming strategy enforces the pattern. Under the
     * withdrawn wrapper both were lost.
     */
    @Test
    public void t_02_15_patternCreateAndNamingStrategyWork() throws Exception {
        j.jenkins.setProjectNamingStrategy(new RoleBasedProjectNamingStrategy(false));
        install();
        assertTrue(has(j.jenkins, "bob", Item.CREATE), "pattern-based Create must reach the root for bob");
        assertFalse(has(j.jenkins, "carol", Item.CREATE), "guard: carol holds no item role and must not create");
        StrategyFixtures.as("bob", () -> {
            assertThrows(Failure.class, () -> j.jenkins.getProjectNamingStrategy().checkName("", "other2"),
                    "the role naming strategy must refuse a name outside bob's pattern");
            j.jenkins.getProjectNamingStrategy().checkName("", "team-new");
            return null;
        });
    }

    /**
     * T-02-16 (PoC-5 row 9 on role-strategy; SPEC 8 grant and expiry): a CONFIGURE grant is
     * layered over the role ACL on a job no role covers, stays inside its scope, and is refused
     * from the first check past its expiry.
     */
    @Test
    public void t_02_16_grantLayeredOverRoleAclAndExpires() throws Exception {
        install();
        StrategyFixtures.changeControlOn();
        FreeStyleProject q = j.createFreeStyleProject("other");
        FreeStyleProject r = j.createFreeStyleProject("other-2");
        assertFalse(has(q, "carol", Item.CONFIGURE), "premise: carol has no Configure before the grant");

        StrategyFixtures.grant("carol", GrantScope.Type.JOB, "other", Arrays.asList(GrantAction.CONFIGURE));
        assertTrue(has(q, "carol", Item.CONFIGURE), "the grant must be layered over the role ACL");
        assertFalse(has(r, "carol", Item.CONFIGURE), "guard: a JOB-scoped grant must not reach another job");

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(WINDOW_MINUTES + 1)), ZoneOffset.UTC));
        assertFalse(has(q, "carol", Item.CONFIGURE), "past the expiry the grant must be refused from the first check");
        assertTrue(has(j.createFreeStyleProject("team-b"), "bob", Item.CONFIGURE), "roles are unaffected by the expiry");
    }

    /**
     * T-02-17 (PoC-5 row 6, D-35a known limitation): a save on role-strategy's Manage Roles page
     * installs the plain RoleBasedAuthorizationStrategy. It fails safe (an open grant stops
     * conferring) and, with change control on, the {@code batch-control-strategy} monitor
     * activates. Guards: the monitor is quiet while the subclass is installed, and quiet with
     * change control off.
     */
    @Test
    public void t_02_17_manageRolesSaveFailsSafeAndMonitorShows() throws Exception {
        install();
        BatchControlGlobalConfiguration cfg = StrategyFixtures.changeControlOn();
        AdministrativeMonitor monitor = StrategyFixtures.strategyMonitor();
        StrategyFixtures.configureBuildAuthenticator(); // D-35d: isolate the strategy half of the monitor (note 53)
        FreeStyleProject q = j.createFreeStyleProject("other");
        StrategyFixtures.grant("carol", GrantScope.Type.JOB, "other", Arrays.asList(GrantAction.CONFIGURE));
        assertTrue(has(q, "carol", Item.CONFIGURE), "premise: the grant confers under the subclass");
        assertFalse(monitor.isActivated(), "guard: the monitor must be quiet while the Batch Control role strategy is installed");

        // What RoleBasedAuthorizationStrategy.DescriptorImpl#doRolesSubmit does: newInstance + setAuthorizationStrategy.
        JSONObject form = new JSONObject();
        form.put(RoleBasedAuthorizationStrategy.GLOBAL, new JSONObject().element("data", new JSONObject()
                .element("admin", new JSONObject().element("hudson.model.Hudson.Administer", true))));
        AuthorizationStrategy next = RoleBasedAuthorizationStrategy.DESCRIPTOR.newInstance((org.kohsuke.stapler.StaplerRequest2) null, form);
        j.jenkins.setAuthorizationStrategy(next);

        assertSame(RoleBasedAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "premise (documented limitation): the Manage Roles save installs the plain class");
        assertFalse(has(q, "carol", Item.CONFIGURE), "fail-safe: an open grant must stop conferring under the plain class");
        assertTrue(monitor.isActivated(),
                "with change control on and the plain role strategy installed, the batch-control-strategy monitor must show");

        cfg.setChangeControlEnabled(false);
        cfg.save();
        assertFalse(monitor.isActivated(), "guard: with change control off the monitor must stay quiet");
    }
}
