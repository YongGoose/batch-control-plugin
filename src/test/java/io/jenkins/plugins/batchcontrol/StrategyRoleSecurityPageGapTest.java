package io.jenkins.plugins.batchcontrol;

import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.htmlunit.Page;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlOption;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlRadioButtonInput;
import org.htmlunit.html.HtmlSelect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Coverage lane 2, scenario L2-15 (matrix rows T-GAP-260 and T-GAP-261, note 277): the Batch Control
 * role-based strategy chosen on the global security page.
 *
 * <p>Basis: SPEC item 8, the Implementation line ("Batch Control variants of the matrix-auth and
 * role-strategy strategies ..., chosen on the global security page or installed by the monitor's migration
 * button"); SPEC item 2 ("With the Batch Control role-strategy strategy installed, Manage Roles, item and
 * agent roles, pattern-based Create and the role naming strategy work"; the strategy round-trips through
 * config.xml); LIMITATIONS 8 (display name "Batch Control: Role-Based Strategy") and 9 (saves keep the
 * variant in place).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-35a/D-35f/D-35g, docs/LIMITATIONS.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class StrategyRoleSecurityPageGapTest {

    static final String DISPLAY_NAME = "Batch Control: Role-Based Strategy";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        // Jenkins' own user database: it round-trips through the security page (the test harness's dummy realm does not)
        hudson.security.HudsonPrivateSecurityRealm realm = new hudson.security.HudsonPrivateSecurityRealm(false, false, null);
        for (String id : new String[] {"admin", "alice", "bob", "carol", "a1", "m1"}) {
            realm.createAccount(id, id);
        }
        j.jenkins.setSecurityRealm(realm);
        j.jenkins.setAuthorizationStrategy(new FullControlOnceLoggedInAuthorizationStrategy());
        StrategyFixtures.changeControlOn();
    }

    /**
     * T-GAP-260 (L2-15, SPEC 8 Implementation line, SPEC 2): the administrator selects "Batch Control:
     * Role-Based Strategy" on Configure Global Security and saves: the Batch Control role strategy is
     * installed. With roles configured, saving the security page again keeps the Batch Control class and
     * every role and assignment.
     */
    @Test
    public void t_gap_260_roleStrategyChosenOnTheSecurityPageIsInstalledAndKept() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        HtmlPage page = wc.goTo("manage/configureSecurity/");
        HtmlForm form = page.getFormByName("config");
        choose(page, DISPLAY_NAME);
        Page saved = j.submit(form);
        assertTrue(saved.getWebResponse().getStatusCode() < 400, "the security page save must succeed, got "
                + saved.getWebResponse().getStatusCode());
        assertSame(BatchControlRoleBasedAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "the Batch Control role strategy chosen on the security page must be installed");

        j.jenkins.setAuthorizationStrategy(new BatchControlRoleBasedAuthorizationStrategy(StrategyFixtures.roles(), Collections.emptySet()));
        Set<String> roles = StrategyFixtures.describeRoles((RoleBasedAuthorizationStrategy) j.jenkins.getAuthorizationStrategy());
        HtmlPage again = j.createWebClient().login("admin").goTo("manage/configureSecurity/");
        Page resaved = j.submit(again.getFormByName("config"));
        assertTrue(resaved.getWebResponse().getStatusCode() < 400, "the second save must succeed, got "
                + resaved.getWebResponse().getStatusCode());
        assertSame(BatchControlRoleBasedAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "saving the security page again keeps the Batch Control role strategy");
        assertEquals(roles, StrategyFixtures.describeRoles((RoleBasedAuthorizationStrategy) j.jenkins.getAuthorizationStrategy()),
                "saving the security page again keeps every role and assignment");
    }

    /**
     * T-GAP-261 (L2-15 (u), SPEC 2 "Manage Roles ... work"): with the Batch Control role strategy installed,
     * the role-strategy root page (Assign Roles in role-strategy 927, TEST-MATRIX T-02-99) and Manage Roles
     * answer 200 to the administrator and show the installed roles and assignments.
     */
    @Test
    public void t_gap_261_rolePagesRenderWithTheBatchControlRoleStrategy() throws Exception {
        j.jenkins.setAuthorizationStrategy(new BatchControlRoleBasedAuthorizationStrategy(StrategyFixtures.roles(), Collections.emptySet()));
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        wc.login("admin");
        Page root = wc.goTo("manage/role-strategy/");
        assertEquals(200, root.getWebResponse().getStatusCode(), "the role-strategy page (Assign Roles) renders");
        Page manage = wc.goTo("manage/role-strategy/manage-roles");
        assertEquals(200, manage.getWebResponse().getStatusCode(), "Manage Roles renders");
        assertTrue(manage.getWebResponse().getContentAsString().contains("team-.*"), "Manage Roles shows the installed item role");
        assertTrue(root.getWebResponse().getContentAsString().contains("bob"), "Assign Roles (the role-strategy index page, 927)"
                + " shows the assigned users");
    }

    /** Selects the authorization strategy named {@code displayName} (a drop-down or a radio block). */
    private static void choose(HtmlPage page, String displayName) throws Exception {
        for (HtmlSelect select : page.<HtmlSelect>getByXPath("//select")) {
            for (HtmlOption option : select.getOptions()) {
                if (displayName.equals(option.getText().trim())) {
                    select.setSelectedAttribute(option, true);
                    return;
                }
            }
        }
        List<HtmlRadioButtonInput> radios = page.getByXPath("//input[@type='radio']");
        for (HtmlRadioButtonInput radio : radios) {
            String label = radio.getParentNode().asNormalizedText();
            if (label.contains(displayName)) {
                radio.setChecked(true);
                return;
            }
        }
        fail("the security page must offer '" + displayName + "'");
    }
}
