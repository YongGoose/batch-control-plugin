package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import jenkins.model.Jenkins;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 1, scenario L1-18: request pages for approved-waiting and deleted builds, section
 * access and the resolving-run link. Matrix rows T-GAP-168 .. T-GAP-172 (note 276).
 *
 * <p>Basis: SPEC (D-55) "A request approved before the job was disabled shows that it waits
 * because the job is disabled"; SPEC 8 D-66 "run requests ... pages list pending requests first,
 * then active and ended items"; SPEC 6 #22 "a run shown on any Batch Control screen links to its
 * build page only when the viewer has Item/Read on the job; otherwise it is plain text"; SPEC 4
 * "빌드가 삭제된 뒤에도 해당 RunRecord와 관련 요청이 조회된다" with SPEC 6 usability "no link leads to a
 * 404 or 403 page" (so a deleted build is not linked); SPEC 2 "A user who holds at least one Batch
 * Control permission but not the one a section needs still gets 403 from that section"; SPEC 7
 * "취소는 요청자 본인 또는 Manage 권한자만"; SPEC 11 resolvedByRunId.
 *
 * <p>Written from docs/SPEC.md and docs/DECISIONS.md D-55/D-66 only (no src/main knowledge).
 */
@WithJenkins
public class RequestPageGapTest {

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(base());
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.setApprovedRunTimeoutMinutes(47);
        cfg.save();
    }

    /**
     * T-GAP-168 (L1-18; D-55, D-66): a request approved while its run waits in the queue (label
     * nobody has); the job is then disabled. The request page says it waits because the job is
     * disabled and gives the approved-run timeout (47 minutes); the run requests list shows the
     * request in the active group, not the pending one.
     */
    @Test
    public void t_gap_168_approvedRequestOfADisabledJobSaysItWaits() throws Exception {
        FreeStyleProject job = approvalRequired("gap-disabled-wait");
        job.setAssignedLabel(j.jenkins.getLabel("gap-no-such-agent"));
        String id = create(job);
        approve(id);
        assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(id).getStatus(), "premise: APPROVED, waiting");
        job.disable();
        assertTrue(job.isDisabled(), "premise: the job is disabled");

        HtmlPage page = UsabilityFixtures.htmlPage(j, "u1", "batch-control/requests/" + id + "/");
        String text = page.asNormalizedText();
        assertTrue(text.toLowerCase(Locale.ROOT).contains("disabled"), "the page says the job is disabled: " + excerpt(text));
        assertTrue(text.contains("47"), "the page gives the approved-run timeout in minutes (47): " + excerpt(text));

        HtmlPage list = UsabilityFixtures.htmlPage(j, "u1", "batch-control/requests/");
        assertNotNull(rowIn(list, "active", id), "the request is in the active group (D-66): " + excerpt(list.asNormalizedText()));
        assertTrue(rowIn(list, "pending", id) == null, "the request is not listed as pending");
    }

    /**
     * T-GAP-169 (L1-18; SPEC 4, SPEC 6 #22 and usability): an executed request whose build was
     * deleted: its page still opens and shows the run id as plain text, with no link to the gone
     * build. Guard: before the deletion the page links the build.
     */
    @Test
    public void t_gap_169_deletedBuildIsShownAsPlainText() throws Exception {
        FreeStyleProject job = approvalRequired("gap-deleted-build");
        String id = create(job);
        approve(id);
        j.waitUntilNoActivity();
        RunRequest executed = RunRequestService.get().load(id);
        assertEquals(RequestStatus.EXECUTED, executed.getStatus(), "premise: the request ran");
        String runId = executed.getExecutedRunId();
        assertNotNull(runId, "premise: the run is linked");
        String buildHref = job.getBuildByNumber(1).getUrl();

        HtmlPage before = UsabilityFixtures.htmlPage(j, "u1", "batch-control/requests/" + id + "/");
        assertTrue(UsabilityFixtures.hasLinkTo(j, before, buildHref), "guard: the existing build is linked");

        job.getBuildByNumber(1).delete();
        HtmlPage after = UsabilityFixtures.htmlPage(j, "u1", "batch-control/requests/" + id + "/");
        assertEquals(200, after.getWebResponse().getStatusCode(), "the request page still opens");
        assertTrue(after.asNormalizedText().contains(runId), "the run id is still shown: " + excerpt(after.asNormalizedText()));
        assertFalse(UsabilityFixtures.hasLinkTo(j, after, buildHref), "the deleted build must not be linked");
    }

    /**
     * T-GAP-170 (L1-18; SPEC 2 #31): rg holds RequestGrant (the change-window permission), a Batch
     * Control permission, but none that the run requests section needs, and has no requests:
     * {@code /batch-control/requests/} answers 403. Guard: the approver a1 opens it (200).
     */
    @Test
    public void t_gap_170_sectionWithoutItsPermissionAnswers403() throws Exception {
        assertEquals(403, ApproverFormFixtures.get(j, "rg", "batch-control/requests/").getStatusCode(),
                "a Batch Control permission other than the section's gives 403");
        assertEquals(200, ApproverFormFixtures.get(j, "a1", "batch-control/requests/").getStatusCode(), "guard: an approver opens it");
    }

    /**
     * T-GAP-171 (L1-18; SPEC 7): anonymous holds Request and Item/Read, so u1's request is visible
     * to anonymous; anonymous POSTs its cancel (crumb): refused, and the request stays PENDING.
     * Guard: u1's own cancel succeeds.
     */
    @Test
    public void t_gap_171_anonymousCannotCancelAVisibleRequest() throws Exception {
        j.jenkins.setAuthorizationStrategy(base().grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("anonymous"));
        FreeStyleProject job = approvalRequired("gap-anon-cancel");
        String id = create(job);
        org.htmlunit.WebResponse answer = ApproverFormFixtures.post(j, null, "batch-control/requests/" + id + "/cancel", java.util.List.of());
        int code = answer.getStatusCode();
        boolean signInPage = answer.getContentAsString().contains("<title>Sign in");
        assertTrue((code >= 400 && code < 500) || signInPage, "anonymous's cancel must be refused (4xx or the sign-in page), got "
                + code + ": " + excerpt(answer.getContentAsString()));
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus(), "the request stays PENDING");
        ApproverFormFixtures.assertSuccess(ApproverFormFixtures.post(j, "u1", "batch-control/requests/" + id + "/cancel",
                java.util.List.of()), "guard: the requester's cancel");
        assertEquals(RequestStatus.CANCELLED, RunRequestService.get().load(id).getStatus());
    }

    /**
     * T-GAP-172 (L1-18; SPEC 11 resolvedByRunId, SPEC 6 #22): an incident whose linked rerun
     * succeeded: its page links the resolving run for u1 (Item/Read); hv (ViewHistory, no
     * Item/Read) sees the run id as plain text.
     */
    @Test
    public void t_gap_172_incidentLinksTheResolvingRunForReaders() throws Exception {
        FreeStyleProject job = RerunFallbackFixtures.failedFreestyle(j, "gap-resolved");
        Incident incident = RerunFallbackFixtures.incidentFor("gap-resolved#1");
        RunRequest rerun;
        try (ACLContext ignored = as("u1")) {
            rerun = IncidentService.get().rerun(incident.getId(), "a1");
        }
        approve(rerun.getId());
        j.waitUntilNoActivity();
        assertEquals("gap-resolved#2", IncidentService.get().load(incident.getId()).getResolvedByRunId(), "premise: resolved by #2");
        String buildHref = job.getBuildByNumber(2).getUrl();

        HtmlPage reader = UsabilityFixtures.htmlPage(j, "u1", "batch-control/incidents/" + incident.getId() + "/");
        assertTrue(UsabilityFixtures.hasLinkTo(j, reader, buildHref), "a viewer with Item/Read gets a link to the resolving run: "
                + UsabilityFixtures.resolvedHrefs(reader));
        assertFalse(job.getACL().hasPermission2(User.getById("hv", true).impersonate2(), Item.READ), "premise: hv lacks Item/Read on the job");
        HtmlPage blind = UsabilityFixtures.htmlPage(j, "hv", "batch-control/incidents/" + incident.getId() + "/");
        assertEquals(200, blind.getWebResponse().getStatusCode(), "premise: hv opens the incident (ViewHistory)");
        assertFalse(UsabilityFixtures.hasLinkTo(j, blind, buildHref), "without Item/Read the resolving run is plain text: "
                + UsabilityFixtures.resolvedHrefs(blind));
        assertTrue(blind.asNormalizedText().contains("gap-resolved#2"), "the run id is still shown to hv");
    }

    // ------------------------------------------------------------------ helpers

    /** The accounts of these rows; anonymous holds nothing here (what anonymous holds applies to every user). */
    private static MockAuthorizationStrategy base() {
        return new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("hv")
                .grant(Jenkins.READ, BatchControlPermissions.REQUEST_GRANT).everywhere().to("rg");
    }

    private FreeStyleProject approvalRequired(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    private String create(FreeStyleProject job) {
        try (ACLContext ignored = as("u1")) {
            return RunRequestService.get().create(job, new LinkedHashMap<>(), "request page gap", "a1").getId();
        }
    }

    private void approve(String id) {
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(id, "ok");
        }
    }

    /** The row of {@code table[data-batch-control-list=<list>]} linking request {@code id}, or null. */
    private static DomElement rowIn(HtmlPage page, String list, String id) {
        for (DomNode table : page.querySelectorAll("table[data-batch-control-list=" + list + "]")) {
            for (DomNode tr : table.querySelectorAll("tr")) {
                for (DomNode a : tr.querySelectorAll("a[href]")) {
                    if (((DomElement) a).getAttribute("href").contains(id)) {
                        return (DomElement) tr;
                    }
                }
            }
        }
        return null;
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
