package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.IncidentStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.file_parameters.Base64FileParameterDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.approverPairs;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.post;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.runRequestIds;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.requestDirListing;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 1, scenario L1-17: form refusals on request and incident pages. Matrix rows
 * T-GAP-130 .. T-GAP-137 (note 276).
 *
 * <p>Basis: SPEC 6 usability "invalid input is refused with a message next to the field and the
 * user's input is kept ... (no bare "Access Denied", stack trace, "Oops!" page)"; SPEC 3 "목록에 없는
 * 사용자를 결재자로 지정하면 요청 생성이 거부된다" and the D-37 designation rules; SPEC 11 "acknowledging,
 * resolving and commenting ... each is a POST", SPEC 4 Incident "OPEN -> ACKNOWLEDGED -> RESOLVED
 * (역방향 없음)"; SPEC 5 D-72 / D-74 (2) the body cap (413, system property, second stage counts
 * a base64File as its Base64 text); LIMITATIONS 31 "A save that fails anyway stores nothing ... and
 * is reported as an error above the form, not as an error page". No document names a maximum
 * number of approvers or a maximum approver id length (note 276 ambiguity), so the approver rows
 * assert the documented refusal of approvers that are not on the list.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-37/D-72/D-74 and docs/LIMITATIONS.md only (no
 * src/main knowledge).
 */
@WithJenkins
public class RequestFormRefusalGapTest {

    private static final String CAP_PROPERTY = "io.jenkins.plugins.batchcontrol.maxRequestBodyBytes";
    private static final String KEPT_REASON = "kept-reason-gap-17-Lq3";

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.save();
        job = j.createFreeStyleProject("gap-forms");
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("DAY", "2000-01-01")));
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty(CAP_PROPERTY);
    }

    /**
     * T-GAP-130 (L1-17; SPEC 3, SPEC 6 usability): the run request form submitted with 51
     * approvers (a1 and 50 ids not on the approver list) and, separately, with one approver id of
     * 257 characters is refused (4xx, a plain message naming the approvers), the typed reason is
     * kept, and nothing is stored. Guard: the same form with a1 alone is accepted.
     */
    @Test
    public void t_gap_130_runRequestFormRefusesOversizedApproverInput() throws Exception {
        for (String[] approvers : badApproverSets()) {
            Set<String> before = runRequestIds();
            List<NameValuePair> params = new ArrayList<>();
            params.add(new NameValuePair("reason", KEPT_REASON));
            params.addAll(approverPairs(approvers));
            WebResponse refused = post(j, "u1", job.getUrl() + "batch-control/submit", params);
            assertRefusedNamingApprovers(refused, approvers.length + " approver(s), the longest " + longest(approvers));
            assertTrue(refused.getContentAsString().contains(KEPT_REASON), "the typed reason must be kept: " + excerpt(refused.getContentAsString()));
            assertEquals(before, runRequestIds(), "nothing may be stored");
        }
        submitRunOk(j, "u1", job, KEPT_REASON, "a1");
    }

    /**
     * T-GAP-131 (L1-17; SPEC 3 "결재 전까지 요청자가 결재자를 바꿀 수 있고", D-37): the change-approver
     * form of a PENDING request submitted with the same two bad inputs is refused (4xx, a plain
     * message naming the approvers) and the designation and its history are unchanged. Guard: a
     * change to a2 is accepted.
     */
    @Test
    public void t_gap_131_changeApproverFormRefusesOversizedApproverInput() throws Exception {
        String id = submitRunOk(j, "u1", job, KEPT_REASON, "a1");
        for (String[] approvers : badApproverSets()) {
            WebResponse refused = ApproverFormFixtures.changeRunApprovers(j, "u1", id, approvers);
            assertRefusedNamingApprovers(refused, "change to " + approvers.length + " approver(s)");
            RunRequest unchanged = RunRequestService.get().load(id);
            assertEquals(List.of("a1"), unchanged.getApprovers(), "the designation must not change");
            assertTrue(unchanged.getApproverChanges() == null || unchanged.getApproverChanges().isEmpty(), "no change may be recorded");
        }
        ApproverFormFixtures.assertSuccess(ApproverFormFixtures.changeRunApprovers(j, "u1", id, "a2"), "guard: a valid change");
        assertEquals(List.of("a2"), RunRequestService.get().load(id).getApprovers());
    }

    /**
     * T-GAP-132 (L1-17; SPEC 11 rerun, SPEC 3, SPEC 6 usability): the incident "Request rerun" form
     * submitted with the same two bad inputs is refused (4xx, a plain message naming the
     * approvers), the typed reason is kept and no rerun request is created. Guard: the rerun with
     * a1 creates one.
     */
    @Test
    public void t_gap_132_incidentRerunFormRefusesOversizedApproverInput() throws Exception {
        FreeStyleProject failed = RerunFallbackFixtures.failedFreestyle(j, "gap-rerun-form");
        Incident incident = RerunFallbackFixtures.incidentFor("gap-rerun-form#1");
        HtmlPage incidentPage = UsabilityFixtures.htmlPage(j, "u1", "batch-control/incidents/" + incident.getId() + "/");
        HtmlForm rerunForm = UsabilityFixtures.formsEndingWith(incidentPage, "batch-control/incidents/" + incident.getId() + "/rerun")
                .stream().findFirst().orElse(null);
        assertTrue(rerunForm != null, "fixture: u1 is offered the rerun form; forms: " + UsabilityFixtures.formActions(incidentPage));
        boolean formHasReason = UsabilityFixtures.hasField(rerunForm, "reason");
        for (String[] approvers : badApproverSets()) {
            List<NameValuePair> params = new ArrayList<>();
            params.add(new NameValuePair("reason", KEPT_REASON));
            params.addAll(approverPairs(approvers));
            WebResponse refused = post(j, "u1", "batch-control/incidents/" + incident.getId() + "/rerun", params);
            assertRefusedNamingApprovers(refused, "rerun with " + approvers.length + " approver(s)");
            if (formHasReason) {
                assertTrue(refused.getContentAsString().contains(KEPT_REASON), "the rerun form has a reason field, so the typed reason"
                        + " must be kept (SPEC 6 usability): " + excerpt(refused.getContentAsString()));
            }
            assertTrue(RerunFallbackFixtures.rerunIds(incident.getId()).isEmpty(), "no rerun request may be created");
        }
        List<NameValuePair> ok = new ArrayList<>();
        ok.add(new NameValuePair("reason", KEPT_REASON));
        ok.addAll(approverPairs("a1"));
        WebResponse accepted = post(j, "u1", "batch-control/incidents/" + incident.getId() + "/rerun", ok);
        assertTrue(accepted.getStatusCode() < 400, "guard: a valid rerun is accepted, got " + accepted.getStatusCode());
        assertEquals(1, RerunFallbackFixtures.rerunIds(incident.getId()).size(), "guard: one rerun request");
        assertTrue(failed.getBuilds().size() == 1, "nothing ran");
    }

    /**
     * T-GAP-133 (L1-17; SPEC 11 commenting is a POST; SPEC 6 usability): an empty incident comment
     * is refused (4xx, a plain message about the comment) and adds nothing to the incident's
     * history. Guard: a comment with text is added.
     */
    @Test
    public void t_gap_133_emptyIncidentCommentIsRefused() throws Exception {
        Incident incident = openIncident("gap-comment");
        int transitions = IncidentService.get().load(incident.getId()).getTransitions().size();
        WebResponse refused = incidentPost(incident, "comment", "");
        assertTrue(refused.getStatusCode() >= 400 && refused.getStatusCode() < 500, "an empty comment must be refused with 4xx, got "
                + refused.getStatusCode() + ": " + excerpt(refused.getContentAsString()));
        UsabilityFixtures.assertPlainRefusal("an empty comment", refused.getContentAsString(), Pattern.compile("(?i)comment"));
        assertEquals(transitions, IncidentService.get().load(incident.getId()).getTransitions().size(), "nothing is added");

        assertTrue(incidentPost(incident, "comment", "log attached").getStatusCode() < 400, "guard: a comment with text is accepted");
        assertEquals(transitions + 1, IncidentService.get().load(incident.getId()).getTransitions().size(), "guard: the comment is added");
    }

    /**
     * T-GAP-134 (L1-17; SPEC 4 Incident "OPEN -> ACKNOWLEDGED -> RESOLVED"): acknowledging an
     * ACKNOWLEDGED incident again, and resolving a RESOLVED one again, are each refused with a
     * plain message (4xx) and leave the state and the history unchanged.
     */
    @Test
    public void t_gap_134_repeatedIncidentTransitionsAreRefused() throws Exception {
        Incident incident = openIncident("gap-transitions");
        assertTrue(incidentPost(incident, "acknowledge", "on it").getStatusCode() < 400, "fixture: acknowledge");
        assertEquals(IncidentStatus.ACKNOWLEDGED, IncidentService.get().load(incident.getId()).getStatus());
        int afterAck = IncidentService.get().load(incident.getId()).getTransitions().size();
        WebResponse again = incidentPost(incident, "acknowledge", "on it again");
        assertTrue(again.getStatusCode() >= 400 && again.getStatusCode() < 500, "a second acknowledge must be refused, got "
                + again.getStatusCode() + ": " + excerpt(again.getContentAsString()));
        UsabilityFixtures.assertPlainRefusal("a second acknowledge", again.getContentAsString(), null);
        assertEquals(IncidentStatus.ACKNOWLEDGED, IncidentService.get().load(incident.getId()).getStatus());
        assertEquals(afterAck, IncidentService.get().load(incident.getId()).getTransitions().size(), "no transition is added");

        assertTrue(incidentPost(incident, "resolve", "fixed").getStatusCode() < 400, "fixture: resolve");
        assertEquals(IncidentStatus.RESOLVED, IncidentService.get().load(incident.getId()).getStatus());
        int afterResolve = IncidentService.get().load(incident.getId()).getTransitions().size();
        WebResponse resolvedAgain = incidentPost(incident, "resolve", "fixed again");
        assertTrue(resolvedAgain.getStatusCode() >= 400 && resolvedAgain.getStatusCode() < 500, "a second resolve must be refused, got "
                + resolvedAgain.getStatusCode() + ": " + excerpt(resolvedAgain.getContentAsString()));
        UsabilityFixtures.assertPlainRefusal("a second resolve", resolvedAgain.getContentAsString(), null);
        assertEquals(IncidentStatus.RESOLVED, IncidentService.get().load(incident.getId()).getStatus());
        assertEquals(afterResolve, IncidentService.get().load(incident.getId()).getTransitions().size(), "no transition is added");
    }

    /**
     * T-GAP-135 (L1-17; SPEC 6 usability "no ... stack trace, Oops! page"): structured submissions
     * whose {@code json} lacks the {@code parameter} member, carries a non-object entry, or an
     * entry without a name are each handled with a clear outcome: either a request is created
     * (then DAY holds the job's default) or the submission is refused with a 4xx plain message and
     * nothing is stored; never a server error or crash page.
     */
    @Test
    public void t_gap_135_malformedStructuredSubmissionsHaveAClearOutcome() throws Exception {
        String[] jsons = {
            "{\"reason\":\"no parameter member\",\"approvers\":\"a1\"}",
            "{\"reason\":\"non-object entry\",\"approvers\":\"a1\",\"parameter\":[\"DAY\"]}",
            "{\"reason\":\"entry without name\",\"approvers\":\"a1\",\"parameter\":[{\"value\":\"2026-10-01\"}]}"};
        for (String json : jsons) {
            Set<String> before = runRequestIds();
            List<NameValuePair> params = new ArrayList<>();
            params.add(new NameValuePair("reason", "structured"));
            params.add(new NameValuePair("approvers", "a1"));
            params.add(new NameValuePair("json", json));
            WebResponse answer = post(j, "u1", job.getUrl() + "batch-control/submit", params);
            String text = answer.getContentAsString();
            assertTrue(answer.getStatusCode() < 500, json + ": never a server error, got " + answer.getStatusCode() + ": " + excerpt(text));
            UsabilityFixtures.assertPlainRefusal(json, text, null);
            Set<String> created = runRequestIds();
            created.removeAll(before);
            if (answer.getStatusCode() >= 400) {
                assertTrue(created.isEmpty(), json + ": a refusal stores nothing");
            } else {
                assertEquals(1, created.size(), json + ": an accepted submission creates exactly one request");
                String day = RunRequestService.get().load(created.iterator().next()).getParameters().get("DAY");
                assertTrue(day == null || "2000-01-01".equals(day), json + ": an accepted request uses the job's default, got " + day);
            }
        }
    }

    /**
     * T-GAP-136 (L1-17; SPEC 5 D-72 / D-74 (2) body cap): with the cap at 1,500 bytes a larger
     * url-encoded submission answers 413 with a message stating the limit and creates nothing.
     * With the cap at exactly 1 MB, a base64File of about 900 KB (the body is under the cap, the
     * Base64 text it would keep is over it) answers 413 stating the limit, and nothing is kept
     * under {@code requests/run/}. Guard: a small base64File under the same cap is accepted.
     */
    @Test
    public void t_gap_136_bodyCapMessagesStateTheLimit() throws Exception {
        System.setProperty(CAP_PROPERTY, "1500");
        Set<String> before = runRequestIds();
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("reason", "over the small cap"));
        params.add(new NameValuePair("approvers", "a1"));
        params.add(new NameValuePair("DAY", "x".repeat(3000)));
        WebResponse small = post(j, "u1", job.getUrl() + "batch-control/submit", params);
        assertEquals(413, small.getStatusCode(), "a body over 1,500 bytes answers 413: " + excerpt(small.getContentAsString()));
        assertTrue(Pattern.compile("1,500|1500").matcher(small.getContentAsString()).find(), "the 413 message states the 1,500-byte limit: "
                + excerpt(small.getContentAsString()));
        assertEquals(before, runRequestIds(), "nothing is created");

        System.setProperty(CAP_PROPERTY, Long.toString(1024L * 1024L));
        FreeStyleProject b64 = j.createFreeStyleProject("gap-b64-cap");
        b64.addProperty(new ParametersDefinitionProperty(new Base64FileParameterDefinition("B64")));
        setBatchControl(b64, new BatchControlJobProperty(true));
        Set<String> listing = requestDirListing(j);
        Page big = submitBase64(b64, payload("gap-b64-900k", 900 * 1024));
        assertEquals(413, big.getWebResponse().getStatusCode(), "a kept Base64 text over the cap answers 413: "
                + excerpt(big.getWebResponse().getContentAsString()));
        assertTrue(Pattern.compile("1 MB|1 MiB|1,048,576|1048576").matcher(big.getWebResponse().getContentAsString()).find(),
                "the 413 message states the 1 MB limit: " + excerpt(big.getWebResponse().getContentAsString()));
        assertEquals(listing, requestDirListing(j), "nothing is kept under requests/run/");
        assertEquals(before, runRequestIds(), "no request is created");

        Page ok = submitBase64(b64, payload("gap-b64-small", 10 * 1024));
        assertTrue(ok.getWebResponse().getStatusCode() < 400, "guard: a small base64File is accepted, got "
                + ok.getWebResponse().getStatusCode());
    }

    /**
     * T-GAP-137 (L1-17 (F); LIMITATIONS 31 "A save that fails anyway stores nothing ... reported as
     * an error above the form, not as an error page"; SPEC 5 D-72b "a failed save leaves nothing
     * behind"): {@code requests/run/} is replaced by a plain file, so no request can be written.
     * The Request Run form submission comes back as the form with a message that the request could
     * not be saved, not core's bare error page, and no request exists. Guard: after the store is
     * repaired the same submission creates one.
     */
    @Test
    public void t_gap_137_failedSaveIsReportedOnTheForm() throws Exception {
        Path requests = TypedParameterFixtures.storeDir(j).resolve("requests");
        Path run = requests.resolve("run");
        Files.createDirectories(requests);
        if (Files.isDirectory(run)) {
            try (var files = Files.list(run)) {
                assertTrue(files.findAny().isEmpty(), "fixture: requests/run/ holds no request yet");
            }
            Files.delete(run);
        }
        Files.writeString(run, "not a directory");
        try {
            Set<String> before = runRequestIds();
            JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
            HtmlForm form = TypedParameterFixtures.requestRunForm(j, wc, job);
            Page answer = TypedParameterFixtures.submit(wc, form, KEPT_REASON, "a1");
            assertTrue(answer instanceof HtmlPage, "the failure is answered with an HTML page");
            HtmlPage page = (HtmlPage) answer;
            UsabilityFixtures.assertNotBareErrorPage("a failed save", page);
            UsabilityFixtures.assertPlainRefusal("a failed save", page.asNormalizedText(),
                    Pattern.compile("(?i)could not be saved|not saved|nothing was stored|could not be stored"));
            assertFalse(UsabilityFixtures.formsEndingWith(page, job.getUrl() + "batch-control/submit").isEmpty(),
                    "the form is shown again; forms: " + UsabilityFixtures.formActions(page));
            assertEquals(before, runRequestIds(), "no request may exist after a failed save");
        } finally {
            Files.deleteIfExists(run);
        }
        submitRunOk(j, "u1", job, KEPT_REASON, "a1");
    }

    // ------------------------------------------------------------------ helpers

    /** 51 approvers (a1 and 50 ids that are not on the list), and one approver id of 257 characters. */
    private static List<String[]> badApproverSets() {
        String[] many = new String[51];
        many[0] = "a1";
        for (int i = 1; i < many.length; i++) {
            many[i] = String.format("gap-approver-%02d", i);
        }
        return List.of(many, new String[] {"z".repeat(257)});
    }

    private static int longest(String[] values) {
        return Arrays.stream(values).mapToInt(String::length).max().orElse(0);
    }

    private static void assertRefusedNamingApprovers(WebResponse refused, String what) {
        assertTrue(refused.getStatusCode() >= 400 && refused.getStatusCode() < 500, what + " must be refused with 4xx, got "
                + refused.getStatusCode() + ": " + excerpt(refused.getContentAsString()));
        UsabilityFixtures.assertPlainRefusal(what, refused.getContentAsString(), Pattern.compile("(?i)approver"));
    }

    private Incident openIncident(String name) throws Exception {
        RerunFallbackFixtures.failedFreestyle(j, name);
        return RerunFallbackFixtures.incidentFor(name + "#1");
    }

    private WebResponse incidentPost(Incident incident, String verb, String comment) throws Exception {
        return post(j, "u1", "batch-control/incidents/" + incident.getId() + "/" + verb,
                List.of(new NameValuePair("comment", comment)));
    }

    private Page submitBase64(FreeStyleProject target, byte[] content) throws Exception {
        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm form = TypedParameterFixtures.requestRunForm(j, wc, target);
        TypedParameterFixtures.setFile(form, "B64", uploadFile("data.bin", content));
        return TypedParameterFixtures.submit(wc, form, "base64 cap", "a1");
    }
}
