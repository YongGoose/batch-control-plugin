package io.jenkins.plugins.batchcontrol;

import hudson.model.Job;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HTTP helpers for the D-37 (several approvers) and D-40 (CREATE name restriction) rows.
 *
 * <p>Every helper drives the frozen form contract rather than a service method, so the rows
 * measure what a browser or a script actually submits:
 * <ul>
 *   <li>{@code POST job/<name>/batch-control/submit} with {@code reason} and the repeated
 *       field {@code approvers} (one user id per value);</li>
 *   <li>{@code POST batch-control/requests/<id>/approve|reject} with {@code comment};</li>
 *   <li>{@code POST batch-control/requests/<id>/changeApprover} with repeated {@code approvers};</li>
 *   <li>{@code POST batch-control/grants/create} with {@code scopeType}, {@code scopeFullName},
 *       repeated {@code actions}, {@code durationMinutes}, {@code reason}, repeated
 *       {@code approvers} and the optional {@code createNamePattern};</li>
 *   <li>{@code POST batch-control/grants/<id>/approve|reject} with {@code comment};</li>
 *   <li>{@code POST batch-control/grants/<id>/changeApprover} with repeated {@code approvers}
 *       (requester-only, PENDING-only; symmetric with the run-request endpoint above).</li>
 * </ul>
 *
 * <p>Redirects are not followed, so a success answers 2xx/3xx and a refusal 4xx.
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
final class ApproverFormFixtures {

    private ApproverFormFixtures() {
        // utility class
    }

    static JenkinsRule.WebClient client(JenkinsRule j, String userId) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        if (userId != null) {
            wc.login(userId); // log in with redirects on: the login form itself redirects
        }
        wc.getOptions().setRedirectEnabled(false);
        return wc;
    }

    static WebResponse post(JenkinsRule j, String userId, String path, List<NameValuePair> params)
            throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(path), HttpMethod.POST);
        request.setRequestParameters(new ArrayList<>(params));
        return wc.getPage(request).getWebResponse();
    }

    static WebResponse get(JenkinsRule j, String userId, String path) throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        return wc.getPage(new WebRequest(new java.net.URL(j.getURL(), path), HttpMethod.GET))
                .getWebResponse();
    }

    static List<NameValuePair> approverPairs(String... approvers) {
        List<NameValuePair> out = new ArrayList<>();
        for (String approver : approvers) {
            out.add(new NameValuePair("approvers", approver));
        }
        return out;
    }

    // ------------------------------------------------------------------ run requests

    static WebResponse submitRun(JenkinsRule j, String userId, Job<?, ?> job, String reason,
                                 String... approvers) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("reason", reason));
        params.addAll(approverPairs(approvers));
        return post(j, userId, job.getUrl() + "batch-control/submit", params);
    }

    static Set<String> runRequestIds() {
        return RunRequestService.get().list().stream().map(RunRequest::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Submits and returns the id of the single request the submission created (asserted). */
    static String submitRunOk(JenkinsRule j, String userId, Job<?, ?> job, String reason,
                              String... approvers) throws Exception {
        Set<String> before = runRequestIds();
        WebResponse response = submitRun(j, userId, job, reason, approvers);
        assertSuccess(response, "fixture: the run request submission by " + userId);
        Set<String> after = runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "fixture: exactly one run request must have been created, got " + after);
        return after.iterator().next();
    }

    static WebResponse decideRun(JenkinsRule j, String userId, String requestId, String verb,
                                 String comment) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("comment", comment));
        return post(j, userId, "batch-control/requests/" + requestId + "/" + verb, params);
    }

    static WebResponse changeRunApprovers(JenkinsRule j, String userId, String requestId,
                                          String... approvers) throws Exception {
        return post(j, userId, "batch-control/requests/" + requestId + "/changeApprover",
                approverPairs(approvers));
    }

    // ------------------------------------------------------------------ grant requests

    static WebResponse submitGrant(JenkinsRule j, String userId, String scopeType, String scopeFullName,
                                   List<String> actions, int minutes, String reason,
                                   String createNamePattern, String... approvers) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("scopeType", scopeType));
        params.add(new NameValuePair("scopeFullName", scopeFullName));
        for (String action : actions) {
            params.add(new NameValuePair("actions", action));
        }
        params.add(new NameValuePair("durationMinutes", String.valueOf(minutes)));
        params.add(new NameValuePair("reason", reason));
        if (createNamePattern != null) {
            params.add(new NameValuePair("createNamePattern", createNamePattern));
        }
        params.addAll(approverPairs(approvers));
        return post(j, userId, "batch-control/grants/create", params);
    }

    static Set<String> grantRequestIds() {
        return GrantRequestService.get().list().stream().map(GrantRequest::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static String submitGrantOk(JenkinsRule j, String userId, String scopeType, String scopeFullName,
                                List<String> actions, int minutes, String reason,
                                String createNamePattern, String... approvers) throws Exception {
        Set<String> before = grantRequestIds();
        WebResponse response = submitGrant(j, userId, scopeType, scopeFullName, actions, minutes, reason,
                createNamePattern, approvers);
        assertSuccess(response, "fixture: the grant request submission by " + userId);
        Set<String> after = grantRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "fixture: exactly one grant request must have been created, got " + after);
        return after.iterator().next();
    }

    static WebResponse decideGrant(JenkinsRule j, String userId, String requestId, String verb,
                                   String comment) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("comment", comment));
        return post(j, userId, "batch-control/grants/" + requestId + "/" + verb, params);
    }

    /**
     * {@code POST batch-control/grants/<id>/changeApprover}: only the requester of a still-PENDING
     * grant request may edit its designated approver set (D-26, D-37 applied to GrantRequest).
     */
    static WebResponse changeGrantApprovers(JenkinsRule j, String userId, String requestId,
                                            String... approvers) throws Exception {
        return post(j, userId, "batch-control/grants/" + requestId + "/changeApprover",
                approverPairs(approvers));
    }

    // ------------------------------------------------------------------ assertions and records

    static void assertSuccess(WebResponse response, String what) {
        int code = response.getStatusCode();
        if (code >= 400) {
            throw new AssertionError(what + " must succeed, got HTTP " + code + ": "
                    + excerpt(response.getContentAsString()));
        }
    }

    static void assertClientError(WebResponse response, String what) {
        int code = response.getStatusCode();
        if (code < 400 || code >= 500) {
            throw new AssertionError(what + " must be refused with HTTP 4xx, got HTTP " + code + ": "
                    + excerpt(response.getContentAsString()));
        }
    }

    /** Change records of {@code type} in the current month and in the months of the given instants. */
    static List<ChangeRecord> records(ChangeType type, Instant... alsoMonthsOf) {
        Set<YearMonth> months = new LinkedHashSet<>();
        months.add(YearMonth.now());
        for (Instant instant : alsoMonthsOf) {
            months.add(YearMonth.from(instant.atZone(ZoneOffset.UTC)));
        }
        List<ChangeRecord> out = new ArrayList<>();
        for (YearMonth month : months) {
            for (ChangeRecord rec : FileStore.get().listChangeRecords(month)) {
                if (rec.getType() == type && !out.contains(rec)) {
                    out.add(rec);
                }
            }
        }
        return out;
    }

    static String excerpt(String text) {
        if (text == null) {
            return "<null>";
        }
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() > 600 ? flat.substring(0, 600) + "..." : flat;
    }
}
