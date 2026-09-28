package io.jenkins.plugins.batchcontrol.poc.auth;

import static io.jenkins.plugins.batchcontrol.poc.auth.Poc5Support.T0;
import static io.jenkins.plugins.batchcontrol.poc.auth.Poc5Support.has;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import hudson.XmlFile;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import hudson.security.AuthorizationMatrixProperty;
import hudson.security.AuthorizationStrategy;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.html.HtmlForm;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** PoC-5 option B: no wrapper; the window is native entries added and removed by the plugin. */
@WithJenkins
class Poc5OptionBNativeTest {

    private static final Instant END = T0.plus(Duration.ofHours(1));

    private JenkinsRule j;
    private Poc5Support.SettableClock clock;

    @BeforeEach
    void setUp(JenkinsRule j) {
        this.j = j;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        clock = new Poc5Support.SettableClock(T0);
        PocNativeGrants.clock = clock;
    }

    private FreeStyleProject jobWithAliceConfigure(String name) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject(name);
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        p.addProperty(amp);
        return p;
    }

    // ------------------------------------------------------------------ matrix-auth

    @Test
    void native_matrix_grantAndSweep_keepsAdminEntriesAndPreExisting() throws Exception {
        j.jenkins.setAuthorizationStrategy(Poc5Support.matrix(new ProjectMatrixAuthorizationStrategy()));
        FreeStyleProject p = jobWithAliceConfigure("job");
        FreeStyleProject q = j.createFreeStyleProject("job2");
        AuthorizationMatrixProperty qp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        qp.add(Item.CONFIGURE, PermissionEntry.user("bob"));
        q.addProperty(qp);

        PocNativeGrants.grantMatrix("g1", "bob", p, Item.CONFIGURE, END, true, false);
        PocNativeGrants.grantMatrix("g2", "bob", q, Item.CONFIGURE, END, true, false);
        assertTrue(has(p, "bob", Item.CONFIGURE));
        clock.set(END.minusSeconds(1));
        assertEquals(0, PocNativeGrants.sweep());

        clock.set(END);
        assertTrue(has(p, "bob", Item.CONFIGURE), "between expiry and the next sweep the native entry still answers");
        assertEquals(2, PocNativeGrants.sweep());
        assertFalse(has(p, "bob", Item.CONFIGURE), "removed by the sweep");
        assertTrue(has(p, "alice", Item.CONFIGURE), "administrator's entry untouched");
        assertTrue(has(q, "bob", Item.CONFIGURE), "an entry bob already had is not taken away");
    }

    @Test
    void native_matrix_staleAdminFormResurrectsExpiredEntry() throws Exception {
        j.jenkins.setAuthorizationStrategy(Poc5Support.matrix(new ProjectMatrixAuthorizationStrategy()));
        FreeStyleProject p = jobWithAliceConfigure("job");
        PocNativeGrants.grantMatrix("g1", "bob", p, Item.CONFIGURE, END, true, false);
        // An administrator opens the job configuration while the window is open ...
        HtmlForm stale = j.createWebClient().login("admin").getPage(p, "configure").getFormByName("config");
        clock.set(END);
        PocNativeGrants.sweep();
        assertFalse(has(p, "bob", Item.CONFIGURE));
        // ... and saves it after the sweep.
        j.submit(stale);
        assertTrue(has(p, "bob", Item.CONFIGURE), "the expired entry is back, and the ledger no longer knows about it");
        assertTrue(PocNativeGrants.read().isEmpty());
    }

    @Test
    void native_matrix_staleAdminFormDropsOpenWindow() throws Exception {
        j.jenkins.setAuthorizationStrategy(Poc5Support.matrix(new ProjectMatrixAuthorizationStrategy()));
        FreeStyleProject p = jobWithAliceConfigure("job");
        HtmlForm stale = j.createWebClient().login("admin").getPage(p, "configure").getFormByName("config");
        PocNativeGrants.grantMatrix("g1", "bob", p, Item.CONFIGURE, END, true, false);
        assertTrue(has(p, "bob", Item.CONFIGURE));
        j.submit(stale);
        assertFalse(has(p, "bob", Item.CONFIGURE), "an approved window silently ends early");
        assertEquals(1, PocNativeGrants.read().size(), "the ledger still believes it is open");
    }

    @Test
    void native_matrix_configChurnIsVisible() throws Exception {
        j.jenkins.setAuthorizationStrategy(Poc5Support.matrix(new ProjectMatrixAuthorizationStrategy()));
        FreeStyleProject p = jobWithAliceConfigure("job");
        String before = p.getConfigFile().asString();
        JobSaves.saves.clear();
        PocNativeGrants.grantMatrix("g1", "bob", p, Item.CONFIGURE, END, true, false);
        String during = p.getConfigFile().asString();
        clock.set(END);
        PocNativeGrants.sweep();
        String after = p.getConfigFile().asString();
        System.out.println("PoC-5 job saves for grant+expiry: " + JobSaves.saves);
        assertTrue(during.contains("USER:hudson.model.Item.Configure:bob"), "the grant is written into the job's config.xml");
        assertFalse(after.contains(":bob"));
        assertEquals(before, after, "net effect is zero, but ...");
        assertTrue(JobSaves.saves.size() >= 2, "... every grant and expiry is a job configuration save (history, SCM sync, audit noise)");
    }

    @TestExtension("native_matrix_configChurnIsVisible")
    public static final class JobSaves extends SaveableListener {
        static final List<String> saves = new ArrayList<>();

        @Override
        public void onChange(Saveable o, XmlFile file) {
            if (o instanceof FreeStyleProject) {
                saves.add(((FreeStyleProject) o).getFullName());
            }
        }
    }

    // ------------------------------------------------------------------ role-strategy

    @Test
    void native_role_grantAndSweep() throws Exception {
        j.jenkins.setAuthorizationStrategy(new RoleBasedAuthorizationStrategy(Poc5Support.roles()));
        FreeStyleProject q = j.createFreeStyleProject("other");
        FreeStyleProject t = j.createFreeStyleProject("team-a");
        AuthorizationStrategy before = j.jenkins.getAuthorizationStrategy();
        PocNativeGrants.grantRole("g1", "carol", "other", Item.CONFIGURE, END);
        assertNotSame(before, j.jenkins.getAuthorizationStrategy(), "only public route: rebuild and replace the strategy");
        assertTrue(has(q, "carol", Item.CONFIGURE));
        clock.set(END);
        PocNativeGrants.sweep();
        assertFalse(has(q, "carol", Item.CONFIGURE));
        assertTrue(has(t, "bob", Item.CONFIGURE), "administrator's roles survive the two rebuilds");
    }

    private static JSONObject rolesForm(boolean withGrantRole) {
        JSONObject items = new JSONObject().element("team", new JSONObject().element("pattern", "team-.*")
                .element("hudson.model.Item.Read", true).element("hudson.model.Item.Configure", true)
                .element("hudson.model.Item.Create", true));
        if (withGrantRole) {
            items.element("bc-g1", new JSONObject().element("pattern", "\\Qother\\E").element("hudson.model.Item.Configure", true));
        }
        return new JSONObject()
                .element(RoleBasedAuthorizationStrategy.GLOBAL, new JSONObject().element("data", new JSONObject()
                        .element("admin", new JSONObject().element("hudson.model.Hudson.Administer", true))
                        .element("reader", new JSONObject().element("hudson.model.Hudson.Read", true))))
                .element(RoleBasedAuthorizationStrategy.PROJECT, new JSONObject().element("data", items));
    }

    @Test
    void native_role_staleManageRolesFormDropsOpenWindow() throws Exception {
        j.jenkins.setAuthorizationStrategy(new RoleBasedAuthorizationStrategy(Poc5Support.roles()));
        FreeStyleProject q = j.createFreeStyleProject("other");
        JSONObject stale = rolesForm(false); // page loaded before the grant
        PocNativeGrants.grantRole("g1", "carol", "other", Item.CONFIGURE, END);
        assertTrue(has(q, "carol", Item.CONFIGURE));
        // RoleBasedAuthorizationStrategy.DescriptorImpl#doRolesSubmit = newInstance + setAuthorizationStrategy
        j.jenkins.setAuthorizationStrategy(RoleBasedAuthorizationStrategy.DESCRIPTOR
                .newInstance((org.kohsuke.stapler.StaplerRequest2) null, stale));
        assertFalse(has(q, "carol", Item.CONFIGURE), "window ends early (fail-safe direction)");
    }

    @Test
    void native_role_staleFormsAfterExpiryDoNotResurrect() throws Exception {
        j.jenkins.setAuthorizationStrategy(new RoleBasedAuthorizationStrategy(Poc5Support.roles()));
        FreeStyleProject q = j.createFreeStyleProject("other");
        PocNativeGrants.grantRole("g1", "carol", "other", Item.CONFIGURE, END);
        JSONObject staleRoles = rolesForm(true); // both pages loaded during the window
        JSONObject staleAssign = new JSONObject()
                .element(RoleBasedAuthorizationStrategy.GLOBAL, JSONArray.fromObject(
                        "[{type:'USER',name:'admin',roles:['admin']},{type:'USER',name:'carol',roles:['reader']},"
                                + "{type:'USER',name:'bob',roles:['reader']}]"))
                .element(RoleBasedAuthorizationStrategy.PROJECT, JSONArray.fromObject(
                        "[{type:'USER',name:'carol',roles:['bc-g1']},{type:'USER',name:'bob',roles:['team']}]"))
                .element(RoleBasedAuthorizationStrategy.SLAVE, new JSONArray());
        clock.set(END);
        PocNativeGrants.sweep();
        assertFalse(has(q, "carol", Item.CONFIGURE));

        RoleBasedAuthorizationStrategy.DESCRIPTOR.doAssignSubmit(staleAssign);
        assertFalse(has(q, "carol", Item.CONFIGURE), "assignment to a deleted role is skipped");
        j.jenkins.setAuthorizationStrategy(RoleBasedAuthorizationStrategy.DESCRIPTOR
                .newInstance((org.kohsuke.stapler.StaplerRequest2) null, staleRoles));
        assertFalse(has(q, "carol", Item.CONFIGURE), "role re-created from the stale form carries no assignees");
        RoleBasedAuthorizationStrategy.DESCRIPTOR.doAssignSubmit(staleAssign);
        assertTrue(has(q, "carol", Item.CONFIGURE), "both stale pages submitted in this order do resurrect it");
    }
}
