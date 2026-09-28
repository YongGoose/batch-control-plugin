package io.jenkins.plugins.batchcontrol.poc.auth;

import static io.jenkins.plugins.batchcontrol.poc.auth.Poc5Support.has;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleStrategyConfig;
import hudson.ExtensionList;
import hudson.model.Failure;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.AuthorizationMatrixProperty;
import hudson.security.AuthorizationStrategy;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import hudson.slaves.DumbSlave;
import java.util.HashMap;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.AuthorizationMatrixNodeProperty;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.jenkinsci.plugins.rolestrategy.RoleBasedProjectNamingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * PoC-5 question 0: what breaks behind the current wrapper shape. Each "control" line runs the
 * same assertion against the plain strategy so the difference is caused by the wrapper alone.
 */
@WithJenkins
class Poc5BaselineWrapperTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule j) {
        this.j = j;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
    }

    private static boolean jobPropApplicable() {
        return ExtensionList.lookupSingleton(AuthorizationMatrixProperty.DescriptorImpl.class)
                .isApplicable(FreeStyleProject.class);
    }

    private static boolean folderPropApplicable() {
        return ExtensionList.lookupSingleton(
                com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.DescriptorImpl.class)
                .isApplicable(Folder.class);
    }

    private static boolean nodePropApplicable() {
        return ExtensionList.lookupSingleton(AuthorizationMatrixNodeProperty.DescriptorImpl.class)
                .isApplicable(DumbSlave.class);
    }

    private boolean jobConfigPageOffersProjectSecurity(FreeStyleProject p) throws Exception {
        HtmlPage page = j.createWebClient().login("admin").getPage(p, "configure");
        return page.getWebResponse().getContentAsString().contains("useProjectSecurity");
    }

    @Test
    void wrapper_matrix_folderJobAgentPropertiesNotConfigurable() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("job");
        // control: plain ProjectMatrixAuthorizationStrategy
        j.jenkins.setAuthorizationStrategy(Poc5Support.matrix(new ProjectMatrixAuthorizationStrategy()));
        assertTrue(jobPropApplicable());
        assertTrue(folderPropApplicable());
        assertTrue(nodePropApplicable());
        assertTrue(jobConfigPageOffersProjectSecurity(p));

        j.jenkins.setAuthorizationStrategy(
                new PocWrapperStrategy(Poc5Support.matrix(new ProjectMatrixAuthorizationStrategy())));
        assertFalse(jobPropApplicable(), "job property hidden behind the wrapper");
        assertFalse(folderPropApplicable(), "folder property hidden behind the wrapper");
        assertFalse(nodePropApplicable(), "agent property hidden behind the wrapper");
        assertFalse(jobConfigPageOffersProjectSecurity(p), "job configure page has no project-based security section");
    }

    @Test
    void wrapper_matrix_existingJobPropertyEffectiveButDroppedByUiSave() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("job");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.READ, PermissionEntry.user("alice"));
        p.addProperty(amp);
        j.jenkins.setAuthorizationStrategy(
                new PocWrapperStrategy(Poc5Support.matrix(new ProjectMatrixAuthorizationStrategy())));

        assertTrue(has(p, "alice", Item.READ), "an existing property still takes effect (delegate ACL)");

        // An administrator saves the job configuration through the UI for an unrelated reason.
        j.submit(j.createWebClient().login("admin").getPage(p, "configure").getFormByName("config"));

        assertNull(p.getProperty(AuthorizationMatrixProperty.class),
                "the non-applicable property is silently dropped on UI save");
        assertFalse(has(p, "alice", Item.READ), "alice lost access she was given per job");
    }

    @Test
    void wrapper_matrix_granteeCanSelfGrantThroughConfigXml() throws Exception {
        io.jenkins.plugins.batchcontrol.poc.PocGrantStore.setClock(new Poc5Support.SettableClock(Poc5Support.T0));
        try {
            j.jenkins.setAuthorizationStrategy(
                    new PocWrapperStrategy(Poc5Support.matrix(new ProjectMatrixAuthorizationStrategy())));
            FreeStyleProject p = j.createFreeStyleProject("job");
            io.jenkins.plugins.batchcontrol.poc.PocGrantStore.addGrant("bob", "job", false, java.util.Set.of(Item.CONFIGURE),
                    Poc5Support.T0.plus(java.time.Duration.ofHours(1)));
            String xml = p.getConfigFile().asString().replace("<properties/>",
                    "<properties><hudson.security.AuthorizationMatrixProperty>"
                            + "<inheritanceStrategy class=\"org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy\"/>"
                            + "<permission>USER:hudson.model.Item.Configure:bob</permission>"
                            + "</hudson.security.AuthorizationMatrixProperty></properties>");
            org.htmlunit.WebRequest req = new org.htmlunit.WebRequest(new java.net.URL(j.getURL(), "job/job/config.xml"),
                    org.htmlunit.HttpMethod.POST);
            req.setAdditionalHeader("Content-Type", "application/xml");
            req.setRequestBody(xml);
            j.jenkins.setCrumbIssuer(null);
            j.createWebClient().login("bob").getPage(req);
            io.jenkins.plugins.batchcontrol.poc.PocGrantStore.setClock(
                    new Poc5Support.SettableClock(Poc5Support.T0.plus(java.time.Duration.ofHours(2))));
            assertTrue(has(p, "bob", Item.CONFIGURE),
                    "also possible behind the wrapper: config.xml ignores descriptor applicability, the delegate honours it");
        } finally {
            io.jenkins.plugins.batchcontrol.poc.PocGrantStore.reset();
        }
    }

    @Test
    void wrapper_role_itemRolesEffectiveButManagementAndApisGone() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("team-a");
        j.jenkins.setAuthorizationStrategy(new RoleBasedAuthorizationStrategy(Poc5Support.roles()));
        RoleStrategyConfig cfg = ExtensionList.lookupSingleton(RoleStrategyConfig.class);
        // control
        assertNotNull(RoleBasedAuthorizationStrategy.getInstance());
        assertNotNull(cfg.getIconFileName());
        assertNotNull(cfg.getStrategy());

        j.jenkins.setAuthorizationStrategy(new PocWrapperStrategy(new RoleBasedAuthorizationStrategy(Poc5Support.roles())));
        assertTrue(has(p, "bob", Item.CONFIGURE), "item roles still take effect through the delegate ACL");
        DumbSlave agent = j.createSlave("agent-1", null, null);
        assertTrue(agent.toComputer().getACL().hasPermission2(
                hudson.model.User.getById("bob", true).impersonate2(), hudson.model.Computer.CONFIGURE),
                "agent roles still take effect through the delegate ACL");
        assertNull(RoleBasedAuthorizationStrategy.getInstance(), "getInstance() (REST API, pipeline steps) sees no role strategy");
        assertNull(cfg.getIconFileName(), "Manage and Assign Roles link is hidden");
        assertNull(cfg.getStrategy(), "Manage Roles page has no strategy to edit");
    }

    @Test
    void wrapper_role_patternBasedCreateAndNamingStrategyBroken() throws Exception {
        j.jenkins.setProjectNamingStrategy(new RoleBasedProjectNamingStrategy(false));
        // control: bob can create at the root only through the item role "team-.*"
        j.jenkins.setAuthorizationStrategy(new RoleBasedAuthorizationStrategy(Poc5Support.roles()));
        assertTrue(has(j.jenkins, "bob", Item.CREATE));
        Poc5Support.as("bob", () -> {
            j.jenkins.getProjectNamingStrategy().checkName("", "team-new");
            assertThrows(Failure.class, () -> j.jenkins.getProjectNamingStrategy().checkName("", "other"));
            return null;
        });

        AuthorizationStrategy wrapped = new PocWrapperStrategy(new RoleBasedAuthorizationStrategy(Poc5Support.roles()));
        j.jenkins.setAuthorizationStrategy(wrapped);
        assertFalse(has(j.jenkins, "bob", Item.CREATE), "item-role Create no longer reaches the root ACL");
        Poc5Support.as("bob", () -> {
            j.jenkins.getProjectNamingStrategy().checkName("", "other"); // no Failure: naming rule not enforced
            return null;
        });
        assertTrue(Jenkins.get().getAuthorizationStrategy() instanceof PocWrapperStrategy);
    }
}
