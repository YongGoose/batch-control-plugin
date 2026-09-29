package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6, "a run shown on any Batch Control screen links to its build page only when the
 * viewer has {@code Item/Read} on the job; otherwise it is plain text. The rule is the same on
 * every screen, including history, and is defined once. (#22)", with D-44. Matrix row T-06-51
 * (note 87); settles the question note 49 left open.
 *
 * <p>One failing approved run of {@code link-j} appears on five screens: the request detail, the
 * incident detail and list, the history screen and the dashboard. {@code u1} (the requester)
 * holds Item/Read on the job; {@code a2} (the designated approver, #26) holds Approve and
 * ViewHistory but no Item/Read. The same run is a link to {@code job/link-j/1/} for u1 and plain
 * text for a2 on every screen.
 *
 * Written from docs/SPEC.md, docs/DECISIONS.md D-44, issue #22 and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class RunLinkRuleTest {

    private static final String JOB = "link-j";
    private static final String BUILD_HREF = "job/" + JOB + "/1";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a2"));
        cfg.save();
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.VIEW_HISTORY).everywhere().to("u1")
                .grant(Jenkins.READ, BatchControlPermissions.APPROVE,
                        BatchControlPermissions.VIEW_HISTORY).everywhere().to("a2"));
    }

    /**
     * T-06-51 (#22, D-44): the same run is a build-page link for a viewer with Item/Read and
     * plain text for a viewer without it, on the request, incident, history and dashboard
     * screens.
     */
    @Test
    public void t_06_51_runIsLinkedOnlyForViewersWhoCanReadTheJob() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(JOB);
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(job, property);
        job.getBuildersList().add(new FailureBuilder());

        try (ACLContext ignored = as("a2")) {
            assertFalse(job.hasPermission(Item.READ), "fixture: a2 must not hold Item/Read on " + JOB);
        }
        try (ACLContext ignored = as("u1")) {
            assertTrue(job.hasPermission(Item.READ), "fixture: u1 must hold Item/Read on " + JOB);
        }

        RunRequest request;
        try (ACLContext ignored = as("u1")) {
            request = RunRequestService.get().create(job, new LinkedHashMap<>(), "link rule run", "a2");
        }
        try (ACLContext ignored = as("a2")) {
            RunRequestService.get().approve(request.getId(), "ok");
        }
        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size(), "fixture: the approved run must have executed once");

        Incident incident = IncidentService.get().list(YearMonth.now()).stream()
                .filter(i -> (JOB + "#1").equals(i.getRunId())).findFirst().orElse(null);
        assertNotNull(incident, "fixture: the failed run must have opened an incident");

        String[] linkedScreens = {
            "batch-control/requests/" + request.getId() + "/",
            "batch-control/incidents/" + incident.getId() + "/",
            "batch-control/history/",
            "batch-control/dashboard/"};
        String[] plainTextOnly = {"batch-control/incidents/"};

        for (String path : linkedScreens) {
            HtmlPage reader = page("u1", path);
            assertFalse(buildLinks(reader).isEmpty(), path + ": a viewer with Item/Read must get a link to the build page"
                    + " (" + BUILD_HREF + "/); anchors were " + hrefs(reader));

            HtmlPage blind = page("a2", path);
            assertTrue(buildLinks(blind).isEmpty(), path + ": a viewer without Item/Read must see the run as plain text,"
                    + " not a link; build anchors were " + buildLinks(blind));
            assertTrue(blind.asNormalizedText().contains(JOB), path + ": the run must still be shown (as text) to the viewer"
                    + " without Item/Read: " + excerpt(blind.asNormalizedText()));
        }
        for (String path : plainTextOnly) {
            HtmlPage blind = page("a2", path);
            assertTrue(buildLinks(blind).isEmpty(), path + ": a viewer without Item/Read must not get a build link;"
                    + " build anchors were " + buildLinks(blind));
        }
    }

    // ---------------------------------------------------------------- helpers

    private HtmlPage page(String user, String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = (HtmlPage) wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET));
        assertEquals(200, page.getWebResponse().getStatusCode(), user + " GET " + path);
        return page;
    }

    private static List<String> buildLinks(HtmlPage page) {
        return page.getAnchors().stream().map(HtmlAnchor::getHrefAttribute)
                .filter(href -> href.contains(BUILD_HREF + "/") || href.endsWith(BUILD_HREF))
                .collect(Collectors.toList());
    }

    private static List<String> hrefs(HtmlPage page) {
        return page.getAnchors().stream().map(HtmlAnchor::getHrefAttribute).collect(Collectors.toList());
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private static String excerpt(String text) {
        return text.length() > 1500 ? text.substring(0, 1500) + "..." : text;
    }
}
