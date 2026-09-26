package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression cover for the approval screen's "recent runs" window, requested by ui-dev for
 * commit d6c0878. Matrix rows T-UI-01 (the allowed window sizes), T-UI-02 (every other value
 * falls back to the default of 5 without an error page), T-UI-03 (the window is not a way
 * around the P-09 visibility model), T-UI-04 (a deleted target job still renders, with no run
 * history) and T-UI-05 (the post-approval notice is bound to APPROVED + not yet executed).
 *
 * IMPORTANT — provenance: unlike every other row in the matrix, T-UI-* is NOT derived from a
 * SPEC acceptance criterion. docs/SPEC.md says nothing about a recent-run table on the approval
 * screen or about a {@code runs} query parameter; the contract asserted here is the one the
 * implementation chose (reported by ui-dev), and these rows pin it so it cannot change silently.
 * Whether it belongs in SPEC is a human decision — see matrix note 40.
 *
 * Detection technique: the rows never assume a DOM shape. A run is "listed" when the page
 * references it either as a build URL of the job ({@code job/<name>/<number>/}) or as a
 * {@code #<number>} token that is not part of a longer token (so CSS colours such as
 * {@code #1f2937} cannot be mistaken for build #1).
 *
 * Written from docs/SPEC.md, docs/TEST-MATRIX.md and the ui-dev endpoint contract only
 * (no src/main knowledge).
 */
public class ApprovalScreenRecentRunsTest {

    private static final String JOB = "batch-x";
    /** A second, never-built job, so the notice row's pages carry no run rows at all. */
    private static final String NOTICE_JOB = "batch-y";

    private static final String REQUESTER = "u1";
    /** Designated approver WITHOUT Item/Read on the job (P-09: still sees the request itself). */
    private static final String BLIND_APPROVER = "a1";
    /** Designated approver WITH Item/Read on the job (sees the job's run history). */
    private static final String READING_APPROVER = "a2";
    private static final String ADMIN = "admin";

    /** How many builds the job has before any request is opened. */
    private static final int BUILDS = 12;
    private static final int DEFAULT_RUNS = 5;

    /** The window sizes ui-dev's contract allows. */
    private static final List<String> ALLOWED = Arrays.asList("5", "10", "20", "50");

    /** Values that must silently fall back to the default of 5 (no error page). */
    private static final List<String> UNSUPPORTED = Arrays.asList(
            "100000", "-1", "0", "7", "abc", "", "99999999999999999999", "5.5", "10 ");

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private final Map<String, JenkinsRule.WebClient> clients = new HashMap<>();

    private FreeStyleProject job;
    private FreeStyleProject noticeJob;
    private BatchControlGlobalConfiguration cfg;

    @Before
    public void setUp() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to(ADMIN)
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST)
                        .everywhere().to(REQUESTER)
                // a1 deliberately has NO Item/Read anywhere (P-09 fixture, same as T-SEC-08)
                .grant(Jenkins.READ, BatchControlPermissions.APPROVE).everywhere().to(BLIND_APPROVER)
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to(READING_APPROVER));

        cfg = BatchControlGlobalConfiguration.get();
        // Recording stays active through change control (SPEC 9: any switch on), while run
        // control stays off for the fixture builds, so the job's history can be produced
        // without approvals and without the D-31 creation default interfering.
        cfg.setChangeControlEnabled(true);
        cfg.setRunControlEnabled(false);
        cfg.setApprovers(Arrays.asList(BLIND_APPROVER, READING_APPROVER));
        cfg.save();

        job = j.createFreeStyleProject(JOB);
        for (int i = 0; i < BUILDS; i++) {
            j.buildAndAssertSuccess(job);
        }
        assertEquals("fixture: the job must have " + BUILDS + " runs to page through",
                BUILDS, job.getBuilds().size());

        noticeJob = j.createFreeStyleProject(NOTICE_JOB);

        cfg.setRunControlEnabled(true);
        cfg.save();
        job.addProperty(new BatchControlJobProperty(true));
        noticeJob.addProperty(new BatchControlJobProperty(true));
    }

    /**
     * T-UI-01: the approval screen shows the latest {@code runs} runs of the target job, for
     * each allowed value, and the latest 5 when the parameter is absent.
     */
    @Test
    public void t_ui_01_recentRunWindowFollowsTheRunsParameter() throws Exception {
        RunRequest request = createRequest(READING_APPROVER, "runs-window review");

        assertWindow("no runs parameter", request.getId(), null, DEFAULT_RUNS);
        for (String allowed : ALLOWED) {
            assertWindow("runs=" + allowed, request.getId(), allowed,
                    Integer.parseInt(allowed));
        }
    }

    /**
     * T-UI-02: every value outside the allowed list falls back to the default of 5 silently —
     * HTTP 200, the default window, and no error page or parse failure surfaced to the user.
     */
    @Test
    public void t_ui_02_unsupportedRunsValuesFallBackToTheDefault() throws Exception {
        RunRequest request = createRequest(READING_APPROVER, "runs-fallback review");

        for (String unsupported : UNSUPPORTED) {
            String label = "runs=[" + unsupported + "]";
            WebResponse response = detail(READING_APPROVER, request.getId(), unsupported);
            assertEquals(label + " must not produce an error page", 200, response.getStatusCode());

            String html = response.getContentAsString();
            assertFalse(label + " must not leak a parse failure into the page",
                    html.contains("NumberFormatException"));
            assertFalse(label + " must not render a stack trace",
                    html.contains("Stack Trace"));
            assertWindowIn(label, html, DEFAULT_RUNS);
        }
    }

    /**
     * T-UI-03 (P0, P-09 must not be bypassable): the designated approver a1 can open the
     * request (P-09 makes the request itself visible to its approver — T-SEC-08) but holds no
     * Item/Read on the target job, so no {@code runs} value may disclose a single run of that
     * job. The reading approver a2 is the fixture control: the very same table does list runs
     * when the viewer may read the job, so this row measures the gate and not an empty screen.
     */
    @Test
    public void t_ui_03_runsParameterCannotBypassP09Visibility() throws Exception {
        RunRequest blindRequest = createRequest(BLIND_APPROVER, "p09 window probe");
        RunRequest readableRequest = createRequest(READING_APPROVER, "p09 control probe");

        // fixture control: with Item/Read the widest window really does list the oldest run
        String controlHtml = detail(READING_APPROVER, readableRequest.getId(), "50")
                .getContentAsString();
        assertTrue("fixture control: an approver holding Item/Read must see the job's runs, "
                        + "otherwise this row cannot measure the visibility gate",
                showsRun(controlHtml, JOB, BUILDS) && showsRun(controlHtml, JOB, 1));

        // fixture control: P-09 still lets the designated approver open the request itself
        assertEquals("P-09: the designated approver must be able to open the request detail",
                200, detail(BLIND_APPROVER, blindRequest.getId(), null).getStatusCode());

        for (String value : concat(ALLOWED, UNSUPPORTED)) {
            String label = "runs=[" + value + "] as an approver without Item/Read";
            WebResponse response = detail(BLIND_APPROVER, blindRequest.getId(), value);
            int code = response.getStatusCode();
            assertTrue(label + " must answer 200, 403 or 404, got " + code,
                    code == 200 || code == 403 || code == 404);
            assertNoRunDisclosed(label, JOB, response.getContentAsString());
        }
    }

    /**
     * T-UI-04 (P0, same non-bypass clause): when the target job is gone, no {@code runs} value
     * may produce run rows — and the screen must still render instead of failing.
     */
    @Test
    public void t_ui_04_deletedJobShowsNoRunHistoryForAnyRunsValue() throws Exception {
        RunRequest request = createRequest(READING_APPROVER, "deleted job window probe");
        job.delete();
        assertNull("fixture: the target job must be gone",
                j.jenkins.getItemByFullName(JOB, FreeStyleProject.class));

        for (String value : concat(ALLOWED, UNSUPPORTED)) {
            String label = "runs=[" + value + "] with the target job deleted";
            WebResponse response = detail(READING_APPROVER, request.getId(), value);
            assertTrue(label + " must not fail the screen, got HTTP " + response.getStatusCode(),
                    response.getStatusCode() < 500);
            String html = response.getContentAsString();
            assertFalse(label + " must not render a stack trace", html.contains("Stack Trace"));
            assertNoRunDisclosed(label, JOB, html);
        }
    }

    /**
     * T-UI-05: the post-approval notice is bound to "APPROVED and not executed yet". SPEC pins
     * no wording for it (matrix note 40), so the row asserts the state it is keyed on: the
     * approved-but-unexecuted request renders APPROVED with no run attached; the executed one
     * renders EXECUTED and links its run; and a REJECTED or CANCELLED request never renders
     * the approved state at all.
     */
    @Test
    public void t_ui_05_approvedStateIsBoundToTheUnexecutedRequest() throws Exception {
        // (1) APPROVED, not executed yet - matrix note 16: quiet down keeps the submission idle
        RunRequest approved = createNoticeRequest("notice: approved not run");
        j.jenkins.doQuietDown();
        approveAs(READING_APPROVER, approved.getId(), "notice-approved-comment");
        j.jenkins.getQueue().clear();
        RunRequest reloadedApproved = RunRequestService.get().load(approved.getId());
        assertEquals(RequestStatus.APPROVED, reloadedApproved.getStatus());
        assertNull("fixture: the approved request must not have executed yet",
                reloadedApproved.getExecutedRunId());

        String approvedHtml = detail(READING_APPROVER, approved.getId(), null).getContentAsString();
        assertTrue("the approved, not yet executed request must render its APPROVED state",
                approvedHtml.contains("APPROVED"));
        assertNoRunDisclosed("an approved but unexecuted request", NOTICE_JOB, approvedHtml);
        j.jenkins.doCancelQuietDown();

        // (2) EXECUTED
        RunRequest executed = createNoticeRequest("notice: executed");
        approveAs(READING_APPROVER, executed.getId(), "notice-executed-comment");
        j.waitUntilNoActivity();
        RunRequest reloadedExecuted = RunRequestService.get().load(executed.getId());
        assertNotNull("fixture: the approved request must have executed",
                reloadedExecuted.getExecutedRunId());
        String executedHtml = detail(READING_APPROVER, executed.getId(), "50").getContentAsString();
        assertTrue("an executed request must render its EXECUTED state",
                executedHtml.contains("EXECUTED"));
        assertTrue("an executed request must point at the run it produced",
                showsRun(executedHtml, NOTICE_JOB, 1));

        // (3) REJECTED and (4) CANCELLED: the approved state must not be rendered at all
        RunRequest rejected = createNoticeRequest("notice: rejected");
        rejectAs(READING_APPROVER, rejected.getId(), "notice-rejected-comment");
        String rejectedHtml = detail(READING_APPROVER, rejected.getId(), null).getContentAsString();
        assertTrue("a rejected request must render its REJECTED state",
                rejectedHtml.contains("REJECTED"));
        assertFalse("a rejected request must not render the approved state",
                rejectedHtml.contains("APPROVED"));

        RunRequest cancelled = createNoticeRequest("notice: cancelled");
        cancelAs(REQUESTER, cancelled.getId());
        String cancelledHtml = detail(READING_APPROVER, cancelled.getId(), null)
                .getContentAsString();
        assertTrue("a cancelled request must render its CANCELLED state",
                cancelledHtml.contains("CANCELLED"));
        assertFalse("a cancelled request must not render the approved state",
                cancelledHtml.contains("APPROVED"));
    }

    // ---------------------------------------------------------------- assertions

    /** Fetches the screen and asserts the window it renders, for one {@code runs} value. */
    private void assertWindow(String label, String requestId, String runsValue, int expected)
            throws Exception {
        WebResponse response = detail(READING_APPROVER, requestId, runsValue);
        assertEquals(label + " must render", 200, response.getStatusCode());
        assertWindowIn(label, response.getContentAsString(), expected);
    }

    /**
     * The latest {@code expected} runs (capped by how many exist) must be listed, and the next
     * older one must not be - so a screen that simply lists everything fails.
     */
    private void assertWindowIn(String label, String html, int expected) {
        int shown = Math.min(expected, BUILDS);
        for (int number = BUILDS; number > BUILDS - shown; number--) {
            assertTrue(label + ": run #" + number + " is inside the window and must be listed",
                    showsRun(html, JOB, number));
        }
        if (shown < BUILDS) {
            assertFalse(label + ": run #" + (BUILDS - shown) + " is outside the window and "
                            + "must not be listed",
                    showsRun(html, JOB, BUILDS - shown));
        }
    }

    /** No run of the named job may be referenced by the page. */
    private void assertNoRunDisclosed(String label, String jobName, String html) {
        for (int number = 1; number <= BUILDS; number++) {
            assertFalse(label + " must not disclose run #" + number + " of " + jobName,
                    showsRun(html, jobName, number));
        }
    }

    /**
     * True when the page references run {@code number} of {@code jobName}, either as the build
     * URL or as a {@code #<number>} token. The token must not be followed by another letter or
     * digit, so neither a longer build number nor a CSS colour can match.
     */
    private static boolean showsRun(String html, String jobName, int number) {
        return html.contains("job/" + jobName + "/" + number + "/")
                || Pattern.compile("#" + number + "(?![0-9A-Za-z])").matcher(html).find();
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private RunRequest createRequest(String approver, String reason) {
        try (ACLContext ignored = as(REQUESTER)) {
            return RunRequestService.get().create(job, new LinkedHashMap<String, String>(),
                    reason, approver);
        }
    }

    private RunRequest createNoticeRequest(String reason) {
        try (ACLContext ignored = as(REQUESTER)) {
            return RunRequestService.get().create(noticeJob, new LinkedHashMap<String, String>(),
                    reason, READING_APPROVER);
        }
    }

    private void approveAs(String userId, String requestId, String comment) {
        try (ACLContext ignored = as(userId)) {
            RunRequestService.get().approve(requestId, comment);
        }
    }

    private void rejectAs(String userId, String requestId, String comment) {
        try (ACLContext ignored = as(userId)) {
            RunRequestService.get().reject(requestId, comment);
        }
    }

    private void cancelAs(String userId, String requestId) {
        try (ACLContext ignored = as(userId)) {
            RunRequestService.get().cancel(requestId);
        }
    }

    private WebResponse detail(String userId, String requestId, String runsValue)
            throws Exception {
        String relative = "batch-control/requests/" + requestId + "/";
        if (runsValue != null) {
            relative += "?runs=" + URLEncoder.encode(runsValue, StandardCharsets.UTF_8);
        }
        return client(userId)
                .getPage(new WebRequest(new URL(j.getURL(), relative), HttpMethod.GET))
                .getWebResponse();
    }

    private JenkinsRule.WebClient client(String userId) throws Exception {
        JenkinsRule.WebClient existing = clients.get(userId);
        if (existing != null) {
            return existing;
        }
        JenkinsRule.WebClient created = j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false)
                .login(userId);
        clients.put(userId, created);
        return created;
    }
}
