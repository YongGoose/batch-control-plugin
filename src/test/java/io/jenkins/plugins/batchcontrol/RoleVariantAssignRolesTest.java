package io.jenkins.plugins.batchcontrol;

import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC 2 / D-35a: "With the Batch Control role-strategy strategy installed, Manage Roles, item and
 * agent roles, pattern-based Create and the role naming strategy work." Matrix row T-02-46
 * (e2e-03 DEF-20, note 124).
 *
 * <p>role-strategy's Assign Roles page validates every user or group cell by calling its
 * strategy descriptor's {@code checkName} (and the role pages call {@code checkPattern} and
 * {@code checkForWhitespace}) under the installed strategy's descriptor URL. Under the variant
 * that URL answered 404 and the "Oops! Not Found" page replaced every user cell. Only a browser
 * shows the replaced cells; the closest HtmlUnit check is the endpoint itself, compared with the
 * same call on role-strategy's own descriptor (the control that the call is well-formed).
 *
 * <p>Written from docs/SPEC.md, docs/reports/e2e-03.md and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class RoleVariantAssignRolesTest {

    private static final String VARIANT = BatchControlRoleBasedAuthorizationStrategy.class.getName();
    private static final String PLAIN = RoleBasedAuthorizationStrategy.class.getName();

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(
                new BatchControlRoleBasedAuthorizationStrategy(StrategyFixtures.roles(), Collections.emptySet()));
        assertSame(BatchControlRoleBasedAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "fixture: the Batch Control role strategy must be installed");
    }

    /**
     * T-02-46 (DEF-20): the variant's descriptor serves role-strategy's validation endpoints
     * (checkName, checkPattern, checkForWhitespace) under both descriptor URL forms, answering
     * 200 with the same kind of answer as role-strategy's own descriptor, never the 404 page.
     */
    @Test
    public void t_02_46_assignRolesValidationWorksUnderTheVariant() throws Exception {
        String[][] calls = {
            {"checkName", "[USER:bob]", "bob"},
            {"checkPattern", "team-.*", null},
            {"checkForWhitespace", "team-a", null},
        };
        for (String prefix : new String[] {"descriptorByName/", "descriptor/"}) {
            for (String[] call : calls) {
                WebResponse plain = check(prefix + PLAIN + "/" + call[0], call[1]);
                assertEquals(200, plain.getStatusCode(), "control: role-strategy's own " + call[0] + " answers at " + prefix);
                WebResponse variant = check(prefix + VARIANT + "/" + call[0], call[1]);
                String body = variant.getContentAsString();
                assertEquals(200, variant.getStatusCode(), "the variant's descriptor must serve " + call[0] + " at " + prefix
                        + " (the Assign/Manage Roles pages call it), got HTTP " + variant.getStatusCode() + ": " + excerpt(body));
                assertFalse(body.contains("Oops") || body.contains("Not Found"), call[0] + " must not answer the 404 page: " + excerpt(body));
                if (call[2] != null) {
                    assertTrue(body.contains(call[2]), call[0] + " must describe the user it validates, as role-strategy's own does: "
                            + excerpt(body));
                }
            }
        }
    }

    /**
     * T-SEC-52 (security scan alerts 31-33): the variant descriptor's form checks checkName,
     * checkPattern and checkForWhitespace answer 403 to bob (Overall/Read, no Administer) with no
     * check result, refuse a GET by the administrator, and answer the administrator's POST with a
     * check result (premise). Note 177.
     */
    @Test
    public void t_sec_52_variantFormChecksRequireAdministerAndPost() throws Exception {
        String[][] calls = {
            {"checkName", "[USER:bob]"},
            {"checkPattern", "team-.*"},
            {"checkForWhitespace", "team-a"},
        };
        for (String[] call : calls) {
            String path = "descriptorByName/" + VARIANT + "/" + call[0];
            WebResponse admin = check(path, call[1]);
            assertEquals(200, admin.getStatusCode(), "premise: the administrator's POST of " + call[0] + " gets a check result");

            List<NameValuePair> params = new ArrayList<>();
            params.add(new NameValuePair("value", call[1]));
            WebResponse bob = ApproverFormFixtures.post(j, "bob", path, params);
            assertEquals(403, bob.getStatusCode(), call[0] + " must answer 403 to a user without Overall/Administer, got "
                    + bob.getStatusCode() + ": " + excerpt(bob.getContentAsString()));

            WebResponse get = ApproverFormFixtures.get(j, "admin", path + "?value="
                    + java.net.URLEncoder.encode(call[1], java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(get.getStatusCode() >= 400, call[0] + " must refuse a GET, got " + get.getStatusCode() + ": "
                    + excerpt(get.getContentAsString()));
        }
    }

    private WebResponse check(String path, String value) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("value", value));
        return ApproverFormFixtures.post(j, "admin", path, params);
    }
}
