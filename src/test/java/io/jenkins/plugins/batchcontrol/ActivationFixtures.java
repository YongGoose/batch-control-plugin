package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Queue;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import java.net.URL;
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

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.approverPairs;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.post;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP and store helpers for the SPEC item 6a (activation approval, #15, D-39) rows.
 *
 * <p>The frozen web contract (matrix note 90):
 * <ul>
 *   <li>{@code GET job/<name>/batch-control-activation/} — the request form;</li>
 *   <li>{@code POST job/<name>/batch-control-activation/submit} with {@code action}
 *       ({@code ACTIVATE}|{@code HOLD}), {@code reason} and the repeated {@code approvers};</li>
 *   <li>{@code GET batch-control/activations/} — the approver's inbox;</li>
 *   <li>{@code POST batch-control/activations/<id>/approve|reject|cancel} with {@code comment}.</li>
 * </ul>
 *
 * <p>Redirects are not followed ({@link ApproverFormFixtures#client}), so a success answers
 * 2xx/3xx and a refusal 4xx. Written from docs/SPEC.md item 6a, docs/ARCHITECTURE.md
 * "Activation store" and docs/DESIGN-ACTIVATION-APPROVAL.md only (no src/main knowledge).
 */
final class ActivationFixtures {

    /** A freestyle job that runs on its own schedule the moment it is in service. */
    static final String CRON_FREESTYLE_XML =
            "<?xml version='1.1' encoding='UTF-8'?><project>"
                    + "<description>a nightly batch job</description>"
                    + "<triggers><hudson.triggers.TimerTrigger><spec>0 3 * * *</spec>"
                    + "</hudson.triggers.TimerTrigger></triggers>"
                    + "<builders/><publishers/><buildWrappers/></project>";

    private ActivationFixtures() {
        // utility class
    }

    static WebResponse submitActivation(JenkinsRule j, String userId, Item job, String action,
                                        String reason, String... approvers) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("action", action));
        params.add(new NameValuePair("reason", reason));
        params.addAll(approverPairs(approvers));
        return post(j, userId, job.getUrl() + "batch-control-activation/submit", params);
    }

    static Set<String> activationIds() {
        return ActivationService.get().list().stream().map(ActivationRequest::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Submits through the form and returns the id of the one request it created (asserted). */
    static String submitActivationOk(JenkinsRule j, String userId, Item job, String action,
                                     String reason, String... approvers) throws Exception {
        Set<String> before = activationIds();
        assertSuccess(submitActivation(j, userId, job, action, reason, approvers),
                "fixture: the " + action + " request by " + userId + " for " + job.getFullName());
        Set<String> after = activationIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "fixture: exactly one activation request must have been created, got " + after);
        return after.iterator().next();
    }

    static WebResponse decideActivation(JenkinsRule j, String userId, String requestId, String verb,
                                        String comment) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("comment", comment));
        return post(j, userId, "batch-control/activations/" + requestId + "/" + verb, params);
    }

    /** A GET on a state-changing URL, as {@code userId}; redirects are not followed. */
    static WebResponse getAs(JenkinsRule j, String userId, String path) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, userId);
        return wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET)).getWebResponse();
    }

    /** ACTIVATED/HELD records (any month the store holds for this instance's clock) for {@code target}. */
    static List<ChangeRecord> recordsFor(ChangeType type, String target) {
        return ApproverFormFixtures.records(type).stream()
                .filter(r -> target.equals(r.getTarget()))
                .collect(Collectors.toList());
    }

    static boolean isActivated(Item job) {
        return ActivationService.get().isActivated(job);
    }

    /**
     * A {@link Cause.UserIdCause} made while authenticated as {@code userId}: a human submission.
     * Fixtures use it instead of a cause-less {@code scheduleBuild2(0)} / {@code buildAndAssertSuccess}
     * when a helper job must simply run, because whether a cause-less or {@code LegacyCodeCause}
     * submission is an "unclassified" (unattended) cause under D-46 is not settled (note 100).
     *
     * <p><b>security-15 S-15-01:</b> impersonation must cover the {@code scheduleBuild2} call
     * itself, not only this factory method — the queue gate now classifies a cause as human only
     * if the <em>submitting</em> authentication also names the same user, not the SYSTEM the test
     * thread runs as by default. Callers must wrap the whole submission:
     * {@code try (ACLContext c = ACL.as2(token(id))) { job.scheduleBuild2(0, userCause(id)); }}
     * (or equivalently construct {@code new Cause.UserIdCause()} directly inside that block).
     */
    static Cause userCause(String userId) {
        try (hudson.security.ACLContext ignored = hudson.security.ACL.as2(BatchControlFixtures.token(userId))) {
            return new Cause.UserIdCause();
        }
    }

    /** Matrix common blocking baseline: empty queue, unchanged next build number, no new build. */
    static void assertBlocked(JenkinsRule j, Job<?, ?> target, int nextBuildNumberBefore, int buildsBefore)
            throws Exception {
        Queue.Item[] items = j.jenkins.getQueue().getItems();
        assertEquals(0, items.length, "the queue must stay empty");
        j.waitUntilNoActivity();
        assertEquals(nextBuildNumberBefore, target.getNextBuildNumber(), "nextBuildNumber of " + target.getFullName() + " must not move");
        assertEquals(buildsBefore, target.getBuilds().size(), "no build of " + target.getFullName() + " may have run");
        assertTrue(j.jenkins.getQueue().isEmpty(), "the queue must still be empty after settling");
    }
}
