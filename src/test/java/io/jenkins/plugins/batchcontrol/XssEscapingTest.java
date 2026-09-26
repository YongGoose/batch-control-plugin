package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.FreeStyleProject;
import hudson.model.StringParameterDefinition;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import jenkins.model.Jenkins;
import org.htmlunit.CollectingAlertHandler;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC section 6 output-escaping requirement (R-3, D-18). Matrix row T-RT-10:
 * stored XSS payloads in the request reason and parameter values must render as
 * escaped text only — no script execution, no element injection — on the request
 * list, the request detail (approval) screen and the dashboard.
 *
 * Job-name payloads are not creatable: Jenkins core rejects {@code <} and {@code >}
 * in item names (checkGoodName), so that part of the row is unreachable by design
 * (matrix note 27).
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class XssEscapingTest {

    private static final String SCRIPT_PAYLOAD = "<script>alert('xss-reason')</script>";
    private static final String IMG_PAYLOAD = "<img src=x onerror=alert('xss-param')>";

    private JenkinsRule j;

    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to("a1"));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        job = j.createFreeStyleProject("xss-x");
        job.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("P1", "safe")));
        job.addProperty(new BatchControlJobProperty(true));
    }

    /**
     * T-RT-10: reason and parameter payloads are escaped on the request list, the
     * request detail screen and the dashboard; nothing executes and no element is
     * injected into the DOM.
     */
    @Test
    public void t_rt_10_storedXssPayloadsAreEscapedOnEveryRenderSurface() throws Exception {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("P1", IMG_PAYLOAD);
        RunRequest request;
        try (ACLContext ignored = as("u1")) {
            request = RunRequestService.get().create(job, parameters, SCRIPT_PAYLOAD, "a1");
        }
        // run the approved build so the dashboard has a RunRecord carrying the payload params
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(request.getId(), "reviewed the raw parameters");
        }
        j.waitUntilNoActivity();

        JenkinsRule.WebClient wc = j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false);
        CollectingAlertHandler alerts = new CollectingAlertHandler();
        wc.setAlertHandler(alerts);
        wc.login("admin");

        // surfaces that must show the stored values: detail (approval review) and dashboard
        HtmlPage detail = page(wc, "batch-control/requests/" + request.getId() + "/");
        assertRenderedEscaped("request detail", detail, true);
        HtmlPage dashboard = page(wc, "batch-control/dashboard/");
        assertNoUnescapedPayload("dashboard", dashboard);
        assertTrue(dashboard.getWebResponse().getContentAsString()
                        .contains("&lt;img src=x onerror=alert"), "the dashboard must render the parameter value as escaped text");
        assertNoInjectedElement("dashboard", dashboard);

        // the request list must at minimum never carry the payload unescaped
        HtmlPage list = page(wc, "batch-control/requests/");
        assertNoUnescapedPayload("request list", list);
        assertNoInjectedElement("request list", list);

        assertTrue(alerts.getCollectedAlerts().isEmpty(), "no injected script may have executed on any surface, but alerts fired: "
                + alerts.getCollectedAlerts());
    }

    // ---------------------------------------------------------------- helpers

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private HtmlPage page(JenkinsRule.WebClient wc, String relative) throws Exception {
        HtmlPage page = wc.getPage(new URL(j.getURL(), relative));
        assertTrue(page.getWebResponse().getStatusCode() < 300, relative + " must render (2xx), got "
                + page.getWebResponse().getStatusCode());
        return page;
    }

    /** The surface shows the values: escaped forms present, unescaped forms absent. */
    private static void assertRenderedEscaped(String surface, HtmlPage page,
            boolean expectReason) {
        assertNoUnescapedPayload(surface, page);
        String html = page.getWebResponse().getContentAsString();
        if (expectReason) {
            // the opening '<' is what must be neutralized; Jelly's default escaping
            // leaves '>' raw, so both escaped variants are accepted
            assertTrue(html.contains("&lt;script&gt;alert") || html.contains("&lt;script>alert"), surface + " must render the reason as escaped text");
        }
        assertTrue(html.contains("&lt;img src=x onerror=alert"), surface + " must render the parameter value as escaped text");
        assertNoInjectedElement(surface, page);
    }

    /** The raw HTML never contains the payloads with a live {@code <}. */
    private static void assertNoUnescapedPayload(String surface, HtmlPage page) {
        String html = page.getWebResponse().getContentAsString();
        assertFalse(html.contains("<script>alert"), surface + " must not contain the unescaped script payload");
        assertFalse(html.contains("<img src=x onerror"), surface + " must not contain the unescaped img payload");
    }

    /** DOM-level check: no script element with the payload body, no img with src=x. */
    private static void assertNoInjectedElement(String surface, HtmlPage page) {
        for (DomElement script : page.getElementsByTagName("script")) {
            String body = script.getTextContent();
            assertFalse(body != null && (body.contains("xss-reason") || body.contains("xss-param")), surface + " must not carry an injected script element");
        }
        for (DomElement img : page.getElementsByTagName("img")) {
            assertFalse("x".equals(img.getAttribute("src")), surface + " must not carry an injected img element");
        }
    }
}
