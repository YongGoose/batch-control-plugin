package io.jenkins.plugins.batchcontrol;

import com.chikli.hudson.plugin.naginator.FixedDelay;
import com.chikli.hudson.plugin.naginator.NaginatorPublisher;
import com.chikli.hudson.plugin.naginator.NaginatorRetryAction;
import com.sonyericsson.rebuild.RebuildAction;
import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.htmlunit.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.token;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertApprovedRunQueuedExactlyOnce;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.get;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.post;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.requestAndApprove;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.secureWithRunControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6 (e2e-03 DEF-03): "every refused retry, rebuild or re-queue of an approved or manual
 * run is recorded in the history (MARKER_REUSE_BLOCKED when it presents a consumed marker,
 * otherwise TRIGGER_BLOCKED with the cause kind), not only logged." Matrix rows T-06-61 ..
 * T-06-65 (note 118).
 *
 * <p>The refusals themselves are T-06-24/25/33/34; these rows add the record. Every job is
 * activated first (SPEC 6a), so the only reason left to refuse is the approval rule — a record
 * for a missing activation (T-06-54) cannot stand in for the one these rows ask for. Whether
 * naginator or rebuild copies the approval marker onto the new submission is the other
 * plugin's business, so for a re-run of an approved run either record type is accepted; a
 * MARKER_REUSE_BLOCKED record must then name the consumed request (D-30), and for a re-run of a
 * manual run (no marker exists) the record is TRIGGER_BLOCKED. Each job is used for one refusal
 * only, so the per-hour coalescing of TRIGGER_BLOCKED cannot hide a record.
 *
 * <p>Written from docs/SPEC.md, docs/reports/e2e-03.md and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class RefusedRerunAuditTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        secureWithRunControl(j);
    }

    /**
     * T-06-61 (DEF-03, PR-05): naginator's automatic retry of a failed approved run on an
     * activated job is refused and recorded.
     */
    @Test
    public void t_06_61_refusedAutomaticRetryOfApprovedRunIsRecorded() throws Exception {
        FreeStyleProject job = approvalRequired("rr-nag-auto");
        job.getBuildersList().add(new FailureBuilder());
        job.getPublishersList().add(new NaginatorPublisher("", false, false, false, 1, new FixedDelay(0)));
        assertTrue(rerunRecords(job).isEmpty(), "fixture: no refusal record before the approved run");

        RunRequest request = requestAndApprove(job);
        j.waitUntilNoActivity();

        assertBlocked(j, job, 2, 1);
        j.assertBuildStatus(Result.FAILURE, job.getBuildByNumber(1));
        assertRecorded(job, request, null);
    }

    /**
     * T-06-62 (DEF-03, PR-05): u1 presses naginator's Retry on the failed approved run; the
     * refusal is recorded, and a MARKER_REUSE_BLOCKED record names u1 as the actor.
     */
    @Test
    public void t_06_62_refusedManualRetryOfApprovedRunIsRecorded() throws Exception {
        FreeStyleProject job = approvalRequired("rr-nag-manual");
        job.getBuildersList().add(new FailureBuilder());

        RunRequest request = requestAndApprove(job);
        FreeStyleBuild failed = (FreeStyleBuild) assertApprovedRunQueuedExactlyOnce(j, job);
        j.assertBuildStatus(Result.FAILURE, failed);
        assertNotNull(failed.getAction(NaginatorRetryAction.class), "fixture: naginator must offer Retry");
        assertTrue(rerunRecords(job).isEmpty(), "fixture: a clean approved run leaves no refusal record");

        post(j, "u1", failed.getUrl() + "retry/");

        assertBlocked(j, job, 2, 1);
        assertRecorded(job, request, "u1");
    }

    /**
     * T-06-74 (e2e-run3 DEF-32, PR-05): on a job with naginator's automatic retry, the approved run
     * fails and the automatic retry is refused and recorded as SYSTEM (D-47: unattended). Within
     * the same hour u1 presses Retry on the failed run; that refusal must be recorded as u1, with
     * the retry named, and not folded into the SYSTEM record of the automatic retry (SPEC 6: every
     * refused retry is recorded; usability line: recorded history names who did what). Note 146.
     */
    @Test
    public void t_06_74_manualRetryRefusalIsRecordedAsTheUserNotMergedIntoSystem() throws Exception {
        FreeStyleProject job = approvalRequired("rr-nag-both");
        job.getBuildersList().add(new FailureBuilder());
        job.getPublishersList().add(new NaginatorPublisher("", false, false, false, 1, new FixedDelay(0)));

        requestAndApprove(job);
        j.waitUntilNoActivity();
        assertBlocked(j, job, 2, 1);
        FreeStyleBuild failed = job.getBuildByNumber(1);
        j.assertBuildStatus(Result.FAILURE, failed);
        List<ChangeRecord> automatic = rerunRecords(job);
        assertTrue(!automatic.isEmpty(), "fixture: the refused automatic retry must be recorded: " + describe(automatic));
        assertTrue(automatic.stream().noneMatch(r -> "u1".equals(r.getUser())), "fixture: before u1 acts no refusal"
                + " record may name u1: " + describe(automatic));
        assertNotNull(failed.getAction(NaginatorRetryAction.class), "fixture: naginator must offer Retry");

        post(j, "u1", failed.getUrl() + "retry/");
        assertBlocked(j, job, 2, 1);

        List<ChangeRecord> records = rerunRecords(job);
        List<ChangeRecord> byU1 = records.stream().filter(r -> "u1".equals(r.getUser())).collect(Collectors.toList());
        assertTrue(!byU1.isEmpty(), "u1's refused Retry must be recorded with user = u1, not merged into the automatic"
                + " retry's record: " + describe(records));
        for (ChangeRecord record : byU1) {
            if (record.getType() == ChangeType.TRIGGER_BLOCKED) {
                assertTrue(String.valueOf(record.getDetail()).toLowerCase(Locale.ROOT).contains("retry"),
                        "u1's TRIGGER_BLOCKED record must name the retry as its cause kind: " + describe(byU1));
            }
        }
        assertTrue(records.stream().anyMatch(r -> !"u1".equals(r.getUser())
                        && String.valueOf(r.getUser()).equalsIgnoreCase("SYSTEM")),
                "the automatic retry's record stays attributed to SYSTEM (D-47): " + describe(records));
    }

    /**
     * T-06-75 (security-18 S-18-03): u1 presses naginator's Retry on a failed build that was
     * started by a remote token call (its causes include a {@code RemoteCause}; the build ran
     * before the job was made approval-required). The click is a person re-running a build, so it
     * is refused like T-06-74: an HTML refusal page naming approval (not the plain-text answer
     * meant for a token caller), a TRIGGER_BLOCKED record by u1 naming the retry, and no
     * REMOTE_RUN_BLOCKED record (SPEC 6; usability line: history names who did what). Note 151.
     */
    @Test
    public void t_06_75_retryOfRemoteStartedBuildIsRecordedAsUserRetry() throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("rr-remote-retry"));
        job.getBuildersList().add(new FailureBuilder());
        BatchControlFixtures.activate(job);
        FreeStyleBuild remote = j.assertBuildStatus(Result.FAILURE,
                job.scheduleBuild2(0, new Cause.RemoteCause("127.0.0.1", "token call")));
        assertNotNull(remote.getCause(Cause.RemoteCause.class), "fixture: build #1 must carry a RemoteCause");
        assertNotNull(remote.getAction(NaginatorRetryAction.class), "fixture: naginator must offer Retry");
        setBatchControl(job, new BatchControlJobProperty(true));
        j.waitUntilNoActivity();
        int remoteBlockedBefore = remoteRunBlocked(job).size();

        Page answer = post(j, "u1", remote.getUrl() + "retry/");

        assertBlocked(j, job, 2, 1);
        String type = String.valueOf(answer.getWebResponse().getContentType());
        assertTrue(type.startsWith("text/html"), "a person's Retry must be answered with the HTML refusal page, not the"
                + " token caller's plain text; got " + type + ": " + answer.getWebResponse().getContentAsString());
        assertTrue(UsabilityFixtures.text(answer).toLowerCase(Locale.ROOT).contains("approv"),
                "the refusal page must name approval");
        List<ChangeRecord> records = rerunRecords(job);
        assertTrue(records.stream().anyMatch(r -> r.getType() == ChangeType.TRIGGER_BLOCKED && "u1".equals(r.getUser())
                        && String.valueOf(r.getDetail()).toLowerCase(Locale.ROOT).contains("retry")),
                "u1's Retry must be recorded as TRIGGER_BLOCKED cause=RETRY by u1: " + describe(records)
                        + " remote=" + describe(remoteRunBlocked(job)));
        assertEquals(remoteBlockedBefore, remoteRunBlocked(job).size(), "a person's Retry must not be recorded as a"
                + " remote run submission: " + describe(remoteRunBlocked(job)));
    }

    /**
     * T-06-76 (security-19 S-19-01): a submission made inside an HTTP request by u1 that carries a
     * naginator retry cause and a copied RemoteCause but no UserIdCause is not a person's Retry
     * (T-06-74 needs the fresh UserIdCause naginator adds on a click); it is an unattended re-run
     * of an approved run and must be refused on an activated approval-required job, as the same
     * submission is on a non-request thread (T-06-34). Produced through a test root action that
     * calls the public {@code Queue.schedule2}. The false-positive guard: the same action queues a
     * build of an uncontrolled, activated job. Note 154.
     */
    @Test
    public void t_06_76_retryCauseWithRemoteCauseButNoUserOnRequestThreadIsRefused() throws Exception {
        io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration cfg =
                io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.save();

        FreeStyleProject free = uncontrolled(j.createFreeStyleProject("rr-s19-free"));
        BatchControlFixtures.activate(free);
        try (ACLContext ignored = ACL.as2(token("u1"))) {
            j.assertBuildStatusSuccess(free.scheduleBuild2(0, new Cause.UserIdCause()));
        }
        Page guard = post(j, "u1", "s19-probe/schedule?job=rr-s19-free");
        assertEquals(200, guard.getWebResponse().getStatusCode(), "fixture: the probe action must answer");
        j.waitUntilNoActivity();
        assertEquals(2, free.getBuilds().size(), "fixture: the probe must queue a build of an uncontrolled, activated"
                + " job, or this row measures nothing: " + guard.getWebResponse().getContentAsString());

        FreeStyleProject job = approvalRequired("rr-s19");
        requestAndApprove(job);
        FreeStyleBuild approved = (FreeStyleBuild) assertApprovedRunQueuedExactlyOnce(j, job);
        j.assertBuildStatusSuccess(approved);

        post(j, "u1", "s19-probe/schedule?job=rr-s19");

        assertBlocked(j, job, 2, 1);
    }

    /**
     * T-06-77 (e2e-03 DEF-32, real path): the same situation as T-06-74, but u1 presses Retry the
     * way a browser does: logged in, on the failed build's page, clicking naginator's "Retry" task
     * link (a POST link that core's JavaScript sends with the crumb header). u1's refusal must be
     * recorded as TRIGGER_BLOCKED naming the retry, by u1, and no build follows (SPEC 6; D-49 keeps
     * the link visible and requires the click to be refused and recorded). Note 158.
     */
    @Test
    public void t_06_77_retryClickedInTheBrowserIsRecordedAsTheUser() throws Exception {
        FreeStyleProject job = approvalRequired("rr-nag-click");
        job.getBuildersList().add(new FailureBuilder());
        job.getPublishersList().add(new NaginatorPublisher("", false, false, false, 1, new FixedDelay(0)));

        requestAndApprove(job);
        j.waitUntilNoActivity();
        assertBlocked(j, job, 2, 1);
        FreeStyleBuild failed = job.getBuildByNumber(1);
        j.assertBuildStatus(Result.FAILURE, failed);
        assertTrue(rerunRecords(job).stream().noneMatch(r -> "u1".equals(r.getUser())),
                "fixture: before u1 acts no refusal record may name u1: " + describe(rerunRecords(job)));

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("u1");
        wc.getOptions().setJavaScriptEnabled(true);
        wc.getOptions().setThrowExceptionOnScriptError(false);
        List<String> retryPosts = new java.util.concurrent.CopyOnWriteArrayList<>();
        new org.htmlunit.util.WebConnectionWrapper(wc) {
            @Override
            public org.htmlunit.WebResponse getResponse(org.htmlunit.WebRequest request) throws java.io.IOException {
                org.htmlunit.WebResponse response = super.getResponse(request);
                if (request.getHttpMethod() == org.htmlunit.HttpMethod.POST
                        && request.getUrl().getPath().endsWith("/retry/")) {
                    retryPosts.add(response.getStatusCode() + " crumb=" + request.getAdditionalHeaders().keySet());
                }
                return response;
            }
        };
        org.htmlunit.html.HtmlPage buildPage = wc.getPage(failed);
        assertEquals(200, buildPage.getWebResponse().getStatusCode(), "fixture: u1 must open the failed build's page");
        org.htmlunit.html.HtmlAnchor retry = null;
        for (org.htmlunit.html.HtmlAnchor a : buildPage.getAnchors()) {
            String href = a.getHrefAttribute();
            if (href != null && href.endsWith(failed.getUrl() + "retry/")) {
                retry = a;
                break;
            }
        }
        assertNotNull(retry, "fixture: the failed build's page must offer naginator's Retry link (D-49)");
        retry.click();
        wc.waitForBackgroundJavaScript(10000);
        assertEquals(1, retryPosts.size(), "fixture: the click must have sent exactly one POST to retry/, as a browser"
                + " does: " + retryPosts);

        assertBlocked(j, job, 2, 1);
        List<ChangeRecord> records = rerunRecords(job);
        assertTrue(records.stream().anyMatch(r -> r.getType() == ChangeType.TRIGGER_BLOCKED && "u1".equals(r.getUser())
                        && String.valueOf(r.getDetail()).toLowerCase(Locale.ROOT).contains("retry")),
                "u1's Retry clicked in the browser must be recorded as TRIGGER_BLOCKED cause=RETRY by u1: "
                        + describe(records));
    }

    /**
     * T-06-78 (D-51, e2e-03 DEF-32 root cause): a person's refused re-run is recorded per attempt.
     * u1 clicks Retry (browser path, as T-06-77) on failed build #1, again on #1 a few seconds
     * later, then on failed build #2: exactly two TRIGGER_BLOCKED records by u1, one naming #1 and
     * one naming #2 (the double click on #1 is merged). Unattended refusals keep the hourly
     * coalescing: on a second job, two refused automatic retries within the hour leave one record.
     * Note 161.
     */
    @Test
    public void t_06_78_personRetryIsRecordedPerAttemptAndUnattendedStaysCoalesced() throws Exception {
        FreeStyleProject job = approvalRequired("rr-d51");
        job.getBuildersList().add(new FailureBuilder());
        requestAndApprove(job);
        j.waitUntilNoActivity();
        requestAndApprove(job);
        j.waitUntilNoActivity();
        FreeStyleBuild first = job.getBuildByNumber(1);
        FreeStyleBuild second = job.getBuildByNumber(2);
        assertNotNull(second, "fixture: two approved runs must have produced #1 and #2");
        j.assertBuildStatus(Result.FAILURE, first);
        j.assertBuildStatus(Result.FAILURE, second);

        assertEquals(1, clickRetryInBrowser("u1", first), "fixture: the click on #1 must send one POST");
        assertEquals(1, clickRetryInBrowser("u1", first), "fixture: the repeated click on #1 must send one POST");
        assertEquals(1, clickRetryInBrowser("u1", second), "fixture: the click on #2 must send one POST");
        assertBlocked(j, job, 3, 2);

        List<ChangeRecord> byU1 = rerunRecords(job).stream()
                .filter(r -> r.getType() == ChangeType.TRIGGER_BLOCKED && "u1".equals(r.getUser()))
                .collect(Collectors.toList());
        assertEquals(2, byU1.size(), "u1's attempts on #1 (twice within seconds) and #2 must leave exactly two"
                + " TRIGGER_BLOCKED records, one per attempt (D-51): " + describe(rerunRecords(job)));
        java.util.regex.Pattern names1 = java.util.regex.Pattern.compile("(#|/)1\\b");
        java.util.regex.Pattern names2 = java.util.regex.Pattern.compile("(#|/)2\\b");
        assertEquals(1, byU1.stream().filter(r -> names1.matcher(r.getTarget() + " " + r.getDetail()).find()
                        && !names2.matcher(r.getTarget() + " " + r.getDetail()).find()).count(),
                "one record must name build #1: " + describe(byU1));
        assertEquals(1, byU1.stream().filter(r -> names2.matcher(r.getTarget() + " " + r.getDetail()).find()
                        && !names1.matcher(r.getTarget() + " " + r.getDetail()).find()).count(),
                "one record must name build #2: " + describe(byU1));

        FreeStyleProject auto = approvalRequired("rr-d51-auto");
        auto.getBuildersList().add(new FailureBuilder());
        auto.getPublishersList().add(new NaginatorPublisher("", false, false, false, 1, new FixedDelay(0)));
        requestAndApprove(auto);
        j.waitUntilNoActivity();
        requestAndApprove(auto);
        j.waitUntilNoActivity();
        assertBlocked(j, auto, 3, 2);
        List<ChangeRecord> unattended = ActivationFixtures.recordsFor(ChangeType.TRIGGER_BLOCKED, auto.getFullName());
        assertEquals(1, unattended.size(), "two refused automatic retries of one job within the hour must stay one"
                + " coalesced record: " + describe(unattended));
    }

    /**
     * T-06-79 (D-51a): per user at most 20 per-attempt records of refused re-runs per rolling 10
     * minutes. u1 retries 23 different failed builds within seconds: after 20 there are 20 records
     * by u1, the 21st refusal adds exactly one summary record, and the 22nd and 23rd add no record
     * but change the summary's text (its count). Every attempt is refused. Note 162.
     */
    @Test
    public void t_06_79_perUserBudgetEndsInOneCountingSummaryRecord() throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("rr-budget"));
        job.getBuildersList().add(new FailureBuilder());
        BatchControlFixtures.activate(job);
        int builds = 23;
        for (int i = 0; i < builds; i++) {
            try (ACLContext ignored = ACL.as2(token("u1"))) {
                j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, new Cause.UserIdCause()));
            }
        }
        setBatchControl(job, new BatchControlJobProperty(true));
        j.waitUntilNoActivity();
        java.util.Set<String> baseline = byUser("u1").stream().map(ChangeRecord::getId).collect(Collectors.toSet());
        baselineIds.set(baseline);

        for (int n = 1; n <= 20; n++) {
            post(j, "u1", job.getBuildByNumber(n).getUrl() + "retry/");
        }
        assertBlocked(j, job, builds + 1, builds);
        List<ChangeRecord> twenty = byUser("u1");
        assertEquals(20, twenty.size(), "20 refused retries of 20 builds must leave 20 per-attempt records: "
                + describe(twenty));

        post(j, "u1", job.getBuildByNumber(21).getUrl() + "retry/");
        List<ChangeRecord> withSummary = byUser("u1");
        assertEquals(21, withSummary.size(), "the 21st refusal in the window must add exactly one summary record: "
                + describe(withSummary));
        ChangeRecord summary = withSummary.stream().filter(r -> twenty.stream().noneMatch(t -> t.getId().equals(r.getId())))
                .findFirst().orElseThrow();
        String afterFirst = summary.getDetail();

        post(j, "u1", job.getBuildByNumber(22).getUrl() + "retry/");
        post(j, "u1", job.getBuildByNumber(23).getUrl() + "retry/");
        assertBlocked(j, job, builds + 1, builds);
        List<ChangeRecord> after = byUser("u1");
        assertEquals(21, after.size(), "further refusals in the window must not add records: " + describe(after));
        ChangeRecord counted = after.stream().filter(r -> r.getId().equals(summary.getId())).findFirst().orElseThrow();
        assertTrue(!String.valueOf(counted.getDetail()).equals(String.valueOf(afterFirst)),
                "the summary record must count the further refusals (its text must change): before '" + afterFirst
                        + "', after '" + counted.getDetail() + "'");
    }

    /**
     * T-06-80 (D-51a): a refused Replay by a person is recorded per attempt and names the build.
     * The administrator (Replay needs Run/Replay) replays #1 and #2 of an approval-required
     * Pipeline job: two TRIGGER_BLOCKED records by admin, one naming #1, one naming #2. Note 162.
     */
    @Test
    public void t_06_80_personReplayIsRecordedPerAttemptNamingTheBuild() throws Exception {
        org.jenkinsci.plugins.workflow.job.WorkflowJob pipe = uncontrolled(
                j.createProject(org.jenkinsci.plugins.workflow.job.WorkflowJob.class, "rr-replay"));
        pipe.setDefinition(new org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition("echo 'hello'", true));
        BatchControlFixtures.activateAsAdmin(pipe);
        j.buildAndAssertSuccess(pipe);
        j.buildAndAssertSuccess(pipe);
        setBatchControl(pipe, new BatchControlJobProperty(true));

        for (int n = 1; n <= 2; n++) {
            JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
            List<org.htmlunit.util.NameValuePair> params = new ArrayList<>();
            params.add(new org.htmlunit.util.NameValuePair("mainScript", "echo 'replayed'"));
            params.add(new org.htmlunit.util.NameValuePair("json", "{\"mainScript\":\"echo 'replayed'\"}"));
            org.htmlunit.WebRequest request = new org.htmlunit.WebRequest(
                    wc.createCrumbedUrl(pipe.getUrl() + n + "/replay/run"), org.htmlunit.HttpMethod.POST);
            request.setRequestParameters(params);
            wc.getPage(request);
        }
        assertBlocked(j, pipe, 3, 2);

        List<ChangeRecord> byAdmin = ActivationFixtures.recordsFor(ChangeType.TRIGGER_BLOCKED, pipe.getFullName()).stream()
                .filter(r -> "admin".equals(r.getUser())).collect(Collectors.toList());
        assertEquals(2, byAdmin.size(), "two refused Replays of #1 and #2 by a person must leave two records (D-51a): "
                + describe(ActivationFixtures.recordsFor(ChangeType.TRIGGER_BLOCKED, pipe.getFullName())));
        assertEquals(1, byAdmin.stream().filter(r -> namesOnly(r, 1)).count(), "one record must name #1: " + describe(byAdmin));
        assertEquals(1, byAdmin.stream().filter(r -> namesOnly(r, 2)).count(), "one record must name #2: " + describe(byAdmin));
    }

    /**
     * T-06-81 (D-51a): a refused Rebuild record names the re-run build. u1 rebuilds #2 of a job
     * with builds #1 and #2: the refusal record by u1 names #2 and not #1. Note 162.
     */
    @Test
    public void t_06_81_refusedRebuildRecordNamesTheRerunBuild() throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("rr-rebuild-names"));
        BatchControlFixtures.activate(job);
        for (int i = 0; i < 2; i++) {
            try (ACLContext ignored = ACL.as2(token("u1"))) {
                j.assertBuildStatusSuccess(job.scheduleBuild2(0, new Cause.UserIdCause()));
            }
        }
        setBatchControl(job, new BatchControlJobProperty(true));
        FreeStyleBuild second = job.getBuildByNumber(2);
        assertNotNull(second.getAction(RebuildAction.class), "fixture: the rebuild plugin must offer its action");

        post(j, "u1", second.getUrl() + "rebuild/");

        assertBlocked(j, job, 3, 2);
        List<ChangeRecord> byU1 = rerunRecords(job).stream().filter(r -> "u1".equals(r.getUser()))
                .collect(Collectors.toList());
        assertEquals(1, byU1.size(), "the refused Rebuild must leave one record by u1: " + describe(rerunRecords(job)));
        assertTrue(namesOnly(byU1.get(0), 2), "the refused Rebuild's record must name the re-run build #2: "
                + describe(byU1));
    }

    /** The record names build {@code n} ({@code #n} or {@code /n/}) and no other build number 1..3. */
    private static boolean namesOnly(ChangeRecord r, int n) {
        String text = r.getTarget() + " " + r.getDetail();
        for (int other = 1; other <= 3; other++) {
            boolean names = java.util.regex.Pattern.compile("(#|/)" + other + "\\b").matcher(text).find();
            if (names != (other == n)) {
                return false;
            }
        }
        return true;
    }

    /** Ids of u1's records written before T-06-79's retries (activation and fixture records). */
    private final java.util.concurrent.atomic.AtomicReference<java.util.Set<String>> baselineIds =
            new java.util.concurrent.atomic.AtomicReference<>(java.util.Set.of());

    /** Change records of the current month written by {@code user} after the baseline, whatever their type or target. */
    private List<ChangeRecord> byUser(String user) {
        java.util.Set<String> skip = baselineIds.get();
        return io.jenkins.plugins.batchcontrol.store.FileStore.get().listChangeRecords(java.time.YearMonth.now()).stream()
                .filter(r -> user.equals(r.getUser()) && !skip.contains(r.getId())).collect(Collectors.toList());
    }

    /** Opens {@code build}'s page as {@code userId} with JavaScript and clicks naginator's Retry; returns the POSTs sent to retry/. */
    private int clickRetryInBrowser(String userId, FreeStyleBuild build) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        wc.getOptions().setJavaScriptEnabled(true);
        wc.getOptions().setThrowExceptionOnScriptError(false);
        List<String> posts = new java.util.concurrent.CopyOnWriteArrayList<>();
        new org.htmlunit.util.WebConnectionWrapper(wc) {
            @Override
            public org.htmlunit.WebResponse getResponse(org.htmlunit.WebRequest request) throws java.io.IOException {
                org.htmlunit.WebResponse response = super.getResponse(request);
                if (request.getHttpMethod() == org.htmlunit.HttpMethod.POST
                        && request.getUrl().getPath().endsWith("/retry/")) {
                    posts.add(String.valueOf(response.getStatusCode()));
                }
                return response;
            }
        };
        org.htmlunit.html.HtmlPage page = wc.getPage(build);
        org.htmlunit.html.HtmlAnchor retry = null;
        for (org.htmlunit.html.HtmlAnchor a : page.getAnchors()) {
            String href = a.getHrefAttribute();
            if (href != null && href.endsWith(build.getUrl() + "retry/")) {
                retry = a;
                break;
            }
        }
        assertNotNull(retry, "fixture: " + build + " must offer naginator's Retry link (D-49)");
        retry.click();
        wc.waitForBackgroundJavaScript(10000);
        return posts.size();
    }

    /**
     * Test-only root action: on a request thread, as the calling user, schedules build #1's job
     * with a {@code NaginatorCause} (of build #1) and a {@code RemoteCause}, and no UserIdCause.
     */
    @org.jvnet.hudson.test.TestExtension("t_06_76_retryCauseWithRemoteCauseButNoUserOnRequestThreadIsRefused")
    public static class ScheduleProbe implements hudson.model.RootAction {
        @Override
        public String getIconFileName() {
            return null;
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getUrlName() {
            return "s19-probe";
        }

        @org.kohsuke.stapler.verb.POST
        public org.kohsuke.stapler.HttpResponse doSchedule(@org.kohsuke.stapler.QueryParameter String job) {
            jenkins.model.Jenkins.get().checkPermission(jenkins.model.Jenkins.READ);
            FreeStyleProject p = jenkins.model.Jenkins.get().getItemByFullName(job, FreeStyleProject.class);
            FreeStyleBuild first = p.getBuildByNumber(1);
            hudson.model.queue.ScheduleResult result = jenkins.model.Jenkins.get().getQueue().schedule2(p, 0,
                    new hudson.model.CauseAction(new com.chikli.hudson.plugin.naginator.NaginatorCause(first),
                            new Cause.RemoteCause("127.0.0.1", "token call")));
            return org.kohsuke.stapler.HttpResponses.text(result.isRefused() ? "refused" : "scheduled");
        }
    }

    private static List<ChangeRecord> remoteRunBlocked(FreeStyleProject job) {
        List<ChangeRecord> out = new ArrayList<>();
        for (ChangeType type : ChangeType.values()) {
            if (type.name().equals("REMOTE_RUN_BLOCKED")) {
                out.addAll(ActivationFixtures.recordsFor(type, job.getFullName()));
            }
        }
        return out;
    }

    /**
     * T-06-63 (DEF-03, PR-02): u1 clicks Rebuild on an approved run; the refusal is recorded and
     * the record is in {@code changes.csv}.
     */
    @Test
    public void t_06_63_refusedRebuildOfApprovedRunIsRecordedAndExported() throws Exception {
        FreeStyleProject job = approvalRequired("rr-rebuild-approved");

        RunRequest request = requestAndApprove(job);
        FreeStyleBuild approved = (FreeStyleBuild) assertApprovedRunQueuedExactlyOnce(j, job);
        assertNotNull(approved.getAction(RebuildAction.class), "fixture: the rebuild plugin must offer its action");
        assertTrue(rerunRecords(job).isEmpty(), "fixture: a clean approved run leaves no refusal record");

        post(j, "u1", approved.getUrl() + "rebuild/");

        assertBlocked(j, job, 2, 1);
        ChangeRecord record = assertRecorded(job, request, "u1");

        Page csv = get(j, "admin", "batch-control/history/changes.csv");
        assertEquals(200, csv.getWebResponse().getStatusCode(), "the administrator must download changes.csv");
        String body = csv.getWebResponse().getContentAsString();
        assertTrue(body.lines().anyMatch(line -> line.contains(record.getType().name()) && line.contains("rr-rebuild-approved")),
                "changes.csv must carry the " + record.getType() + " row for rr-rebuild-approved:\n" + body);
    }

    /**
     * T-06-64 (DEF-03): a Rebuild of a manual run that ran before the job required approval (no
     * marker exists) is refused and recorded as TRIGGER_BLOCKED naming a cause kind.
     */
    @Test
    public void t_06_64_refusedRebuildOfManualRunIsRecordedAsTriggerBlocked() throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("rr-rebuild-manual"));
        BatchControlFixtures.activate(job);
        try (ACLContext ignored = ACL.as2(token("u1"))) {
            j.assertBuildStatusSuccess(job.scheduleBuild2(0, new Cause.UserIdCause()));
        }
        setBatchControl(job, new BatchControlJobProperty(true));
        FreeStyleBuild manual = job.getBuildByNumber(1);
        assertNotNull(manual.getAction(RebuildAction.class), "fixture: the rebuild plugin must offer its action");
        assertTrue(rerunRecords(job).isEmpty(), "fixture: no refusal record before the rebuild");

        post(j, "u1", manual.getUrl() + "rebuild/");

        assertBlocked(j, job, 2, 1);
        List<ChangeRecord> records = rerunRecords(job);
        assertEquals(1, records.size(), "the refused rebuild of a manual run must leave exactly one record: " + describe(records));
        ChangeRecord record = records.get(0);
        assertEquals(ChangeType.TRIGGER_BLOCKED, record.getType(), "no marker was presented, so the record is TRIGGER_BLOCKED");
        assertNotNull(record.getDetail(), "the TRIGGER_BLOCKED record must name the cause kind");
        assertTrue(!record.getDetail().isBlank(), "the TRIGGER_BLOCKED record must name the cause kind");
    }

    /**
     * T-06-65 (DEF-03, negative twin): an admitted Rebuild (uncontrolled, activated job) and a
     * clean approved run leave no refusal record.
     */
    @Test
    public void t_06_65_admittedRerunsLeaveNoRefusalRecord() throws Exception {
        FreeStyleProject free = uncontrolled(j.createFreeStyleProject("rr-free"));
        BatchControlFixtures.activate(free);
        try (ACLContext ignored = ACL.as2(token("u1"))) {
            j.assertBuildStatusSuccess(free.scheduleBuild2(0, new Cause.UserIdCause()));
        }
        post(j, "u1", free.getBuildByNumber(1).getUrl() + "rebuild/");
        j.waitUntilNoActivity();
        assertEquals(2, free.getBuilds().size(), "fixture: the rebuild of an uncontrolled job must run");
        assertTrue(rerunRecords(free).isEmpty(), "an admitted rebuild must leave no refusal record: " + describe(rerunRecords(free)));

        FreeStyleProject approvedJob = approvalRequired("rr-clean");
        requestAndApprove(approvedJob);
        assertApprovedRunQueuedExactlyOnce(j, approvedJob);
        assertTrue(rerunRecords(approvedJob).isEmpty(), "a clean approved run must leave no refusal record: "
                + describe(rerunRecords(approvedJob)));
    }

    // ---------------------------------------------------------------- helpers

    private FreeStyleProject approvalRequired(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        setBatchControl(job, new BatchControlJobProperty(true));
        BatchControlFixtures.activate(job);
        return job;
    }

    private static List<ChangeRecord> rerunRecords(FreeStyleProject job) {
        List<ChangeRecord> out = new ArrayList<>();
        out.addAll(ActivationFixtures.recordsFor(ChangeType.MARKER_REUSE_BLOCKED, job.getFullName()));
        out.addAll(ActivationFixtures.recordsFor(ChangeType.TRIGGER_BLOCKED, job.getFullName()));
        return out;
    }

    /**
     * At least one refusal record for {@code job}; a MARKER_REUSE_BLOCKED one names the consumed
     * request and, when {@code actor} is given, that actor. Returns the first record.
     */
    private static ChangeRecord assertRecorded(FreeStyleProject job, RunRequest request, String actor) {
        List<ChangeRecord> records = rerunRecords(job);
        assertTrue(!records.isEmpty(), job.getFullName() + ": the refused re-run must be recorded in the history"
                + " (MARKER_REUSE_BLOCKED or TRIGGER_BLOCKED), not only logged");
        for (ChangeRecord record : records) {
            if (record.getType() == ChangeType.MARKER_REUSE_BLOCKED) {
                String text = record.getTarget() + " " + record.getDetail();
                assertTrue(text.contains(request.getId()), "a MARKER_REUSE_BLOCKED record must name the consumed request "
                        + request.getId() + ": " + describe(records));
                if (actor != null) {
                    assertEquals(actor, record.getUser(), "a MARKER_REUSE_BLOCKED record names the account that attempted"
                            + " the re-use");
                }
            } else {
                assertNotNull(record.getDetail(), "a TRIGGER_BLOCKED record must name the cause kind");
            }
        }
        return records.get(0);
    }

    private static String describe(List<ChangeRecord> records) {
        return records.stream()
                .map(r -> r.getType() + " user=" + r.getUser() + " target=" + r.getTarget() + " detail=" + r.getDetail())
                .collect(Collectors.joining("; ", "[", "]"));
    }
}
