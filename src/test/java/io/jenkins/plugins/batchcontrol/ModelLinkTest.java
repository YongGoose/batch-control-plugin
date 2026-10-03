package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hosting review round 3, R4-11: links to jobs and runs on Batch Control pages carry the class
 * {@code model-link} (core's context-menu hook). Matrix rows T-UI-93, T-UI-94 (note 241).
 *
 * <p>A job or run link is an anchor inside {@code #main-panel} (outside the tab bar) whose path
 * is exactly {@code job/ml-job/} or {@code job/ml-job/1/} (trailing slash ignored). Each page
 * must offer at least one such link (the run-link rule of SPEC #22 / D-44 makes the request
 * detail, history and dashboard link the run for a reader) and every one of them must carry
 * {@code model-link}. SPEC does not pin the request list's columns (matrix note 27), so on the
 * list the class is required of every job/run link it offers without requiring that it offers one.
 *
 * <p>Written from docs/SPEC.md, the review checklist (R4-11) and docs/TEST-MATRIX.md only (no
 * src/main knowledge).
 */
@WithJenkins
public class ModelLinkTest {

    private JenkinsRule j;
    private RunRequest request;

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
        FreeStyleProject job = j.createFreeStyleProject("ml-job");
        setBatchControl(job, new BatchControlJobProperty(true));
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            request = RunRequestService.get().create(job, new LinkedHashMap<>(), "month-end run", "a1");
        }
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            RunRequestService.get().approve(request.getId(), "ok");
        }
        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size(), "fixture: the approved run must have executed");
    }

    /** T-UI-93: the request list and the request detail page. */
    @Test
    public void t_ui_93_requestPagesMarkJobAndRunLinksAsModelLinks() throws Exception {
        assertModelLinks("batch-control/requests/", false, false);
        assertModelLinks("batch-control/requests/" + request.getId() + "/", true, true);
    }

    /** T-UI-94: the run dashboard and the history runs screen. */
    @Test
    public void t_ui_94_dashboardAndHistoryMarkJobAndRunLinksAsModelLinks() throws Exception {
        assertModelLinks("batch-control/dashboard/", true, true);
        assertModelLinks("batch-control/history/", true, true);
    }

    /** Every job/run link of the page carries model-link; when {@code required}, at least one exists, and a run link when {@code runLink}. */
    private void assertModelLinks(String path, boolean required, boolean runLink) throws Exception {
        HtmlPage page = UsabilityFixtures.htmlPage(j, "admin", path);
        assertEquals(200, page.getWebResponse().getStatusCode(), "premise: admin opens " + path);
        DomElement main = page.getElementById("main-panel");
        assertTrue(main != null, path + ": the page must have a main panel");
        String jobUrl = UsabilityFixtures.stripQueryAndSlash(j.getURL() + "job/ml-job/");
        String runUrl = UsabilityFixtures.stripQueryAndSlash(j.getURL() + "job/ml-job/1/");
        List<String> missing = new ArrayList<>();
        int found = 0;
        boolean foundRun = false;
        for (DomElement a : main.getElementsByTagName("a")) {
            if (insideTabBar(a) || !a.hasAttribute("href")) {
                continue;
            }
            String href = a.getAttribute("href");
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:")) {
                continue;
            }
            String target = UsabilityFixtures.stripQueryAndSlash(page.getFullyQualifiedUrl(href).toExternalForm());
            if (!target.equals(jobUrl) && !target.equals(runUrl)) {
                continue;
            }
            found++;
            foundRun |= target.equals(runUrl);
            List<String> classes = Arrays.asList(a.getAttribute("class").trim().split("\\s+"));
            if (!classes.contains("model-link")) {
                missing.add(href + " (class='" + a.getAttribute("class") + "')");
            }
        }
        assertTrue(!required || found > 0, path + ": premise: the page must link the job or its run; anchors were "
                + UsabilityFixtures.resolvedHrefs(page));
        if (runLink) {
            assertTrue(foundRun, path + ": premise: the page must link the run (SPEC #22, D-44)");
        }
        assertTrue(missing.isEmpty(), path + ": every job/run link must carry class model-link (R4-11), these do not: " + missing);
    }

    private static boolean insideTabBar(DomNode node) {
        for (DomNode n = node; n != null; n = n.getParentNode()) {
            if (n instanceof DomElement e && e.hasAttribute("data-batch-control-tabs")) {
                return true;
            }
        }
        return false;
    }
}
