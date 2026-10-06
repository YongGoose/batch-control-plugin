package io.jenkins.plugins.batchcontrol;

import hudson.model.AdministrativeMonitor;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import org.htmlunit.Page;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hosting review UI polish (68b0f80), automated evidence for checklist items that only had e2e
 * coverage. Matrix rows T-UI-50..52 (note 202):
 * <ul>
 *   <li>Batch Control's administrative monitors and the approval-required refusal page
 *   (ApprovalRequiredFailure) render their alert text without a leading {@code <p>} (core's alert
 *   padding then holds);</li>
 *   <li>the Cancel (request detail), Revoke (active grants) and Revert (strategy uninstall)
 *   controls carry core's {@code jenkins-!-destructive-color} class.</li>
 * </ul>
 * Written from the coordinator's checklist and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class PolishMarkupTest {

    static final String DESTRUCTIVE = "jenkins-!-destructive-color";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-UI-50: every active Batch Control administrative monitor on {@code /manage} (the strategy
     * monitor under a plain matrix strategy, the configure-without-grant monitor for a native
     * Configure holder) has no {@code <p>} as the first element of its message.
     */
    @Test
    public void t_ui_50_monitorsHaveNoLeadingParagraph() throws Exception {
        ProjectMatrixAuthorizationStrategy plain = StrategyFixtures.matrix(new ProjectMatrixAuthorizationStrategy());
        j.jenkins.setAuthorizationStrategy(plain);
        StrategyFixtures.changeControlOn();
        List<String> ids = new ArrayList<>();
        for (AdministrativeMonitor m : AdministrativeMonitor.all()) {
            if (m.getClass().getName().startsWith("io.jenkins.plugins.batchcontrol.") && m.isEnabled() && m.isActivated()) {
                ids.add(m.id);
            }
        }
        assertTrue(ids.contains(StrategyFixtures.MONITOR_ID), "premise: the strategy monitor is active, got " + ids);
        HtmlPage manage = page("admin", "manage/");
        for (String id : ids) {
            DomElement monitor = manage.querySelector("[data-monitor-id='" + id + "']");
            assertNotNull(monitor, "monitor " + id + " must be rendered on /manage");
            DomElement first = firstMessageElement(monitor);
            assertTrue(first == null || !"p".equalsIgnoreCase(first.getTagName()),
                    "monitor " + id + " must not start its message with <p>: " + excerpt(monitor.asXml()));
        }
    }

    /** T-UI-51: the approval-required refusal page's alert has no leading {@code <p>}. */
    @Test
    public void t_ui_51_approvalRequiredFailureAlertHasNoLeadingParagraph() throws Exception {
        BatchControlMatrixAuthorizationStrategy s = StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy());
        s.add(Item.BUILD, PermissionEntry.user("bob"));
        s.add(BatchControlPermissions.REQUEST, PermissionEntry.user("bob"));
        j.jenkins.setAuthorizationStrategy(s);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        FreeStyleProject job = j.createFreeStyleProject("batch-x");
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "bob");
        wc.getOptions().setJavaScriptEnabled(false);
        Page refused = wc.getPage(new org.htmlunit.WebRequest(wc.createCrumbedUrl(job.getUrl() + "build?delay=0sec"),
                org.htmlunit.HttpMethod.POST));
        assertTrue(refused.getWebResponse().getStatusCode() >= 400, "premise: Build Now is refused");
        assertTrue(refused instanceof HtmlPage, "premise: a browser gets an HTML refusal page");
        List<DomElement> alerts = new ArrayList<>();
        ((HtmlPage) refused).querySelectorAll(".jenkins-alert").forEach(n -> alerts.add((DomElement) n));
        assertFalse(alerts.isEmpty(), "the refusal must render a jenkins-alert: " + excerpt(refused.getWebResponse().getContentAsString()));
        for (DomElement alert : alerts) {
            DomElement first = alert.getFirstElementChild();
            assertTrue(first == null || !"p".equalsIgnoreCase(first.getTagName()),
                    "the refusal alert must not start with <p>: " + excerpt(alert.asXml()));
        }
    }

    /**
     * T-UI-52: Cancel on the requester's own pending run request, Revoke on an active grant and the
     * Revert (uninstall) control on the configuration page carry {@code jenkins-!-destructive-color}.
     */
    @Test
    public void t_ui_52_destructiveControlsCarryDestructiveColor() throws Exception {
        BatchControlMatrixAuthorizationStrategy s = StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy());
        s.add(BatchControlPermissions.REQUEST, PermissionEntry.user("bob"));
        s.add(Item.BUILD, PermissionEntry.user("bob")); // a run request needs Item/Build on this branch (D-38)
        j.jenkins.setAuthorizationStrategy(s);
        BatchControlGlobalConfiguration cfg = StrategyFixtures.changeControlOn();
        cfg.setRunControlEnabled(true);
        cfg.save();
        FreeStyleProject job = j.createFreeStyleProject("batch-x");
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
        RunRequest request;
        try (ACLContext ignored = ACL.as2(User.getById("bob", true).impersonate2())) {
            request = RunRequestService.get().create(job, new LinkedHashMap<>(), "month-end run", "a1");
        }
        StrategyFixtures.grant("carol", "batch-x", Arrays.asList(GrantAction.CONFIGURE));

        assertDestructive(page("bob", "batch-control/requests/" + request.getId() + "/"), "/cancel", "Cancel on the request");
        assertDestructive(page("admin", "batch-control/grants/"), "/revoke", "Revoke on the active grant");
        HtmlPage config = null;
        for (String path : new String[] {"manage/batch-control-configuration/", "manage/configure"}) {
            HtmlPage p = page("admin", path);
            if (control(p, "/revert") != null) {
                config = p;
                break;
            }
        }
        assertNotNull(config, "the Revert control must be offered while a Batch Control strategy is installed");
        assertDestructive(config, "/revert", "Revert of the Batch Control strategy");
    }

    // ---------------------------------------------------------------- helpers

    private HtmlPage page(String user, String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        wc.login(user);
        Page p = wc.goTo(path);
        assertTrue(p instanceof HtmlPage && p.getWebResponse().getStatusCode() == 200,
                user + " GET /" + path + " must render, got " + p.getWebResponse().getStatusCode());
        return (HtmlPage) p;
    }

    /** First element of a monitor's message: inside its content, skipping the controls container. */
    private static DomElement firstMessageElement(DomElement monitor) {
        DomElement content = monitor.querySelector(".app-adminmonitor-content");
        DomElement scope = content != null ? content : monitor;
        for (DomElement child : scope.getChildElements()) {
            String cls = child.getAttribute("class");
            if (cls.contains("app-adminmonitor-controls") || "svg".equalsIgnoreCase(child.getTagName())) {
                continue;
            }
            return child;
        }
        return null;
    }

    /** The clickable control for an endpoint ending in {@code suffix}: link, data-url button or form submit. */
    private static HtmlElement control(HtmlPage page, String suffix) {
        for (HtmlElement e : page.getHtmlElementDescendants()) {
            if (e.getAttribute("href").endsWith(suffix) || e.getAttribute("data-url").endsWith(suffix)
                    || e.getAttribute("formaction").endsWith(suffix)) {
                return e;
            }
            if (e instanceof HtmlForm f && f.getActionAttribute().endsWith(suffix)) {
                for (DomNode n : f.querySelectorAll("button, input[type=submit]")) {
                    return (HtmlElement) n;
                }
            }
        }
        return null;
    }

    private static void assertDestructive(HtmlPage page, String suffix, String what) {
        HtmlElement c = control(page, suffix);
        assertNotNull(c, what + ": the control for '" + suffix + "' must be on the page: " + excerpt(page.asNormalizedText()));
        assertTrue(c.getAttribute("class").contains(DESTRUCTIVE),
                what + " must carry " + DESTRUCTIVE + ": " + excerpt(c.asXml()));
    }
}
