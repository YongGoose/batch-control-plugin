package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Screen contract (e2e-03 DEF-06, checklist A-11): the activation request form
 * ({@code job/<name>/batch-control-activation/}) is its own screen, so its last breadcrumb names
 * the activation and is not the run request form's "Request Run". Matrix row T-UI-22 (note 121).
 *
 * <p>Breadcrumbs are read from core's breadcrumb list items
 * ({@code li.jenkins-breadcrumbs__list-item}). Written from docs/SPEC.md,
 * docs/reports/e2e-03.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ActivationBreadcrumbTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-UI-22 (DEF-06): the activation form's last breadcrumb is not "Request Run" and names the
     * activation; the crumb bar is present (so a page without crumbs cannot pass).
     */
    @Test
    public void t_ui_22_activationFormLastBreadcrumbIsNotRequestRun() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("crumb-x");
        setBatchControl(job, new BatchControlJobProperty(true));

        List<String> crumbs = crumbs("u1", job.getUrl() + "batch-control-activation/");
        assertFalse(crumbs.isEmpty(), "the activation form must render breadcrumbs");
        String last = crumbs.get(crumbs.size() - 1);
        assertTrue(crumbs.stream().anyMatch(c -> c.equals("crumb-x")), "the breadcrumbs must name the job: " + crumbs);
        assertFalse(last.equalsIgnoreCase("Request Run"), "the activation form's last breadcrumb must not be the run"
                + " request form's 'Request Run': " + crumbs);
        assertTrue(last.toLowerCase(Locale.ROOT).contains("activat"), "the activation form's last breadcrumb must name"
                + " the activation: " + crumbs);
    }

    /**
     * T-UI-24 (e2e-03 DEF-06, still open in e2e-run3 A-11): no breadcrumb of the activation form
     * leads to the run request form ({@code job/<name>/batch-control/}) or is captioned "Request
     * Run" — the activation is its own screen, reached from the job (note 148). The job crumb must
     * be present and link the job, so a page without crumbs cannot pass.
     */
    @Test
    public void t_ui_24_activationBreadcrumbsDoNotLeadToTheRunRequestForm() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("crumb-y");
        setBatchControl(job, new BatchControlJobProperty(true));

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("u1");
        String path = job.getUrl() + "batch-control-activation/";
        HtmlPage page = (HtmlPage) wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET));
        assertEquals(200, page.getWebResponse().getStatusCode(), "u1 must reach " + path);
        List<org.htmlunit.html.HtmlAnchor> anchors = page.getByXPath("//li[contains(concat(' ',"
                + " normalize-space(@class), ' '), ' jenkins-breadcrumbs__list-item ')]//a[@href]");
        String jobUrl = UsabilityFixtures.stripQueryAndSlash(new URL(j.getURL(), job.getUrl()).toExternalForm());
        String requestForm = UsabilityFixtures.stripQueryAndSlash(
                new URL(j.getURL(), job.getUrl() + "batch-control").toExternalForm());
        boolean jobCrumb = false;
        for (org.htmlunit.html.HtmlAnchor a : anchors) {
            String target = UsabilityFixtures.stripQueryAndSlash(page.getFullyQualifiedUrl(a.getHrefAttribute())
                    .toExternalForm());
            jobCrumb |= target.equals(jobUrl);
            assertFalse(target.equals(requestForm), "no breadcrumb of the activation form may lead to the run request"
                    + " form: '" + a.asNormalizedText() + "' -> " + a.getHrefAttribute());
            assertFalse(a.asNormalizedText().trim().equalsIgnoreCase("Request Run"), "no breadcrumb of the activation"
                    + " form may be captioned 'Request Run': -> " + a.getHrefAttribute());
        }
        assertTrue(jobCrumb, "fixture: the breadcrumbs must link the job " + jobUrl + ", read: " + anchors.size()
                + " crumb links");
    }

    private List<String> crumbs(String userId, String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        HtmlPage page = (HtmlPage) wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET));
        assertEquals(200, page.getWebResponse().getStatusCode(), userId + " must reach " + path);
        List<DomElement> items = page.getByXPath("//li[contains(concat(' ', normalize-space(@class), ' '),"
                + " ' jenkins-breadcrumbs__list-item ')]");
        return items.stream().map(e -> e.asNormalizedText().trim()).filter(t -> !t.isEmpty())
                .collect(Collectors.toList());
    }
}
