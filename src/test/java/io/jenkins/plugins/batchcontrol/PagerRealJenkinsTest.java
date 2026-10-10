package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-UI-149 (#45, note 329): the paged Run Requests, Grants and Activations pages render completely
 * in a real Jenkins, where a plugin class is not visible to the web application's class loader.
 * The first #45 fix resolved a plugin class from a Jelly tag; under JenkinsRule's flat class path
 * that worked, but in a real Jenkins every page with more than one page in a list was cut off in the
 * middle of the first table (HTTP 200, partial HTML). Each page has more than 50 rows in both its
 * pending and its ended list; the administrator requests {@code ?pendingPage=2} and gets the whole
 * page, with the ended list's pager link to its page 2 carrying {@code pendingPage=2} (T-UI-146..148
 * contract).
 *
 * <p>Written from docs/SPEC.md, docs/TEST-MATRIX.md and the issue brief only (no src/main knowledge).
 */
public class PagerRealJenkinsTest {

    private static final int ROWS = 52;

    @RegisterExtension
    final RealJenkinsExtension rr = new RealJenkinsExtension();

    /** T-UI-149: all three paged pages are complete and keep the other list's page, in a real Jenkins. */
    @Test
    public void t_ui_149_pagedListsRenderCompletelyInARealJenkins() throws Throwable {
        rr.then(PagerRealJenkinsTest::run);
    }

    private static void run(JenkinsRule j) throws Throwable {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.REQUEST_GRANT).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        FreeStyleProject job = j.createFreeStyleProject("batch-x");
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));

        for (int i = 0; i < ROWS; i++) {
            as("u1", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "pending run", "a1"));
            String id = as("u1", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "cancelled run", "a1").getId());
            as("u1", () -> {
                RunRequestService.get().cancel(id);
                return null;
            });
        }
        check(j, "batch-control/requests/");

        for (int i = 0; i < ROWS; i++) {
            as("u1", () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "batch-x"),
                    Arrays.asList(GrantAction.CONFIGURE), 30, "pending window", "a1"));
            String id = as("u1", () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "batch-x"),
                    Arrays.asList(GrantAction.CONFIGURE), 30, "rejected window", "a1").getId());
            as("a1", () -> GrantRequestService.get().reject(id, "no"));
        }
        check(j, "batch-control/grants/");

        for (int i = 0; i < ROWS; i++) {
            as("u1", () -> ActivationService.get().create(job, ActivationRequest.Action.ACTIVATE, "pending", List.of("a1")));
            String id = as("u1", () -> ActivationService.get().create(job, ActivationRequest.Action.ACTIVATE, "cancelled", List.of("a1")).getId());
            as("u1", () -> {
                ActivationService.get().cancel(id);
                return null;
            });
        }
        check(j, "batch-control/activations/");
    }

    private static void check(JenkinsRule j, String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        wc.getOptions().setCssEnabled(false);
        wc.login("admin");
        String url = path + "?pendingPage=2";
        Page p = wc.getPage(new URL(j.getURL(), url));
        String body = p.getWebResponse().getContentAsString();
        String tail = body.length() > 600 ? body.substring(body.length() - 600) : body;
        assertEquals(200, p.getWebResponse().getStatusCode(), "GET " + url + " must answer 200");
        assertTrue(p instanceof HtmlPage, "GET " + url + " must render HTML");
        // Complete: both lists rendered and the document closed (a cut-off page ends inside the first table).
        assertTrue(body.contains("data-batch-control-list=\"pending\"") && body.contains("data-batch-control-list=\"ended\""),
                "GET " + url + " must render both lists; the page ends with: " + tail);
        assertTrue(body.trim().toLowerCase().endsWith("</html>"), "GET " + url + " must be complete; the page ends with: " + tail);
        // The pager renders, and the ended list's link keeps pendingPage=2 (#45).
        boolean endedLink = false;
        for (HtmlAnchor a : ((HtmlPage) p).getAnchors()) {
            String href = a.getHrefAttribute();
            if (href == null || href.isEmpty() || href.startsWith("#")) {
                continue;
            }
            Map<String, String> q = query(((HtmlPage) p).getFullyQualifiedUrl(href));
            if ("2".equals(q.get("endedPage"))) {
                endedLink = true;
                assertEquals("2", q.get("pendingPage"), "on " + url + " the ended pager link " + href + " must keep pendingPage=2");
            }
        }
        assertTrue(endedLink, "GET " + url + " must render the ended list's pager link to endedPage=2");
    }

    private static Map<String, String> query(URL url) {
        Map<String, String> out = new LinkedHashMap<>();
        String q = url.getQuery();
        if (q == null || q.isEmpty()) {
            return out;
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8),
                    eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static <T> T as(String id, Callable<T> body) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(id, true).impersonate2())) {
            return body.call();
        }
    }
}
