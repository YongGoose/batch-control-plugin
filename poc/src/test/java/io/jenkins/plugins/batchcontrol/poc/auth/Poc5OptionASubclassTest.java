package io.jenkins.plugins.batchcontrol.poc.auth;

import static io.jenkins.plugins.batchcontrol.poc.auth.Poc5Support.T0;
import static io.jenkins.plugins.batchcontrol.poc.auth.Poc5Support.has;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import io.jenkins.plugins.batchcontrol.poc.PocGrantStore;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.yaml.YamlSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Set;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.matrixauth.AuthorizationMatrixNodeProperty;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.jenkinsci.plugins.rolestrategy.RoleBasedProjectNamingStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** PoC-5 option A: Batch Control subclasses of the supported strategies. */
@WithJenkins
class Poc5OptionASubclassTest {

    private JenkinsRule j;
    private Poc5Support.SettableClock clock;

    @BeforeEach
    void setUp(JenkinsRule j) {
        this.j = j;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        clock = new Poc5Support.SettableClock(T0);
        PocGrantStore.setClock(clock);
    }

    @AfterEach
    void tearDown() {
        PocGrantStore.reset();
    }

    private void grant(String user, String scope, boolean folder, hudson.security.Permission... p) {
        PocGrantStore.addGrant(user, scope, folder, Set.of(p), T0.plus(Duration.ofHours(1)));
    }

    // ------------------------------------------------------------------ matrix-auth

    @Test
    void subclass_matrix_typeChecksPassAndPropertiesEffective() throws Exception {
        j.jenkins.setAuthorizationStrategy(Poc5Support.matrix(new PocGrantMatrixStrategy()));
        Folder f = j.jenkins.createProject(Folder.class, "f");
        FreeStyleProject p = f.createProject(FreeStyleProject.class, "job");
        assertTrue(ExtensionList.lookupSingleton(AuthorizationMatrixProperty.DescriptorImpl.class).isApplicable(FreeStyleProject.class));
        assertTrue(ExtensionList.lookupSingleton(
                com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.DescriptorImpl.class)
                .isApplicable(Folder.class));
        assertTrue(ExtensionList.lookupSingleton(AuthorizationMatrixNodeProperty.DescriptorImpl.class).isApplicable(DumbSlave.class));
        assertTrue(j.createWebClient().login("admin").getPage(p, "configure").getWebResponse()
                .getContentAsString().contains("useProjectSecurity"));

        // folder property: alice may read items under f only
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty fp =
                new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(
                        new HashMap<hudson.security.Permission, Set<String>>());
        fp.add(Item.READ, PermissionEntry.user("alice"));
        f.addProperty(fp);
        assertTrue(has(p, "alice", Item.READ), "folder property inherited by the job");
        // agent property
        DumbSlave agent = j.createSlave("agent-1", null, null);
        AuthorizationMatrixNodeProperty np = new AuthorizationMatrixNodeProperty(new HashMap<>());
        np.add(hudson.model.Computer.CONFIGURE, PermissionEntry.user("alice"));
        agent.getNodeProperties().add(np);
        assertTrue(agent.toComputer().getACL().hasPermission2(
                hudson.model.User.getById("alice", true).impersonate2(), hudson.model.Computer.CONFIGURE));
    }

    @Test
    void subclass_matrix_grantLayeredOnPerItemAclAndExpires() throws Exception {
        j.jenkins.setAuthorizationStrategy(Poc5Support.matrix(new PocGrantMatrixStrategy()));
        Folder f = j.jenkins.createProject(Folder.class, "f");
        FreeStyleProject p = f.createProject(FreeStyleProject.class, "job");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        p.addProperty(amp);

        assertFalse(has(p, "bob", Item.CONFIGURE));
        grant("bob", "f", true, Item.CONFIGURE);
        assertTrue(has(p, "bob", Item.CONFIGURE), "folder-scoped grant reaches a job that has its own property");
        assertTrue(has(p, "alice", Item.CONFIGURE), "per-job entry still effective");
        clock.set(T0.plus(Duration.ofHours(1)));
        assertFalse(has(p, "bob", Item.CONFIGURE), "denied from the first check at expiry");
    }

    @Test
    void subclass_matrix_uiSavesKeepPropertyAndSubclass() throws Exception {
        j.jenkins.setAuthorizationStrategy(Poc5Support.matrix(new PocGrantMatrixStrategy()));
        FreeStyleProject p = j.createFreeStyleProject("job");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.READ, PermissionEntry.user("alice"));
        p.addProperty(amp);
        JenkinsRule.WebClient wc = j.createWebClient().login("admin");

        j.submit(wc.getPage(p, "configure").getFormByName("config"));
        assertNotNull(p.getProperty(AuthorizationMatrixProperty.class), "job UI save keeps the property");
        assertTrue(has(p, "alice", Item.READ));

        // Configure Global Security renders the matrix editor for the subclass ...
        assertTrue(wc.goTo("manage/configureSecurity").getWebResponse().getContentAsString().contains("hudson.model.Hudson.Administer"));
        // ... and its save path (DescriptorImpl#newInstance, the same code the form submission runs) builds the subclass.
        // (A full HtmlUnit submit of that page is not usable here: it also rebuilds the test-only DummySecurityRealm.)
        JSONObject form = new JSONObject().element("data", new JSONObject()
                .element("USER:admin", new JSONObject().element("hudson.model.Hudson.Administer", true)));
        AuthorizationStrategy saved = j.jenkins.getDescriptorByType(PocGrantMatrixStrategy.DescriptorImpl.class)
                .newInstance((org.kohsuke.stapler.StaplerRequest2) null, form);
        assertSame(PocGrantMatrixStrategy.class, saved.getClass(), "security form save keeps the subclass (create hook)");
    }

    @Test
    void subclass_matrix_xmlRoundTripKeepsClassAndEntries() throws Exception {
        j.jenkins.setAuthorizationStrategy(Poc5Support.matrix(new PocGrantMatrixStrategy()));
        j.jenkins.save();
        String xml = Jenkins.XSTREAM2.toXML(j.jenkins.getAuthorizationStrategy());
        System.out.println("PoC-5 matrix subclass XML:\n" + xml);
        j.jenkins.reload();
        AuthorizationStrategy s = j.jenkins.getAuthorizationStrategy();
        assertSame(PocGrantMatrixStrategy.class, s.getClass());
        assertTrue(has(j.jenkins, "admin", Jenkins.ADMINISTER));
        assertTrue(has(j.jenkins, "bob", Jenkins.READ));
    }

    private static String export() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConfigurationAsCode.get().export(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void apply(String yaml) throws Exception {
        ConfigurationAsCode.get().configureWith(YamlSource.of(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void subclass_matrix_cascExportAndApply() throws Exception {
        j.jenkins.setAuthorizationStrategy(Poc5Support.matrix(new PocGrantMatrixStrategy()));
        String yaml = export();
        int i = yaml.indexOf("authorizationStrategy");
        System.out.println("PoC-5 matrix subclass JCasC export:\n" + (i < 0 ? "(none)" : yaml.substring(i, Math.min(yaml.length(), i + 500))));
        assertTrue(yaml.contains("batchControlProjectMatrix") && yaml.contains("alice"), "export round-trips the matrix");

        apply("jenkins:\n  authorizationStrategy:\n    batchControlProjectMatrix:\n      entries:\n"
                + "        - user:\n            name: admin\n            permissions:\n              - Overall/Administer\n"
                + "        - user:\n            name: carol\n            permissions:\n              - Overall/Read\n");
        assertSame(PocGrantMatrixStrategy.class, j.jenkins.getAuthorizationStrategy().getClass());
        assertTrue(has(j.jenkins, "carol", Jenkins.READ));
        assertFalse(has(j.jenkins, "alice", Jenkins.READ));

        // The unchanged matrix-auth symbol still selects the plain class (an explicit admin choice).
        apply("jenkins:\n  authorizationStrategy:\n    projectMatrix:\n      entries:\n"
                + "        - user:\n            name: admin\n            permissions:\n              - Overall/Administer\n");
        assertSame(ProjectMatrixAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass());
    }

    @Test
    void subclass_matrix_creatorAutoGrantOutlivesCreateWindow() throws Exception {
        j.jenkins.setAuthorizationStrategy(Poc5Support.matrix(new PocGrantMatrixStrategy()));
        Folder f = j.jenkins.createProject(Folder.class, "f");
        grant("bob", "f", true, Item.CREATE);
        FreeStyleProject created = Poc5Support.as("bob", () -> f.createProject(FreeStyleProject.class, "new"));
        clock.set(T0.plus(Duration.ofHours(2)));
        AuthorizationMatrixProperty amp = created.getProperty(AuthorizationMatrixProperty.class);
        assertNotNull(amp, "matrix-auth ItemListenerImpl added a creator property");
        assertTrue(has(created, "bob", Item.CONFIGURE), "permanent Configure survives the Create window");
    }

    @Test
    void subclass_matrix_granteeCanSelfGrantPermanently() throws Exception {
        j.jenkins.setAuthorizationStrategy(Poc5Support.matrix(new PocGrantMatrixStrategy()));
        FreeStyleProject p = j.createFreeStyleProject("job");
        grant("bob", "job", false, Item.CONFIGURE);
        String xml = p.getConfigFile().asString().replace("<properties/>",
                "<properties><hudson.security.AuthorizationMatrixProperty>"
                        + "<inheritanceStrategy class=\"org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy\"/>"
                        + "<permission>USER:hudson.model.Item.Configure:bob</permission>"
                        + "</hudson.security.AuthorizationMatrixProperty></properties>");
        WebRequest req = new WebRequest(new URL(j.getURL(), "job/job/config.xml"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml");
        req.setRequestBody(xml);
        j.jenkins.setCrumbIssuer(null);
        j.createWebClient().login("bob").getPage(req);
        clock.set(T0.plus(Duration.ofHours(2)));
        assertTrue(has(p, "bob", Item.CONFIGURE), "Item/Configure lets the grantee edit the job's matrix");
    }

    @Test
    void subclass_matrix_migrationFromExistingMatrixIsOneCall() throws Exception {
        ProjectMatrixAuthorizationStrategy existing = Poc5Support.matrix(new ProjectMatrixAuthorizationStrategy());
        j.jenkins.setAuthorizationStrategy(existing);
        FreeStyleProject p = j.createFreeStyleProject("job");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        p.addProperty(amp);

        j.jenkins.setAuthorizationStrategy(PocGrantMatrixStrategy.from(existing));
        j.jenkins.save();
        j.jenkins.reload();
        p = j.jenkins.getItemByFullName("job", FreeStyleProject.class);
        assertSame(PocGrantMatrixStrategy.class, j.jenkins.getAuthorizationStrategy().getClass());
        assertTrue(has(j.jenkins, "admin", Jenkins.ADMINISTER), "global entries copied");
        assertTrue(has(p, "alice", Item.CONFIGURE), "per-item properties untouched: they live on the items");
        // and back: the same copy to the plain class is the uninstall path
        ProjectMatrixAuthorizationStrategy back = new ProjectMatrixAuthorizationStrategy();
        PocGrantMatrixStrategy.copy((PocGrantMatrixStrategy) j.jenkins.getAuthorizationStrategy(), back);
        assertEquals(existing.getGrantedPermissionEntries(), back.getGrantedPermissionEntries());
    }

    // ------------------------------------------------------------------ role-strategy

    @Test
    void subclass_role_typeChecksPassAndGrantLayered() throws Exception {
        j.jenkins.setProjectNamingStrategy(new RoleBasedProjectNamingStrategy(false));
        j.jenkins.setAuthorizationStrategy(new PocGrantRoleStrategy(Poc5Support.roles()));
        FreeStyleProject p = j.createFreeStyleProject("team-a");
        FreeStyleProject q = j.createFreeStyleProject("other");
        assertNotNull(RoleBasedAuthorizationStrategy.getInstance());
        RoleStrategyConfig cfg = ExtensionList.lookupSingleton(RoleStrategyConfig.class);
        assertNotNull(cfg.getIconFileName());
        assertNotNull(cfg.getStrategy());
        assertTrue(has(p, "bob", Item.CONFIGURE), "item role");
        assertTrue(has(j.jenkins, "bob", Item.CREATE), "pattern-based Create reaches the root");
        Poc5Support.as("bob", () -> {
            assertThrows(Failure.class, () -> j.jenkins.getProjectNamingStrategy().checkName("", "other2"));
            return null;
        });
        assertFalse(has(q, "carol", Item.CONFIGURE));
        grant("carol", "other", false, Item.CONFIGURE);
        assertTrue(has(q, "carol", Item.CONFIGURE), "grant layered on the role ACL");
        clock.set(T0.plus(Duration.ofHours(1)));
        assertFalse(has(q, "carol", Item.CONFIGURE));
    }

    @Test
    void subclass_role_manageRolesSaveReplacesSubclass() throws Exception {
        j.jenkins.setAuthorizationStrategy(new PocGrantRoleStrategy(Poc5Support.roles()));
        FreeStyleProject q = j.createFreeStyleProject("other");
        grant("carol", "other", false, Item.CONFIGURE);
        assertTrue(has(q, "carol", Item.CONFIGURE));
        // What RoleBasedAuthorizationStrategy.DescriptorImpl#doRolesSubmit does: newInstance + setAuthorizationStrategy.
        JSONObject form = new JSONObject();
        form.put(RoleBasedAuthorizationStrategy.GLOBAL, new JSONObject().element("data", new JSONObject()
                .element("admin", new JSONObject().element("hudson.model.Hudson.Administer", true))));
        AuthorizationStrategy next = RoleBasedAuthorizationStrategy.DESCRIPTOR.newInstance((org.kohsuke.stapler.StaplerRequest2) null, form);
        j.jenkins.setAuthorizationStrategy(next);
        assertSame(RoleBasedAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "Manage Roles save installs the plain class");
        assertFalse(has(q, "carol", Item.CONFIGURE), "an open grant silently stops working");
    }

    @Test
    void subclass_role_xmlRoundTrip() throws Exception {
        j.jenkins.setAuthorizationStrategy(new PocGrantRoleStrategy(Poc5Support.roles()));
        FreeStyleProject p = j.createFreeStyleProject("team-a");
        j.jenkins.save();
        String xml = Jenkins.XSTREAM2.toXML(j.jenkins.getAuthorizationStrategy());
        System.out.println("PoC-5 role subclass XML (first 1500 chars):\n" + xml.substring(0, Math.min(1500, xml.length())));
        j.jenkins.reload();
        AuthorizationStrategy s = j.jenkins.getAuthorizationStrategy();
        System.out.println("PoC-5 role subclass after reload: " + s.getClass());
        assertSame(PocGrantRoleStrategy.class, s.getClass());
        assertTrue(has(j.jenkins.getItemByFullName("team-a"), "bob", Item.CONFIGURE));
        assertEquals("team-a", p.getName());
    }

    @Test
    void subclass_role_cascExportAndApply() throws Exception {
        j.jenkins.setAuthorizationStrategy(new PocGrantRoleStrategy(Poc5Support.roles()));
        String yaml = export();
        int i = yaml.indexOf("authorizationStrategy");
        System.out.println("PoC-5 role subclass JCasC export:\n" + (i < 0 ? "(none)" : yaml.substring(i, Math.min(yaml.length(), i + 800))));
        assertTrue(yaml.contains("batchControlRoleBased") && yaml.contains("team-.*"), "export round-trips the roles");
        apply("jenkins:\n  authorizationStrategy:\n    batchControlRoleBased:\n      roles:\n        global:\n"
                + "          - name: admin\n            permissions:\n              - Overall/Administer\n"
                + "            entries:\n              - user: admin\n"
                + "        items:\n          - name: team\n            pattern: team-.*\n            permissions:\n              - Job/Configure\n"
                + "            entries:\n              - user: bob\n");
        assertSame(PocGrantRoleStrategy.class, j.jenkins.getAuthorizationStrategy().getClass());
        assertTrue(has(j.createFreeStyleProject("team-z"), "bob", Item.CONFIGURE));
    }
}
