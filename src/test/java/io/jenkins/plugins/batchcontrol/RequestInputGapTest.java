package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.requestDirListing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 1, scenario L1-07: run request input checks through the service API and the
 * decision forms. Matrix rows T-GAP-126 .. T-GAP-129 (note 276).
 *
 * <p>Basis: SPEC 5 D-72b "each parameter name appears at most once in a run request ... Every
 * stored value obeys the display length limit and contains only characters XML can store; a
 * failed save leaves nothing behind"; LIMITATIONS 31 "A character that XML 1.0 cannot store ... is
 * refused the same way in a parameter name or value, in the reason, and in an approve or reject
 * comment ... a refused approval or rejection leaves the request pending"; SPEC 6 usability
 * (invalid input refused with a message next to the field).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-72b and docs/LIMITATIONS.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class RequestInputGapTest {

    private static final String CONTROL = "\u0001";

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        job = j.createFreeStyleProject("gap-input");
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("DAY", "2000-01-01")));
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * T-GAP-126 (L1-07; LIMITATIONS 31 "refused the same way ... in an approve or reject comment
     * ... a refused approval or rejection leaves the request pending"): the designated approver's
     * approval and rejection with a comment containing U+0001 are refused by the service and over
     * HTTP (4xx, a plain message naming the comment), and the request stays PENDING. Guard: an
     * approval with a clean comment succeeds.
     */
    @Test
    public void t_gap_126_decisionCommentWithAControlCharacterIsRefused() throws Exception {
        String id = create(List.of(new StringParameterValue("DAY", "2026-09-30")));
        for (String verb : new String[] {"approve", "reject"}) {
            try (ACLContext ignored = as("a1")) {
                assertThrows(RuntimeException.class, () -> {
                    if ("approve".equals(verb)) {
                        RunRequestService.get().approve(id, "looks fine" + CONTROL);
                    } else {
                        RunRequestService.get().reject(id, "not now" + CONTROL);
                    }
                }, "the service must refuse a " + verb + " comment holding U+0001");
            }
            assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus(), verb + " (service): the request stays PENDING");

            WebResponse http = decideRun(j, "a1", id, verb, "comment" + CONTROL + "text");
            assertTrue(http.getStatusCode() >= 400 && http.getStatusCode() < 500, verb + ": the HTTP decision must be refused with 4xx, got "
                    + http.getStatusCode() + ": " + excerpt(http.getContentAsString()));
            UsabilityFixtures.assertPlainRefusal(verb + " with U+0001", http.getContentAsString(), Pattern.compile("(?i)comment"));
            assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus(), verb + " (HTTP): the request stays PENDING");
        }

        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(id, "looks fine");
        }
        assertTrue(RunRequestService.get().load(id).getStatus() == RequestStatus.APPROVED
                || RunRequestService.get().load(id).getStatus() == RequestStatus.EXECUTED, "guard: a clean comment is accepted");
        j.waitUntilNoActivity();
    }

    /**
     * T-GAP-127 (L1-07; LIMITATIONS 31, D-72b (2)): the reject form on the request page, filled with
     * a comment holding U+0001 by the designated approver, comes back as a page (not core's bare
     * error page) whose message names the comment, with the reject form again; the request stays
     * PENDING.
     */
    @Test
    public void t_gap_127_rejectFormWithAControlCharacterComesBackWithTheMessage() throws Exception {
        String id = create(List.of(new StringParameterValue("DAY", "2026-09-30")));
        JenkinsRule.WebClient approver = UsabilityFixtures.client(j, "a1");
        HtmlPage detail = (HtmlPage) approver.getPage(new URL(j.getURL(), "batch-control/requests/" + id + "/"));
        HtmlForm reject = UsabilityFixtures.formsEndingWith(detail, "batch-control/requests/" + id + "/reject").stream()
                .findFirst().orElse(null);
        assertNotNull(reject, "fixture: the designated approver is offered a reject form; forms: " + UsabilityFixtures.formActions(detail));
        UsabilityFixtures.setField(reject, "comment", "bad" + CONTROL + "comment");
        Page answer = j.submit(reject);
        assertTrue(answer instanceof HtmlPage, "the refusal is an HTML page");
        HtmlPage refused = (HtmlPage) answer;
        UsabilityFixtures.assertNotBareErrorPage("a reject comment with U+0001", refused);
        UsabilityFixtures.assertPlainRefusal("a reject comment with U+0001", refused.asNormalizedText(), Pattern.compile("(?i)comment"));
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus(), "the request stays PENDING");
    }

    /**
     * T-GAP-128 (L1-07; SPEC 5 D-72b "contains only characters XML can store; a failed save leaves
     * nothing behind"): the service refuses a string value whose name is empty and one whose name
     * contains U+0001, and nothing is stored under {@code requests/run/}. Guard: the same value
     * under the job's own name DAY is stored.
     */
    @Test
    public void t_gap_128_parameterNamesThatCannotBeStoredAreRefused() throws Exception {
        Set<String> listing = requestDirListing(j);
        Set<String> ids = ApproverFormFixtures.runRequestIds();
        for (String name : new String[] {"", "DAY" + CONTROL}) {
            List<ParameterValue> values = List.of(new StringParameterValue(name, "2026-09-30"));
            try (ACLContext ignored = as("u1")) {
                assertThrows(RuntimeException.class, () -> RunRequestService.get().create(job, values, "bad name", "a1"),
                        "a parameter named '" + name.replace(CONTROL, "\\u0001") + "' must be refused");
            }
            assertEquals(listing, requestDirListing(j), "nothing may be stored under requests/run/ for '" + name.replace(CONTROL, "\\u0001") + "'");
            assertEquals(ids, ApproverFormFixtures.runRequestIds(), "no request may exist");
        }
        String id = create(List.of(new StringParameterValue("DAY", "2026-09-30")));
        assertEquals("2026-09-30", RunRequestService.get().load(id).getParameters().get("DAY"), "guard: a valid name is stored");
        assertFalse(listing.equals(requestDirListing(j)), "guard: the valid request is stored under requests/run/");
    }

    /**
     * T-GAP-129 (L1-07; SPEC 4 state machine): approving a run request id that does not exist is
     * refused with IllegalArgumentException. Guard: a real request id is approved.
     */
    @Test
    public void t_gap_129_approvingAnUnknownRunRequestIsRefused() throws Exception {
        String unknown = UUID.randomUUID().toString();
        try (ACLContext ignored = as("a1")) {
            assertThrows(IllegalArgumentException.class, () -> RunRequestService.get().approve(unknown, "ok"),
                    "an unknown id must be refused with IllegalArgumentException");
        }
        String id = create(List.of(new StringParameterValue("DAY", "2026-09-30")));
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(id, "ok");
        }
        assertFalse(RunRequestService.get().load(id).getStatus() == RequestStatus.PENDING, "guard: the real request is decided");
        j.waitUntilNoActivity();
    }

    // ------------------------------------------------------------------ helpers

    private String create(List<ParameterValue> values) {
        try (ACLContext ignored = as("u1")) {
            return RunRequestService.get().create(job, values, "month-end batch", "a1").getId();
        }
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
