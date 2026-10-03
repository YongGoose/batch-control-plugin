package io.jenkins.plugins.batchcontrol;

import com.michelin.cio.hudson.plugins.rolestrategy.PermissionEntry;
import com.michelin.cio.hudson.plugins.rolestrategy.Role;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleMap;
import hudson.model.User;
import hudson.security.HudsonPrivateSecurityRealm;
import io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-02-107 (D-35f(a), note 193): a role administrator without Overall/SystemRead gets an ok from
 * {@code checkSidName} without a security-realm lookup: an unknown sid is echoed, not reported as
 * not found. Guard: the administrator (who has SystemRead) gets "not found" for the same sid.
 *
 * <p>role-strategy's item/agent role administration permissions are disabled unless
 * {@code RoleBasedAuthorizationStrategy.useItemAndAgentRoles} is set when the class loads, so this
 * row runs in its own Jenkins JVM with that property. Written from docs/DECISIONS.md D-35f and the
 * coordinator brief only (no src/main knowledge).
 */
public class RoleSidValidationRoleAdminTest {

    @RegisterExtension
    final RealJenkinsExtension rr = new RealJenkinsExtension()
            .javaOptions("-D" + RoleBasedAuthorizationStrategy.class.getName() + ".useItemAndAgentRoles=true");

    /** T-02-107: role admin without SystemRead: ok, no realm lookup; admin: not found. */
    @Test
    public void t_02_107_roleAdminWithoutSystemReadGetsOkWithoutLookup() throws Throwable {
        rr.then(RoleSidValidationRoleAdminTest::check);
    }

    private static void check(JenkinsRule r) throws Throwable {
        assertTrue(RoleBasedAuthorizationStrategy.ITEM_ROLES_ADMIN.getEnabled(), "premise: the item roles admin permission is enabled");
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        for (String u : new String[] {"admin", "roleadmin", "reader"}) {
            realm.createAccount(u, u);
        }
        r.jenkins.setSecurityRealm(realm);
        TreeMap<Role, Set<PermissionEntry>> global = new TreeMap<>();
        global.put(new Role("admin", Pattern.compile(".*"), Set.of(Jenkins.ADMINISTER), ""), new HashSet<>(Set.of(PermissionEntry.user("admin"))));
        global.put(new Role("reader", Pattern.compile(".*"), Set.of(Jenkins.READ), ""),
                new HashSet<>(Set.of(PermissionEntry.user("roleadmin"), PermissionEntry.user("reader"))));
        global.put(new Role("roles", Pattern.compile(".*"), Set.of(RoleBasedAuthorizationStrategy.ITEM_ROLES_ADMIN), ""),
                new HashSet<>(Set.of(PermissionEntry.user("roleadmin"))));
        Map<String, RoleMap> m = new HashMap<>();
        m.put(RoleBasedAuthorizationStrategy.GLOBAL, new RoleMap(global));
        m.put(RoleBasedAuthorizationStrategy.PROJECT, new RoleMap(new TreeMap<>()));
        m.put(RoleBasedAuthorizationStrategy.SLAVE, new RoleMap(new TreeMap<>()));
        r.jenkins.setAuthorizationStrategy(new BatchControlRoleBasedAuthorizationStrategy(m, Collections.emptySet()));
        // the child JVM rejects the WebClient's crumb (note 182), so the crumb issuer is off here
        r.jenkins.setCrumbIssuer(null);

        User roleadmin = User.getById("roleadmin", true);
        assertTrue(r.jenkins.getACL().hasPermission2(roleadmin.impersonate2(), RoleBasedAuthorizationStrategy.ITEM_ROLES_ADMIN),
                "premise: roleadmin holds the item roles admin permission");
        assertFalse(r.jenkins.getACL().hasPermission2(roleadmin.impersonate2(), Jenkins.SYSTEM_READ),
                "premise: roleadmin does not hold Overall/SystemRead");

        String admin = post(r, "admin", "ghost");
        assertTrue(admin.contains("ghost") && (admin.toLowerCase().contains("not found") || admin.contains("not-found")),
                "guard: with SystemRead the unknown sid is looked up and reported as not found: " + admin);
        String noLookup = post(r, "roleadmin", "ghost");
        assertTrue(noLookup.contains("ghost"), "the sid must be echoed: " + noLookup);
        assertFalse(noLookup.toLowerCase().contains("not found") || noLookup.contains("not-found")
                        || Pattern.compile("class=[\"']?error\\b").matcher(noLookup).find(),
                "without SystemRead the answer is ok without a realm lookup: " + noLookup);
    }

    private static String post(JenkinsRule r, String user, String value) throws Exception {
        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.login(user, user);
        WebRequest request = new WebRequest(new java.net.URL(r.getURL(), RoleSidValidationTest.DESCRIPTOR + "checkSidName"), HttpMethod.POST);
        request.setCharset(StandardCharsets.UTF_8);
        request.setRequestParameters(List.of(new NameValuePair("value", value), new NameValuePair("type", "USER")));
        WebResponse response = wc.getPage(request).getWebResponse();
        assertEquals(200, response.getStatusCode(), user + " must get a check result: " + response.getContentAsString());
        return response.getContentAsString();
    }
}
