package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Items;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Recorded history names who did what (for example who cancelled a request and why a request
 * was invalidated)" (SPEC 6 usability line), SPEC 7 and SPEC 12. Matrix rows T-07-08 (e2e-03
 * DEF-13, note 134), T-07-09 (DEF-17, note 135) and T-12-12 (DEF-22, note 136).
 *
 * <p>Written from docs/SPEC.md, docs/reports/e2e-03.md and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class HistoryAttributionTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.VIEW_HISTORY).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.MANAGE,
                        BatchControlPermissions.VIEW_HISTORY).everywhere().to("mgr7")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-07-08 (DEF-13): a Manage holder cancels a requester's PENDING request through the web
     * endpoint; the request names the canceller and the time (decidedBy, decidedAt), the request
     * screen shows the canceller, and requests.csv carries it in decidedBy. Twin: the requester's
     * own cancellation names the requester.
     */
    @Test
    public void t_07_08_cancellationNamesWhoCancelled() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("cancel-x");
        setBatchControl(job, new BatchControlJobProperty(true));
        String byManager = create(job, "cancelled by the manager");
        String byRequester = create(job, "cancelled by the requester");

        assertTrue(cancel("mgr7", byManager) < 400, "fixture: the Manage holder may cancel (SPEC 7)");
        assertTrue(cancel("u1", byRequester) < 400, "fixture: the requester may cancel");

        RunRequest managerCancelled = RunRequestService.get().load(byManager);
        assertEquals(RequestStatus.CANCELLED, managerCancelled.getStatus(), "fixture: CANCELLED");
        assertEquals("mgr7", managerCancelled.getDecidedBy(), "the cancellation must name who cancelled");
        assertNotNull(managerCancelled.getDecidedAt(), "the cancellation must carry its time");
        RunRequest selfCancelled = RunRequestService.get().load(byRequester);
        assertEquals("u1", selfCancelled.getDecidedBy(), "twin: the requester's own cancellation names the requester");
        assertNotNull(selfCancelled.getDecidedAt());

        String screen = UsabilityFixtures.htmlPage(j, "u1", "batch-control/requests/" + byManager + "/").asNormalizedText();
        assertTrue(screen.contains("mgr7"), "the request screen must show who cancelled the request: " + excerpt(screen));

        WebResponse csv = ApproverFormFixtures.get(j, "admin", "batch-control/history/requests.csv");
        assertEquals(200, csv.getStatusCode());
        List<List<String>> rows = ApproverCsvExportTest.parse(csv.getContentAsString());
        int decidedBy = column(rows.get(0), "decidedBy");
        assertEquals("mgr7", rowOf(rows, byManager).get(decidedBy).trim(), "requests.csv must name the canceller in decidedBy");
        assertEquals("u1", rowOf(rows, byRequester).get(decidedBy).trim(), "twin: requests.csv names the requester who cancelled");
    }

    /**
     * T-07-09 (DEF-17): an INVALIDATED request says why on its screen — the job was renamed (old
     * and new name) or moved (the new full name). Control: a PENDING request on an untouched job
     * shows no such reason.
     */
    @Test
    public void t_07_09_invalidatedRequestSaysWhy() throws Exception {
        FreeStyleProject renamed = j.createFreeStyleProject("inv-job");
        setBatchControl(renamed, new BatchControlJobProperty(true));
        FreeStyleProject moved = j.createFreeStyleProject("inv-move");
        setBatchControl(moved, new BatchControlJobProperty(true));
        FreeStyleProject untouched = j.createFreeStyleProject("inv-still");
        setBatchControl(untouched, new BatchControlJobProperty(true));
        Folder team = j.jenkins.createProject(Folder.class, "team");

        String renamedId = create(renamed, "renamed later");
        String movedId = create(moved, "moved later");
        String untouchedId = create(untouched, "stays pending");
        renamed.renameTo("inv-job-renamed");
        Items.move(moved, team);
        assertEquals(RequestStatus.INVALIDATED, RunRequestService.get().load(renamedId).getStatus(), "fixture: rename invalidates (SPEC 7)");
        assertEquals(RequestStatus.INVALIDATED, RunRequestService.get().load(movedId).getStatus(), "fixture: move invalidates (SPEC 7)");

        String renameScreen = UsabilityFixtures.htmlPage(j, "u1", "batch-control/requests/" + renamedId + "/").asNormalizedText();
        assertTrue(Pattern.compile("(?i)renam").matcher(renameScreen).find() && renameScreen.contains("inv-job-renamed"),
                "the INVALIDATED request must say the job was renamed and name the new name: " + excerpt(renameScreen));
        String moveScreen = UsabilityFixtures.htmlPage(j, "u1", "batch-control/requests/" + movedId + "/").asNormalizedText();
        assertTrue(Pattern.compile("(?i)mov").matcher(moveScreen).find() && moveScreen.contains("team/inv-move"),
                "the INVALIDATED request must say the job was moved and name where to: " + excerpt(moveScreen));
        String control = UsabilityFixtures.htmlPage(j, "u1", "batch-control/requests/" + untouchedId + "/").asNormalizedText();
        assertFalse(Pattern.compile("(?i)renamed or moved|was renamed|was moved").matcher(control).find(),
                "control: a PENDING request on an untouched job gives no invalidation reason: " + excerpt(control));
    }

    /**
     * T-12-12 (DEF-22): the monthly summary a person opens from the history screen (a link captioned "summary" or
     * the history screen's link to {@code history/summary}) labels its
     * counts in words (SPEC 12: runs, success/failure/unstable, incidents open/resolved, requests
     * approved/rejected), not with the raw field keys. The machine-readable contract of T-12-04
     * (note 23) is untouched; this row reads the screen the history page links.
     */
    @Test
    public void t_12_12_monthlySummaryScreenUsesWordsNotFieldKeys() throws Exception {
        HtmlPage history = UsabilityFixtures.htmlPage(j, "viewer", "batch-control/history/");
        List<HtmlAnchor> links = new ArrayList<>(UsabilityFixtures.anchorsCaptioned(history, Pattern.compile("(?i)summary")));
        for (HtmlAnchor a : history.getAnchors()) {
            String href = a.getHrefAttribute();
            if (href != null && UsabilityFixtures.stripQueryAndSlash(history.getFullyQualifiedUrl(href).toExternalForm())
                    .endsWith("/batch-control/history/summary")) {
                links.add(a);
            }
        }
        assertFalse(links.isEmpty(), "the history screen must link the monthly summary; anchors were " + UsabilityFixtures.resolvedHrefs(history));
        JenkinsRule.WebClient wc = UsabilityFixtures.clientNoJs(j, "viewer");
        Page summary = wc.getPage(new WebRequest(history.getFullyQualifiedUrl(links.get(0).getHrefAttribute()), HttpMethod.GET));
        assertEquals(200, summary.getWebResponse().getStatusCode(), "the monthly summary must open for a ViewHistory holder");
        assertTrue(summary instanceof HtmlPage, "the summary a person opens must be a screen (HTML), got " + summary.getWebResponse().getContentType());
        String text = ((HtmlPage) summary).asNormalizedText();
        for (String key : new String[] {"incidentsOpen", "incidentsResolved", "requestsApproved", "requestsRejected"}) {
            assertFalse(text.contains(key), "the summary screen must not label a count with the raw field key " + key + ": " + excerpt(text));
        }
        String lower = text.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("runs") && lower.contains("approved") && lower.contains("rejected") && lower.contains("incident"),
                "the summary screen must label the SPEC 12 counts in words: " + excerpt(text));
    }

    // ---------------------------------------------------------------- helpers

    private String create(FreeStyleProject job, String reason) {
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            return RunRequestService.get().create(job, new LinkedHashMap<>(), reason, "a1").getId();
        }
    }

    private int cancel(String userId, String id) throws Exception {
        JenkinsRule.WebClient wc = UsabilityFixtures.client(j, userId);
        return wc.getPage(new WebRequest(wc.createCrumbedUrl("batch-control/requests/" + id + "/cancel"), HttpMethod.POST))
                .getWebResponse().getStatusCode();
    }

    private static int column(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).trim().replace("﻿", "").equalsIgnoreCase(name)) {
                return i;
            }
        }
        throw new AssertionError("no '" + name + "' column in the header " + header);
    }

    private static List<String> rowOf(List<List<String>> csv, String id) {
        return csv.stream().skip(1).filter(r -> r.stream().anyMatch(c -> c.trim().equals(id)))
                .findFirst().orElseThrow(() -> new AssertionError("requests.csv must carry a row for " + id));
    }
}
