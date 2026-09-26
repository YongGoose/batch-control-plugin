package io.jenkins.plugins.batchcontrol;

import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 2 (permission system). Matrix rows T-02-01, T-02-02, T-02-05.
 * T-02-03 and T-02-04 (admin self-approval) need the approval service and are deferred to slice S2.
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class PermissionsTest {

    private JenkinsRule j;

    @BeforeEach
    void setUpJenkins(JenkinsRule rule) {
        this.j = rule;
    }

    /** T-02-01: a user without BatchControl/Manage cannot save the global configuration (403). */
    @Test
    public void t_02_01_userWithoutManageCannotSaveGlobalConfig() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ,
                        BatchControlPermissions.REQUEST,
                        BatchControlPermissions.APPROVE,
                        BatchControlPermissions.REQUEST_GRANT,
                        BatchControlPermissions.VIEW_HISTORY).everywhere().to("u1"));

        JenkinsRule.WebClient wc = j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false)
                .login("u1");
        Page page = wc.getPage(new WebRequest(wc.createCrumbedUrl("configSubmit"), HttpMethod.POST));
        assertEquals(403, page.getWebResponse().getStatusCode(), "a user holding every BatchControl permission except Manage must get 403");
        assertFalse(BatchControlGlobalConfiguration.get().isRunControlEnabled(), "the rejected POST must not have changed any switch");
    }

    /** T-02-02: the five permissions appear as a "Batch Control" group in the Matrix Authorization screen. */
    @Test
    public void t_02_02_matrixScreenShowsBatchControlGroupWithFivePermissions() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        GlobalMatrixAuthorizationStrategy strategy = new GlobalMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        j.jenkins.setAuthorizationStrategy(strategy);

        PermissionGroup group = BatchControlPermissions.GROUP;
        assertEquals("Batch Control", group.title.toString());

        List<Permission> permissions = group.getPermissions();
        Permission[] expected = {
                BatchControlPermissions.REQUEST,
                BatchControlPermissions.APPROVE,
                BatchControlPermissions.REQUEST_GRANT,
                BatchControlPermissions.VIEW_HISTORY,
                BatchControlPermissions.MANAGE,
        };
        String[] expectedNames = {"Request", "Approve", "RequestGrant", "ViewHistory", "Manage"};
        for (int i = 0; i < expected.length; i++) {
            assertTrue(permissions.contains(expected[i]), expectedNames[i] + " must belong to the Batch Control group");
            assertEquals(expectedNames[i], expected[i].name);
            assertTrue(expected[i].getEnabled(), expectedNames[i] + " must be enabled so authorization strategies expose it");
        }

        HtmlPage page = j.createWebClient().login("admin").goTo("configureSecurity");
        // matrix-auth 3.3 renders permission group titles inside collapsed card bodies
        // (<legend class="mas-card__group-title">) and a hidden filter dropdown, so the title is
        // present in the DOM but invisible to HtmlUnit's normalized (visible-only) text. Assert on
        // the raw DOM instead; visible-text verification is covered by the Phase 5 e2e visual check.
        assertTrue(page.getWebResponse().getContentAsString().contains("Batch Control"), "the security configuration screen must expose the Batch Control permission group in its DOM");
    }

    /** T-02-05: a Manage holder can POST the global config form; values are saved (form round-trip keeps them). */
    @Test
    public void t_02_05_manageUserCanSaveGlobalConfig() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("m1")); // BatchControl/Manage is implied by Administer

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.setAllowAdminSelfApproval(false);
        cfg.setPendingTimeoutHours(48);
        cfg.save();

        JenkinsRule.WebClient wc = j.createWebClient().login("m1");
        HtmlForm form = wc.goTo("configure").getFormByName("config");
        j.submit(form); // must succeed (200) for a Manage holder

        BatchControlGlobalConfiguration reloaded = BatchControlGlobalConfiguration.get();
        assertEquals(Arrays.asList("a1", "a2"), reloaded.getApprovers(), "approver list must survive the config form round-trip");
        assertFalse(reloaded.isAllowAdminSelfApproval());
        assertEquals(48, reloaded.getPendingTimeoutHours());
    }
}
