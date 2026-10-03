package io.jenkins.plugins.batchcontrol;

import com.michelin.cio.hudson.plugins.rolestrategy.PermissionEntry;
import com.michelin.cio.hudson.plugins.rolestrategy.Role;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleMap;
import hudson.model.User;
import hudson.security.HudsonPrivateSecurityRealm;
import io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-35f(a): for forward compatibility with role-strategy PR #766 (which removes the parent
 * descriptor's {@code doCheckName} and adds {@code doCheckSidName(value, type)}), the Batch Control
 * role strategy's descriptor answers {@code checkSidName} and {@code checkName} itself. Matrix rows
 * T-02-100..106 (note 193); the role-admin-without-SystemRead row is
 * {@link RoleSidValidationRoleAdminTest} (T-02-107).
 *
 * <p>Contract (coordinator brief from D-35f(a)): POST with crumb only; Overall/Read alone is 403;
 * empty value ok; type other than USER/GROUP is the error "Invalid type"; {@code authenticated}
 * GROUP and {@code anonymous} USER are internal markers; an existing user is ok and named; an
 * unknown sid is not reported as found (role-strategy strikes it through); HTML-special characters
 * in value, type and a user's full name are escaped. {@code checkName} keeps role-strategy 918's
 * behaviour ({@code [TYPE:sid]}; no type prefix is an error).
 *
 * <p>Security realm: a private realm with real accounts, so "unknown" is decidable (the test
 * harness's dummy realm accepts every name). Written from docs/DECISIONS.md D-35f and the brief
 * only (no src/main knowledge).
 */
@WithJenkins
public class RoleSidValidationTest {

    static final String DESCRIPTOR = "descriptor/" + BatchControlRoleBasedAuthorizationStrategy.class.getName() + "/";
    /** FormValidation.error renders a div whose class is "error". */
    static final Pattern ERROR = Pattern.compile("class=[\"']?error\\b");
    static final Pattern NOT_FOUND = Pattern.compile("(?i)not[ -]found");
    static final String XSS = "<img src=x onerror=alert(1)>";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        for (String u : new String[] {"admin", "reader", "alice", "carol"}) {
            realm.createAccount(u, u);
        }
        j.jenkins.setSecurityRealm(realm);
        User.getById("alice", true).setFullName("Alice Liddell");
        User.getById("carol", true).setFullName("<i>Carol & Co</i>");
        j.jenkins.setAuthorizationStrategy(new BatchControlRoleBasedAuthorizationStrategy(roles(), Collections.emptySet()));
    }

    static Map<String, RoleMap> roles() {
        TreeMap<Role, Set<PermissionEntry>> global = new TreeMap<>();
        global.put(new Role("admin", Pattern.compile(".*"), Set.of(Jenkins.ADMINISTER), ""), new HashSet<>(Set.of(PermissionEntry.user("admin"))));
        global.put(new Role("reader", Pattern.compile(".*"), Set.of(Jenkins.READ), ""),
                new HashSet<>(Set.of(PermissionEntry.user("reader"), PermissionEntry.user("alice"), PermissionEntry.user("carol"))));
        Map<String, RoleMap> m = new HashMap<>();
        m.put(RoleBasedAuthorizationStrategy.GLOBAL, new RoleMap(global));
        m.put(RoleBasedAuthorizationStrategy.PROJECT, new RoleMap(new TreeMap<>()));
        m.put(RoleBasedAuthorizationStrategy.SLAVE, new RoleMap(new TreeMap<>()));
        return m;
    }

    /** T-02-100: Overall/Read alone gets 403 from checkSidName; the administrator gets 200 (guard). */
    @Test
    public void t_02_100_checkSidNameRefusesOverallReadOnly() throws Exception {
        assertEquals(200, sid("admin", "alice", "USER").getStatusCode(), "guard: the administrator gets a check result");
        WebResponse r = sid("reader", "alice", "USER");
        assertEquals(403, r.getStatusCode(), "a user with only Overall/Read must get 403: " + excerpt(r.getContentAsString()));
        assertFalse(r.getContentAsString().contains("Alice Liddell"), "a refused check must not reveal the user's name");
    }

    /** T-02-101: a GET is refused (405 or another refusal), the POST twin answers 200. */
    @Test
    public void t_02_101_checkSidNameRefusesGet() throws Exception {
        assertEquals(200, sid("admin", "alice", "USER").getStatusCode(), "guard: the POST gets a check result");
        JenkinsRule.WebClient wc = client("admin");
        WebResponse get = wc.getPage(new WebRequest(new URL(j.getURL(), DESCRIPTOR + "checkSidName?value=alice&type=USER"),
                HttpMethod.GET)).getWebResponse();
        assertTrue(get.getStatusCode() >= 400, "checkSidName must refuse a GET, got " + get.getStatusCode());
        assertFalse(get.getContentAsString().contains("Alice Liddell"), "a refused GET must not carry a check result");
    }

    /**
     * T-02-102: an empty value is ok (no error); a type other than USER/GROUP is the error
     * "Invalid type", with the posted type escaped. Guard: USER and GROUP are not errors.
     */
    @Test
    public void t_02_102_checkSidNameEmptyValueAndInvalidType() throws Exception {
        String empty = body(sid("admin", "", "USER"));
        assertFalse(ERROR.matcher(empty).find(), "an empty value must be ok: " + excerpt(empty));
        for (String type : new String[] {"USER", "GROUP"}) {
            assertFalse(ERROR.matcher(body(sid("admin", "alice", type))).find(), "guard: type " + type + " is valid");
        }
        for (String type : new String[] {"EITHER", "", "<b>bad</b>"}) {
            String b = body(sid("admin", "alice", type));
            assertTrue(ERROR.matcher(b).find() && b.contains("Invalid type"),
                    "type '" + type + "' must be the error \"Invalid type\": " + excerpt(b));
            assertFalse(b.contains("<b>bad</b>"), "the posted type must be escaped: " + excerpt(b));
        }
    }

    /**
     * T-02-103: {@code authenticated} as GROUP and {@code anonymous} as USER are reported as internal
     * markers; guard: an ordinary unknown sid of the same type is not.
     */
    @Test
    public void t_02_103_checkSidNameInternalMarkers() throws Exception {
        String group = body(sid("admin", "authenticated", "GROUP"));
        assertTrue(group.toLowerCase(Locale.ROOT).contains("internal") && !ERROR.matcher(group).find(),
                "GROUP authenticated must be reported as an internal group: " + excerpt(group));
        String user = body(sid("admin", "anonymous", "USER"));
        assertTrue(user.toLowerCase(Locale.ROOT).contains("internal") && !ERROR.matcher(user).find(),
                "USER anonymous must be reported as an internal user: " + excerpt(user));
        assertFalse(body(sid("admin", "ghosts", "GROUP")).toLowerCase(Locale.ROOT).contains("internal"),
                "guard: an ordinary unknown group is not internal");
    }

    /**
     * T-02-104: an existing user is ok and named (full name); an unknown sid is not reported as found
     * (role-strategy strikes it through and says "not found") and is not an error.
     */
    @Test
    public void t_02_104_checkSidNameExistingAndUnknownSid() throws Exception {
        String known = body(sid("admin", "alice", "USER"));
        assertTrue(known.contains("Alice Liddell") && !ERROR.matcher(known).find(), "an existing user must be ok and named: " + excerpt(known));
        assertFalse(NOT_FOUND.matcher(known).find() || known.contains("not-found"), "an existing user is found: " + excerpt(known));
        for (String type : new String[] {"USER", "GROUP"}) {
            String unknown = body(sid("admin", "ghost", type));
            assertTrue(unknown.contains("ghost"), "the unknown sid must be shown: " + excerpt(unknown));
            assertTrue(NOT_FOUND.matcher(unknown).find() || unknown.contains("not-found"),
                    "an unknown " + type + " must not be reported as found: " + excerpt(unknown));
            assertFalse(ERROR.matcher(unknown).find(), "an unknown " + type + " is a warning or ok, not an error: " + excerpt(unknown));
        }
    }

    /** T-02-105: HTML-special characters in the value and in a user's full name are escaped. */
    @Test
    public void t_02_105_checkSidNameEscapesValueAndFullName() throws Exception {
        for (String type : new String[] {"USER", "GROUP"}) {
            String b = body(sid("admin", XSS, type));
            assertFalse(b.contains(XSS), type + ": the posted value must be escaped: " + excerpt(b));
            assertTrue(b.contains("&lt;img"), type + ": the posted value must be shown escaped: " + excerpt(b));
        }
        String carol = body(sid("admin", "carol", "USER"));
        assertFalse(carol.contains("<i>Carol"), "the user's full name must be escaped: " + excerpt(carol));
        assertTrue(carol.contains("&lt;i&gt;Carol"), "the user's full name must be shown escaped: " + excerpt(carol));
    }

    /**
     * T-02-106: checkName keeps role-strategy 918's behaviour: {@code [USER:alice]} ok and named,
     * {@code [GROUP:x]} not found (not an error), {@code [alice]} the error "No type prefix", HTML in
     * the value escaped; Overall/Read alone 403 (guard: admin 200).
     */
    @Test
    public void t_02_106_checkNameKeeps918Behaviour() throws Exception {
        String user = body(name("admin", "[USER:alice]"));
        assertTrue(user.contains("Alice Liddell") && !ERROR.matcher(user).find(), "[USER:alice] must be ok and named: " + excerpt(user));
        String group = body(name("admin", "[GROUP:x]"));
        assertTrue((NOT_FOUND.matcher(group).find() || group.contains("not-found")) && !ERROR.matcher(group).find(),
                "[GROUP:x] must be reported as not found, not as an error: " + excerpt(group));
        String noPrefix = body(name("admin", "[alice]"));
        assertTrue(ERROR.matcher(noPrefix).find() && noPrefix.contains("No type prefix"),
                "a value without a type prefix must be the error \"No type prefix\": " + excerpt(noPrefix));
        String xss = body(name("admin", "[USER:" + XSS + "]"));
        assertFalse(xss.contains(XSS), "checkName must escape the sid: " + excerpt(xss));
        WebResponse refused = name("reader", "[USER:alice]");
        assertEquals(403, refused.getStatusCode(), "checkName must answer 403 to Overall/Read alone");
    }

    // ---------------------------------------------------------------- helpers

    private JenkinsRule.WebClient client(String user) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.login(user, user);
        wc.getOptions().setRedirectEnabled(false);
        return wc;
    }

    private WebResponse post(String user, String method, List<NameValuePair> params) throws Exception {
        JenkinsRule.WebClient wc = client(user);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(DESCRIPTOR + method), HttpMethod.POST);
        request.setCharset(StandardCharsets.UTF_8);
        request.setRequestParameters(params);
        return wc.getPage(request).getWebResponse();
    }

    private WebResponse sid(String user, String value, String type) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("value", value));
        params.add(new NameValuePair("type", type));
        return post(user, "checkSidName", params);
    }

    private WebResponse name(String user, String value) throws Exception {
        return post(user, "checkName", List.of(new NameValuePair("value", value)));
    }

    private static String body(WebResponse r) {
        assertEquals(200, r.getStatusCode(), "the administrator's POST must get a check result: " + excerpt(r.getContentAsString()));
        return r.getContentAsString();
    }
}
