package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.IncidentStatus;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.DATE;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.LINKED;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.actionWithReference;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.fromRerunValues;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.noticeText;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.SECRET;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.approvalRequired;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.failedFreestyle;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.failedStashPipeline;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.fallbackTarget;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.forceFromRerun;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.formUrl;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.incidentFor;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.incidentPage;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.openForm;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.postRerun;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.rerunIds;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.submitOne;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.bytes;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileDisplay;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 11, D-72a: when an incident rerun falls back to the job's prefilled Request Run form,
 * the form carries the incident id, and a request submitted from it is linked to the incident only
 * after the server re-validates the reference (the incident exists, belongs to that job, and the
 * submitter holds BatchControl/ViewHistory, the same rights as the rerun button); a successful run
 * of a linked request records {@code resolvedByRunId} as for a direct rerun. An invalid reference
 * is ignored, never trusted: the request is still created, unlinked, and the submitter sees no
 * error. Matrix rows T-11-13 .. T-11-20 (note 263).
 *
 * <p>Render side (ui-dev b7ce183 report, relayed by the coordinator): the form carries a hidden
 * {@code fromRerun} field only when the reference is linkable, and the {@code rerun} notice then
 * says the request {@value RerunFallbackFixtures#LINKED}. The validated reference also rides in the
 * form's action query, so it survives a 413. A crafted reference in the field or in the action query
 * is ignored.
 *
 * <p>Users: {@code u1} (Request, Item/Read, ViewHistory: may press Request Rerun), {@code u2}
 * (Request, Item/Read, no ViewHistory), {@code viewer} (ViewHistory, Item/Read, no Request),
 * {@code a1} approver.
 *
 * <p>Services used as documented by the coordinator: {@code IncidentService#linkableIncident(String,
 * Job)} (the id or null, never an exception) and the typed
 * {@code RunRequestService#create(Job, List<ParameterValue>, String, List<String>, String incidentId)}.
 *
 * <p>Written from docs/SPEC.md item 11 and 5, docs/DECISIONS.md D-72 and D-72a and the frozen D-72
 * contract only (no src/main knowledge).
 */
@WithJenkins
public class RerunFallbackLinkTest {

    private static final String CAP_PROPERTY = "io.jenkins.plugins.batchcontrol.maxRequestBodyBytes";

    private JenkinsRule j;

    @AfterEach
    public void clearCap() {
        System.clearProperty(CAP_PROPERTY);
    }

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u2")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        TypedParameterFixtures.CaptureEnv.SEEN.clear();
    }

    /**
     * T-11-13: the whole fallback path. A failed Pipeline with a stashed file: u1's Request Rerun
     * answers 302 to the Request Run form; u1 selects a new file there and submits (multipart). The
     * request is linked: its {@code incidentId} is the incident, the incident lists it (service and
     * incident page). After a1 approves, run #2 succeeds with the new file and the incident records
     * {@code resolvedByRunId = <job>#2}; its status stays OPEN (resolution is a person's act).
     */
    @Test
    public void t_11_13_requestFromTheFallbackFormIsLinkedAndResolvesTheIncident() throws Exception {
        WorkflowJob job = failedStashPipeline(j, "fallback-link", "fallback-first-marker-Rt31");
        Incident incident = incidentFor("fallback-link#1");
        assertEquals(IncidentStatus.OPEN, incident.getStatus(), "premise: the incident is open");
        URL target = fallbackTarget(j, incident, postRerun(j, "u1", incident));
        assertEquals(List.of(), rerunIds(incident.getId()), "premise: the fallback itself links nothing");

        byte[] fixed = payload("fallback-fixed-marker-Wq62", 2200);
        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm form = openForm(wc, job, target);
        assertEquals(List.of(incident.getId()), fromRerunValues(form), "the fallback form must carry the incident reference");
        assertTrue(noticeText((HtmlPage) form.getPage(), "rerun").contains(LINKED), "the rerun notice must say the request "
                + LINKED + ": " + noticeText((HtmlPage) form.getPage(), "rerun"));
        TypedParameterFixtures.setFile(form, "DATA", uploadFile("fixed.bin", fixed));
        TypedParameterFixtures.setValue(form, "TOKEN", SECRET);
        String id = submitOne(wc, form, "the fallback form submission");

        RunRequest request = RunRequestService.get().load(id);
        assertEquals(RequestStatus.PENDING, request.getStatus());
        assertEquals(incident.getId(), request.getIncidentId(), "the request from the fallback form must be linked to the incident");
        assertEquals(fileDisplay("fixed.bin"), request.getParameters().get("DATA"), "the new file is the request's value");
        assertEquals(DATE, request.getParameters().get("DATE"), "the prefilled value is submitted");
        assertTrue(rerunIds(incident.getId()).contains(id), "the incident must list the request among its reruns");
        assertTrue(incidentPage(j, "u1", incident.getId()).contains(id), "the incident page must list the linked request");

        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(id, "rerun approved");
        }
        j.waitUntilNoActivity();
        assertNotNull(job.getBuildByNumber(2), "the approved request must run as #2");
        j.assertBuildStatusSuccess(job.getBuildByNumber(2));
        assertArrayEquals(fixed, bytes(j.jenkins.getWorkspaceFor(job).child("DATA")), "the run must receive the new file");
        Incident reloaded = IncidentService.get().load(incident.getId());
        assertEquals("fallback-link#2", reloaded.getResolvedByRunId(), "a successful linked run must be recorded on the incident");
        assertEquals(IncidentStatus.OPEN, reloaded.getStatus(), "the status must stay OPEN - resolution is a human decision");
    }

    /**
     * T-11-14: the incident of job A carried on the Request Run form of job B is not linked: the
     * request of B is created without an incident, A does not list it, and B's successful run
     * leaves A's {@code resolvedByRunId} empty; B's form renders no reference field and no "will be
     * linked" notice. Guard: the same reference on A's own form is rendered and links,
     * and A's successful run then sets {@code resolvedByRunId}.
     */
    @Test
    public void t_11_14_anotherJobsIncidentIsNotLinked() throws Exception {
        FreeStyleProject a = failedFreestyle(j, "link-a");
        FreeStyleProject b = approvalRequired(j, "link-b");
        Incident incident = incidentFor("link-a#1");

        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm foreign = openForm(wc, b, formUrl(j, b, Map.of("DATE", DATE), incident.getId()));
        assertRenderedUnlinkable(foreign, "A's incident on B's form");
        forceFromRerun(foreign, incident.getId());
        String crossId = submitOne(wc, foreign, "A's incident on B's form");
        assertNull(RunRequestService.get().load(crossId).getIncidentId(), "an incident of another job must not be linked");
        assertFalse(rerunIds(incident.getId()).contains(crossId), "the other job's incident must not list the request");
        assertFalse(incidentPage(j, "u1", incident.getId()).contains(crossId), "the incident page must not list it");
        approve(crossId);
        j.waitUntilNoActivity();
        j.assertBuildStatusSuccess(b.getBuildByNumber(1));
        assertNull(IncidentService.get().load(incident.getId()).getResolvedByRunId(),
                "a run of another job must never resolve the incident");

        HtmlForm own = openForm(wc, a, formUrl(j, a, Map.of("DATE", DATE), incident.getId()));
        assertEquals(List.of(incident.getId()), fromRerunValues(own), "guard: A's own form carries the reference");
        String linkedId = submitOne(wc, own, "guard: A's incident on A's form");
        assertEquals(incident.getId(), RunRequestService.get().load(linkedId).getIncidentId(), "guard: the valid reference links");
        assertTrue(rerunIds(incident.getId()).contains(linkedId), "guard: the incident lists the valid request");
        approve(linkedId);
        j.waitUntilNoActivity();
        FreeStyleBuild second = a.getBuildByNumber(2);
        assertNotNull(second, "guard: the linked request must run");
        j.assertBuildStatusSuccess(second);
        assertEquals("link-a#2", IncidentService.get().load(incident.getId()).getResolvedByRunId(),
                "guard: the linked run resolves the incident");
    }

    /**
     * T-11-15: malformed and unknown references ({@code ../x}, {@code ../../config}, a well-formed id
     * that names no incident, an empty value, and markup) each give a created, unlinked request and
     * no error; the incident lists none of them; nothing is written outside the store's own files
     * for {@code ../x}; markup in the URL reference is not rendered as markup; no crafted form
     * renders a reference field or a "will be linked" notice. Guard: a valid id on the same form
     * links.
     */
    @Test
    public void t_11_15_malformedOrUnknownReferencesAreIgnored() throws Exception {
        FreeStyleProject job = failedFreestyle(j, "link-m");
        Incident incident = incidentFor("link-m#1");
        String markup = "\"><b id=\"injected263\">x</b><script>window.injected263=1</script>";
        List<String> crafted = List.of("../x", "../../config", "20200101-000000-abc123", "", markup);

        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        for (String reference : crafted) {
            HtmlForm form = openForm(wc, job, formUrl(j, job, Map.of("DATE", DATE), reference));
            assertRenderedUnlinkable(form, "reference '" + reference + "'");
            forceFromRerun(form, reference);
            String id = submitOne(wc, form, "reference '" + reference + "'");
            RunRequest request = RunRequestService.get().load(id);
            assertTrue(request.getIncidentId() == null || request.getIncidentId().isEmpty(),
                    "reference '" + reference + "' must leave the request unlinked, got " + request.getIncidentId());
            assertFalse(rerunIds(incident.getId()).contains(id), "the incident must not list the request of '" + reference + "'");
        }
        Path store = TypedParameterFixtures.storeDir(j);
        assertFalse(Files.exists(store.resolve("x.xml")) || Files.exists(store.resolve("x")),
                "a traversal reference must not create or touch a file outside the incidents directory");

        HtmlPage page = (HtmlPage) wc.getPage(formUrl(j, job, Map.of(), markup));
        assertNull(page.getElementById("injected263"), "a markup reference must not inject an element");
        assertFalse(page.getWebResponse().getContentAsString().contains("<b id=\"injected263\">"),
                "a markup reference must not appear unescaped");

        HtmlForm valid = openForm(wc, job, formUrl(j, job, Map.of("DATE", DATE), incident.getId()));
        String linkedId = submitOne(wc, valid, "guard: the valid reference");
        assertEquals(incident.getId(), RunRequestService.get().load(linkedId).getIncidentId(), "guard: the valid reference links");
        assertTrue(incidentPage(j, "u1", incident.getId()).contains(linkedId), "guard: the incident page lists it");
    }

    /**
     * T-11-16: u2 may request runs but lacks BatchControl/ViewHistory (and so the rerun button):
     * a valid reference carried by u2's submission is ignored; the request is created unlinked and
     * the incident does not list it; u2's form renders no reference field and no "will be linked"
     * notice. Guard: u1 (ViewHistory) gets the field and links.
     */
    @Test
    public void t_11_16_submitterWithoutViewHistoryGetsNoLink() throws Exception {
        FreeStyleProject job = failedFreestyle(j, "link-v");
        Incident incident = incidentFor("link-v#1");
        URL url = formUrl(j, job, Map.of("DATE", DATE), incident.getId());

        JenkinsRule.WebClient u2 = TypedParameterFixtures.browser(j, "u2");
        HtmlForm form = openForm(u2, job, url);
        assertRenderedUnlinkable(form, "u2's form");
        forceFromRerun(form, incident.getId());
        String unlinked = submitOne(u2, form, "u2's submission");
        assertNull(RunRequestService.get().load(unlinked).getIncidentId(), "a submitter without ViewHistory must not link the request");
        assertFalse(rerunIds(incident.getId()).contains(unlinked), "the incident must not list u2's request");
        assertFalse(incidentPage(j, "u1", incident.getId()).contains(unlinked), "the incident page must not list u2's request");

        JenkinsRule.WebClient u1 = TypedParameterFixtures.browser(j, "u1");
        HtmlForm u1Form = openForm(u1, job, url);
        assertEquals(List.of(incident.getId()), fromRerunValues(u1Form), "guard: u1's form carries the reference");
        String linked = submitOne(u1, u1Form, "guard: u1's submission");
        assertEquals(incident.getId(), RunRequestService.get().load(linked).getIncidentId(), "guard: u1 holds ViewHistory and links");
        assertTrue(rerunIds(incident.getId()).contains(linked), "guard: the incident lists u1's request");
    }

    /**
     * T-11-17: {@code IncidentService#linkableIncident(reference, job)} returns the id only for an
     * existing incident of that job asked by a caller holding the rerun rights (u1), and null,
     * never an exception, for: the incident asked about another job, {@code ../x}, a well-formed
     * unknown id, null, empty, a caller without ViewHistory (u2), and a caller with ViewHistory but
     * without Request (viewer; D-72a: the same rights as the rerun button, SPEC 5).
     */
    @Test
    public void t_11_17_linkableIncidentReturnsTheIdOnlyForAValidReference() throws Exception {
        FreeStyleProject a = failedFreestyle(j, "svc-a");
        FreeStyleProject b = approvalRequired(j, "svc-b");
        String id = incidentFor("svc-a#1").getId();

        assertEquals(id, linkable("u1", id, a), "guard: a valid reference asked by u1 returns the id");
        assertNull(linkable("u1", id, b), "another job's incident");
        assertNull(linkable("u1", "../x", a), "a traversal reference");
        assertNull(linkable("u1", "20200101-000000-abc123", a), "a well-formed id that names no incident");
        assertNull(linkable("u1", null, a), "no reference");
        assertNull(linkable("u1", "", a), "an empty reference");
        assertNull(linkable("u2", id, a), "a caller without ViewHistory");
        assertNull(linkable("viewer", id, a), "a caller without BatchControl/Request");
    }

    /**
     * T-11-18: the typed {@code create(..., incidentId)} refuses a reference that does not name an
     * incident of the job with IllegalArgumentException and stores nothing: a well-formed unknown
     * id, an incident whose file is gone from the store (vanished), and another job's incident.
     * Guard: the incident of the job is accepted and the stored request is listed by it.
     */
    @Test
    public void t_11_18_typedCreateRefusesAVanishedOrForeignIncident() throws Exception {
        FreeStyleProject a = failedFreestyle(j, "create-a");
        FreeStyleProject gone = failedFreestyle(j, "create-gone");
        String id = incidentFor("create-a#1").getId();
        String vanished = incidentFor("create-gone#1").getId();
        Files.delete(TypedParameterFixtures.storeDir(j).resolve("incidents").resolve(vanished + ".xml"));
        assertNull(IncidentService.get().load(vanished), "premise: the incident has vanished from the store");

        Set<String> before = ApproverFormFixtures.runRequestIds();
        assertRefused(a, "20200101-000000-abc123", "a well-formed id that names no incident");
        assertRefused(gone, vanished, "a vanished incident");
        assertRefused(approvalRequired(j, "create-b"), id, "another job's incident");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "a refused creation must store nothing");
        assertEquals(List.of(), rerunIds(id), "a refused creation must link nothing");

        RunRequest created;
        try (ACLContext ignored = as("u1")) {
            created = RunRequestService.get().create(a, values(), "rerun through the service", List.of("a1"), id);
        }
        assertEquals(id, created.getIncidentId(), "guard: the job's own incident is accepted");
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(created.getId()).getStatus());
        assertTrue(rerunIds(id).contains(created.getId()), "guard: the incident lists the request");
    }

    /**
     * T-11-19: the link survives a 413. u1 submits the fallback form with a file over the cap: 413,
     * nothing created; the form shown again still carries the {@code rerun} notice saying the
     * request {@value RerunFallbackFixtures#LINKED} and the reference field with the incident id.
     * u1 chooses a small file there and submits: the request is created and linked, and the
     * incident lists it.
     */
    @Test
    public void t_11_19_linkSurvivesAnOverSizeSubmission() throws Exception {
        WorkflowJob job = failedStashPipeline(j, "fallback-413", "fallback-413-first-marker-Gd70");
        Incident incident = incidentFor("fallback-413#1");
        URL target = fallbackTarget(j, incident, postRerun(j, "u1", incident));
        System.setProperty(CAP_PROPERTY, Long.toString(64 * 1024));

        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm form = openForm(wc, job, target);
        TypedParameterFixtures.setFile(form, "DATA", uploadFile("huge.bin", payload("fallback-huge-marker-Tb19", 80 * 1024)));
        TypedParameterFixtures.setValue(form, "TOKEN", SECRET);
        Set<String> before = ApproverFormFixtures.runRequestIds();
        Page answer = TypedParameterFixtures.submit(wc, form, "rerun from the Request Run form", "a1");
        assertEquals(413, answer.getWebResponse().getStatusCode(), "premise: the over-size submission answers 413");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "the 413 creates nothing");
        assertTrue(answer instanceof HtmlPage, "the 413 answer must be the form page");
        HtmlPage page = (HtmlPage) answer;
        assertTrue(noticeText(page, "rerun").contains(LINKED), "after a 413 the rerun notice must still say the request "
                + LINKED + ": " + noticeText(page, "rerun"));
        List<HtmlForm> forms = UsabilityFixtures.formsEndingWith(page, job.getUrl() + "batch-control/submit");
        assertFalse(forms.isEmpty(), "the 413 answer must carry the form again: " + UsabilityFixtures.formActions(page));
        HtmlForm again = forms.get(0);
        assertEquals(List.of(incident.getId()), fromRerunValues(again), "after a 413 the form must still carry the reference");

        byte[] small = payload("fallback-small-marker-Kp44", 1800);
        TypedParameterFixtures.setFile(again, "DATA", uploadFile("small.bin", small));
        TypedParameterFixtures.setValue(again, "TOKEN", SECRET);
        String id = submitOne(wc, again, "the resubmission under the cap");
        assertEquals(incident.getId(), RunRequestService.get().load(id).getIncidentId(), "the resubmission must be linked");
        assertTrue(rerunIds(incident.getId()).contains(id), "the incident must list the resubmitted request");
        assertEquals(fileDisplay("small.bin"), RunRequestService.get().load(id).getParameters().get("DATA"));
    }

    /**
     * T-11-20: a crafted reference in the form's action query ({@code submit?fromRerun=...}) is
     * ignored like a crafted field: another job's incident, a well-formed unknown id and
     * {@code ../x} each give a created, unlinked request that no incident lists. Guard: the job's
     * own incident in the action query links (the query is a channel the server reads, and
     * validates).
     */
    @Test
    public void t_11_20_craftedReferenceInTheActionQueryIsIgnored() throws Exception {
        FreeStyleProject a = failedFreestyle(j, "query-a");
        failedFreestyle(j, "query-b");
        Incident own = incidentFor("query-a#1");
        Incident other = incidentFor("query-b#1");

        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        for (String reference : List.of(other.getId(), "20200101-000000-abc123", "../x")) {
            HtmlForm form = openForm(wc, a, formUrl(j, a, Map.of("DATE", DATE), null));
            assertEquals(List.of(), fromRerunValues(form), "premise: the bare form carries no reference field");
            actionWithReference(form, a, reference);
            String id = submitOne(wc, form, "action query reference '" + reference + "'");
            RunRequest request = RunRequestService.get().load(id);
            assertTrue(request.getIncidentId() == null || request.getIncidentId().isEmpty(),
                    "reference '" + reference + "' in the action query must leave the request unlinked, got " + request.getIncidentId());
            assertFalse(rerunIds(own.getId()).contains(id), "the job's incident must not list it");
            assertFalse(rerunIds(other.getId()).contains(id), "the other job's incident must not list it");
        }

        HtmlForm valid = openForm(wc, a, formUrl(j, a, Map.of("DATE", DATE), null));
        actionWithReference(valid, a, own.getId());
        String linkedId = submitOne(wc, valid, "guard: the job's own incident in the action query");
        assertEquals(own.getId(), RunRequestService.get().load(linkedId).getIncidentId(), "guard: a valid reference in the query links");
        assertTrue(rerunIds(own.getId()).contains(linkedId), "guard: the incident lists it");
    }

    // ---------------------------------------------------------------- helpers

    /** The rendered form of a reference that is not linkable: no reference field, no "will be linked" notice. */
    private static void assertRenderedUnlinkable(HtmlForm form, String what) {
        assertEquals(List.of(), fromRerunValues(form), what + ": a reference that is not linkable must not be rendered into the form");
        assertFalse(((HtmlPage) form.getPage()).asNormalizedText().contains(LINKED), what + ": the page must not promise a link");
    }

    private String linkable(String userId, String reference, Job<?, ?> job) {
        try (ACLContext ignored = as(userId)) {
            return assertDoesNotThrow(() -> IncidentService.get().linkableIncident(reference, job),
                    "linkableIncident must never throw (" + userId + ", '" + reference + "', " + job.getFullName() + ")");
        }
    }

    private void assertRefused(Job<?, ?> job, String incidentId, String what) {
        try (ACLContext ignored = as("u1")) {
            assertThrows(IllegalArgumentException.class,
                    () -> RunRequestService.get().create(job, values(), "rerun through the service", List.of("a1"), incidentId),
                    what + " must be refused");
        }
    }

    private static List<ParameterValue> values() {
        return List.of(new StringParameterValue("DATE", DATE));
    }

    private void approve(String id) {
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(id, "approved");
        }
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
