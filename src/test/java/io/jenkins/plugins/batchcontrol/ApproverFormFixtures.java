package io.jenkins.plugins.batchcontrol;

import io.jenkins.plugins.batchcontrol.store.BatchClock;
import hudson.model.Job;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.FormEncodingType;
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
 *       field {@code approvers} (one user id per value), url-encoded or, as the Request Run form
 *       posts it (D-72), {@code multipart/form-data} with the raw parts plus core's structured
 *       {@code json} field;</li>
 *   <li>{@code POST batch-control/requests/<id>/approve|reject} with {@code comment};</li>
 *   <li>{@code POST batch-control/requests/<id>/changeApprover} with repeated {@code approvers};</li>
 *   <li>{@code POST batch-control/grants/create} with {@code scopeFullName}, repeated
 *       {@code actions}, {@code durationMinutes}, {@code reason}, repeated {@code approvers} and
 *       the optional {@code createNamePattern}. Since D-71 a window names exactly one item and
 *       the form has no {@code scopeType} field;</li>
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
        // Encode the fields as UTF-8, as a browser does for a Jenkins page (T-03-32). HtmlUnit's
        // WebRequest defaults to ISO-8859-1 and turns every character outside it into '?'.
        // ASCII values are the same bytes either way.
        request.setCharset(StandardCharsets.UTF_8);
        request.setRequestParameters(new ArrayList<>(params));
        return wc.getPage(request).getWebResponse();
    }

    /**
     * As {@link #post}, but the body is {@code multipart/form-data} (the crumb in the query). The
     * fields are UTF-8 bytes, and the text parts carry no charset, as a browser sends them.
     */
    static WebResponse postMultipart(JenkinsRule j, String userId, String path, List<NameValuePair> fields)
            throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(path), HttpMethod.POST);
        request.setEncodingType(FormEncodingType.MULTIPART);
        request.setCharset(StandardCharsets.UTF_8);
        request.setRequestParameters(new ArrayList<>(fields));
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

    /**
     * {@code submit} as {@code multipart/form-data}, shaped as the Request Run form posts it in a
     * browser (core's form script): the raw parts {@code reason}, one {@code approvers} part per
     * user id in the given order, then {@code parameterParts} (core's {@code name}/{@code value}
     * pairs and the renamed file parts such as {@code file0}), and last the structured {@code json}
     * field carrying {@code reason}, {@code "approvers": [<the same ids, same order>]} and, when
     * {@code parameterJson} is not null, {@code "parameter": parameterJson}.
     */
    static WebResponse submitRunMultipart(JenkinsRule j, String userId, Job<?, ?> job, String reason,
                                          List<NameValuePair> parameterParts, JSONArray parameterJson,
                                          String... approvers) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("reason", reason));
        params.addAll(approverPairs(approvers));
        params.addAll(parameterParts);
        JSONObject json = new JSONObject();
        json.put("reason", reason);
        json.put("approvers", JSONArray.fromObject(approvers));
        if (parameterJson != null) {
            json.put("parameter", parameterJson);
        }
        params.add(new NameValuePair("json", json.toString()));
        return postMultipart(j, userId, job.getUrl() + "batch-control/submit", params);
    }

    /** {@link #submitRunMultipart}, returning the id of the single request it created (asserted). */
    static String submitRunMultipartOk(JenkinsRule j, String userId, Job<?, ?> job, String reason,
                                       List<NameValuePair> parameterParts, JSONArray parameterJson,
                                       String... approvers) throws Exception {
        Set<String> before = runRequestIds();
        WebResponse response = submitRunMultipart(j, userId, job, reason, parameterParts, parameterJson, approvers);
        assertSuccess(response, "fixture: the multipart run request submission by " + userId);
        Set<String> after = runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "fixture: exactly one run request must have been created, got " + after);
        return after.iterator().next();
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

    static WebResponse submitGrant(JenkinsRule j, String userId, String scopeFullName,
                                   List<String> actions, int minutes, String reason,
                                   String createNamePattern, String... approvers) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
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

    static String submitGrantOk(JenkinsRule j, String userId, String scopeFullName,
                                List<String> actions, int minutes, String reason,
                                String createNamePattern, String... approvers) throws Exception {
        Set<String> before = grantRequestIds();
        WebResponse response = submitGrant(j, userId, scopeFullName, actions, minutes, reason,
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
            throw new AssertionError(what + " must succeed, got HTTP " + code + alerts(response.getContentAsString())
                    + ": " + excerpt(response.getContentAsString()));
        }
    }

    /** The texts of the page's {@code role="alert"} elements (a form refusal's reasons), for failure messages. */
    static String alerts(String html) {
        if (html == null) {
            return "";
        }
        List<String> texts = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("role=\"alert\"[^>]*>([^<]+)<").matcher(html);
        while (matcher.find()) {
            String text = matcher.group(1).trim();
            if (!text.isEmpty()) {
                texts.add(text);
            }
        }
        return texts.isEmpty() ? "" : " (alerts " + texts + ")";
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
        months.add(YearMonth.now(BatchClock.clock()));
        for (Instant instant : alsoMonthsOf) {
            months.add(YearMonth.from(instant.atZone(BatchClock.clock().getZone())));
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
