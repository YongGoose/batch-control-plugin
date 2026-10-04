package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import jenkins.model.experimentalflags.UserExperimentalFlagsProperty;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The job activation request page ({@code job/<name>/batch-control-activation/}) follows the
 * layout of the job's other Batch Control sub-page, Request Run ({@code job/<name>/batch-control/}),
 * on both job UIs. Matrix rows T-UI-110/111 (note 252, e2e-14 DEF-08).
 *
 * <p>e2e-14 DEF-08: a user with core's new job page on saw the job's classic side panel on the
 * activation page, with core's URL-less new-job-page actions rendered as dead
 * {@code a.task-link} entries with {@code href=""} ("Delete Project", "Permalinks"). The job UI
 * is chosen per user through core's {@link UserExperimentalFlagsProperty} key
 * {@code new-job-page.flag}. Each row first asserts the same layout on the Request Run page as the
 * reference, so the activation page is held to what its sibling already does.
 *
 * <p>Written from docs/SPEC.md items 6/6a, docs/TEST-MATRIX.md and the e2e-14 report only (no
 * src/main knowledge).
 */
@WithJenkins
public class ActivationPageLayoutTest {

    private static final String FLAG = "new-job-page.flag";

    private JenkinsRule j;

    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST)
                        .everywhere().to("newui", "classic")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        job = j.createFreeStyleProject("layout-x");
        BatchControlJobProperty cleared = new BatchControlJobProperty(true);
        cleared.setBlockTimer(false);
        cleared.setBlockUpstream(false);
        setBatchControl(job, cleared);
        assertFalse(isActivated(job), "premise: a job created after the first start is not activated");

        User.getById("newui", true).addProperty(new UserExperimentalFlagsProperty(Map.of(FLAG, "true")));
        User.getById("classic", true).addProperty(new UserExperimentalFlagsProperty(Map.of(FLAG, "false")));
    }

    /**
     * T-UI-110 (P2): new job page on. Request Run and the activation page both answer 200, have
     * no {@code #side-panel}, show core's {@code div.app-build-bar}, and carry no
     * {@code a.task-link} with an empty href.
     */
    @Test
    public void t_ui_110_activationPageFollowsNewJobPageLayout() throws Exception {
        for (String sub : new String[] {"batch-control/", "batch-control-activation/"}) {
            String what = sub.equals("batch-control/") ? "Request Run page (reference)" : "activation page";
            HtmlPage page = open("newui", job.getUrl() + sub, what);
            assertNull(page.querySelector("#side-panel"),
                    "the " + what + " must not render the job's classic side panel for a new-job-page user");
            assertNotNull(page.querySelector("div.app-build-bar"),
                    "the " + what + " must show the new job page's app bar for a new-job-page user");
            assertNoDeadTaskLink(page, what + " (new job page)");
        }
    }

    /**
     * T-UI-111 (P2): new job page off (classic). Request Run and the activation page both answer
     * 200, keep {@code #side-panel}, and carry no {@code a.task-link} with an empty href.
     */
    @Test
    public void t_ui_111_activationPageKeepsClassicSidePanel() throws Exception {
        for (String sub : new String[] {"batch-control/", "batch-control-activation/"}) {
            String what = sub.equals("batch-control/") ? "Request Run page (reference)" : "activation page";
            HtmlPage page = open("classic", job.getUrl() + sub, what);
            assertNotNull(page.querySelector("#side-panel"),
                    "the " + what + " must keep the job's side panel for a classic-page user");
            assertNoDeadTaskLink(page, what + " (classic)");
        }
    }

    private HtmlPage open(String user, String path, String what) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withJavaScriptEnabled(false)
                .withThrowExceptionOnFailingStatusCode(false);
        wc.login(user);
        HtmlPage page = wc.goTo(path);
        assertEquals(200, page.getWebResponse().getStatusCode(), user + " must be able to open the " + what + " " + path);
        return page;
    }

    private static void assertNoDeadTaskLink(HtmlPage page, String what) {
        List<String> dead = new ArrayList<>();
        for (DomNode node : page.querySelectorAll("a.task-link")) {
            HtmlElement link = (HtmlElement) node;
            if (link.getAttribute("href").trim().isEmpty()) {
                dead.add(link.getTextContent().trim());
            }
        }
        assertTrue(dead.isEmpty(), "the " + what + " must not contain a.task-link with an empty href, found " + dead);
    }
}
