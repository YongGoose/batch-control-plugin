package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.Arrays;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-UI-48 (note 198): the active-grant URLs under real plugin class loaders and a non-root context
 * path. {@code batch-control/grants/active/} and {@code grants/active/<id>/} lead (302) to the
 * grants screen for a user allowed to see it; a GET of the state-changing
 * {@code grants/active/<id>/revoke} answers 405 (the URL exists, the method is wrong), not 404;
 * a user without a grants permission is refused. Reviewer comment of 2026-09-27 (the former
 * GET-only redirect handlers were replaced, ui-dev aadb8da).
 *
 * <p>Written from the coordinator's brief and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class ActiveGrantsRealJenkinsTest {

    @RegisterExtension
    final RealJenkinsExtension rr = new RealJenkinsExtension();

    /** T-UI-48. */
    @Test
    public void t_ui_48_activeGrantUrlsRedirectAndRevokeGetIs405InARealJenkins() throws Throwable {
        rr.then(ActiveGrantsRealJenkinsTest::check);
    }

    private static void check(JenkinsRule r) throws Throwable {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST_GRANT).everywhere().to("g1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ).everywhere().to("reader"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        r.createFreeStyleProject("batch-x");

        GrantRequest request;
        try (ACLContext ignored = ACL.as2(User.getById("g1", true).impersonate2())) {
            request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "batch-x"),
                    Arrays.asList(GrantAction.CONFIGURE), 30, "maintenance", "a1");
        }
        Grant grant;
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            grant = GrantRequestService.get().approve(request.getId(), "ok");
        }
        assertNotNull(grant, "fixture: an active grant");

        assertTrue(r.contextPath.length() > 1, "premise: a non-root context path, was '" + r.contextPath + "'");
        String grants = new URL(r.getURL(), "batch-control/grants/").getPath();
        for (String user : new String[] {"admin", "g1"}) {
            for (String path : new String[] {"batch-control/grants/active/", "batch-control/grants/active/" + grant.getId() + "/"}) {
                WebResponse answer = get(r, user, path);
                assertEquals(302, answer.getStatusCode(), user + " GET /" + path + " must redirect (302), got "
                        + answer.getStatusCode() + ": " + excerpt(answer));
                String location = answer.getResponseHeaderValue("Location");
                assertNotNull(location, user + " GET /" + path + ": the redirect must carry a Location");
                assertEquals(grants, new URL(answer.getWebRequest().getUrl(), location).getPath(),
                        user + " GET /" + path + " must lead to the grants screen under the context path, Location " + location);
            }
        }

        WebResponse revoke = get(r, "admin", "batch-control/grants/active/" + grant.getId() + "/revoke");
        assertEquals(405, revoke.getStatusCode(), "GET of revoke must answer 405 (the URL exists, POST only), got "
                + revoke.getStatusCode() + ": " + excerpt(revoke));

        for (String path : new String[] {"batch-control/grants/active/", "batch-control/grants/active/" + grant.getId() + "/"}) {
            WebResponse refused = get(r, "reader", path);
            assertTrue(refused.getStatusCode() >= 400, "reader (no grants permission) GET /" + path + " must be refused, got "
                    + refused.getStatusCode());
        }
    }

    private static WebResponse get(JenkinsRule r, String user, String path) throws Exception {
        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        wc.login(user);
        wc.getOptions().setRedirectEnabled(false);
        return wc.getPage(new WebRequest(new URL(r.getURL(), path), HttpMethod.GET)).getWebResponse();
    }

    private static String excerpt(WebResponse response) {
        String body = response.getContentAsString();
        return body.length() > 800 ? body.substring(0, 800) : body;
    }
}
