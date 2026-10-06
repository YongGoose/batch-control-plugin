package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.PendingCount;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.core.Authentication;

import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #76 (matrix note 222, T-UI-73..77): one source for pending counts. Each request service answers
 * {@code countPendingFor(Authentication)} with a {@link PendingCount}: the PENDING requests awaiting
 * the user's decision (designated approver who holds BatchControl/Approve) and the user's own
 * PENDING requests; anonymous gets {@link PendingCount#NONE}; decided requests are not counted; and
 * the section tab's badge shows {@link PendingCount#getCount()} (no badge for 0).
 *
 * <p>The API names were given by the coordinator and read from the compiled public API only
 * ({@code javap -public}, core-dev 5f40f4d). How {@code getCount()} combines the two numbers for a
 * user who is both requester and approver is not pinned here: the rows use users who are only one.
 * Written from issue #76, SPEC items 2/5/6a/8, D-61 and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class PendingCountTest {

    private static final String ROOT = "batch-control/";

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(strategy(true));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2", "d1"));
        cfg.save();
        job = j.createFreeStyleProject("count-x");
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
    }

    /** {@code d1} holds Approve only while {@code d1Approves}. */
    private static MockAuthorizationStrategy strategy(boolean d1Approves) {
        MockAuthorizationStrategy s = new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.REQUEST_GRANT).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer");
        if (d1Approves) {
            s.grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("d1");
        } else {
            s.grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("d1");
        }
        return s;
    }

    /** T-UI-73: run requests: approver/requester/neither/anonymous, decided not counted, badge = getCount. */
    @Test
    public void t_ui_73_runRequestCountsAndBadge() throws Exception {
        Function<Authentication, PendingCount> count = a -> RunRequestService.get().countPendingFor(a);
        assertEquals(0, count.apply(auth("a1")).getCount(), "premise: nothing pending before any request");
        assertBadge("a1", "requests", 0);

        RunRequest first = as("u1", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "run 1", "a1"));
        RunRequest second = as("u1", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "run 2", "a1"));
        as("u1", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "run 3", "a1"));

        assertCounts(count, 3);
        assertBadge("a1", "requests", 3);
        assertBadge("u1", "requests", 3);
        assertBadge("a2", "requests", 0);

        as("a1", () -> RunRequestService.get().approve(first.getId(), "ok"));
        as("a1", () -> RunRequestService.get().reject(second.getId(), "no"));
        j.waitUntilNoActivity();
        assertEquals(1, count.apply(auth("a1")).getAwaitingDecision(), "approved and rejected requests must leave the approver's count");
        assertEquals(1, count.apply(auth("u1")).getOwn(), "approved and rejected requests must leave the requester's count");
        assertBadge("a1", "requests", 1);
        assertBadge("u1", "requests", 1);
    }

    /** T-UI-74: grant requests: same rules; badge on the grants tab = getCount. */
    @Test
    public void t_ui_74_grantRequestCountsAndBadge() throws Exception {
        Function<Authentication, PendingCount> count = a -> GrantRequestService.get().countPendingFor(a);
        GrantRequest first = as("u1", () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "count-x"),
                Arrays.asList(GrantAction.CONFIGURE), 30, "fix 1", "a1"));
        as("u1", () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "count-x"),
                Arrays.asList(GrantAction.CONFIGURE), 30, "fix 2", "a1"));

        assertCounts(count, 2);
        assertBadge("a1", "grants", 2);
        assertBadge("u1", "grants", 2);
        assertBadge("a2", "grants", 0);

        as("a1", () -> GrantRequestService.get().reject(first.getId(), "no"));
        assertEquals(1, count.apply(auth("a1")).getAwaitingDecision(), "a rejected grant request must leave the approver's count");
        assertEquals(1, count.apply(auth("u1")).getOwn(), "a rejected grant request must leave the requester's count");
        assertBadge("a1", "grants", 1);
    }

    /** T-UI-75: activation requests: same rules; badge on the activations tab = getCount. */
    @Test
    public void t_ui_75_activationRequestCountsAndBadge() throws Exception {
        Function<Authentication, PendingCount> count = a -> ActivationService.get().countPendingFor(a);
        FreeStyleProject other = j.createFreeStyleProject("count-y");
        BatchControlFixtures.setBatchControl(other, new BatchControlJobProperty(true));
        ActivationRequest first = as("u1", () -> ActivationService.get().create(job, ActivationRequest.Action.ACTIVATE,
                "go live", List.of("a1")));
        as("u1", () -> ActivationService.get().create(other, ActivationRequest.Action.ACTIVATE, "go live", List.of("a1")));

        assertCounts(count, 2);
        assertEquals(2, ActivationService.get().listAwaitingDecision(auth("a1")).size(),
                "listAwaitingDecision must list what awaits the designated approver");
        assertEquals(0, ActivationService.get().listAwaitingDecision(auth("a2")).size(),
                "listAwaitingDecision must be empty for an approver who is not designated");
        assertEquals(0, ActivationService.get().listAwaitingDecision(auth("u1")).size(),
                "listAwaitingDecision must be empty for a user without BatchControl/Approve");
        assertBadge("a1", "activations", 2);
        assertBadge("u1", "activations", 2);
        assertBadge("a2", "activations", 0);

        as("a1", () -> ActivationService.get().approve(first.getId(), "ok"));
        assertEquals(1, count.apply(auth("a1")).getAwaitingDecision(), "an approved activation must leave the approver's count");
        assertEquals(1, count.apply(auth("u1")).getOwn(), "an approved activation must leave the requester's count");
        assertBadge("a1", "activations", 1);
    }

    /**
     * T-UI-76: a designated approver who no longer holds BatchControl/Approve has nothing awaiting
     * them (only own requests count, here none). Guard: with Approve the same user counts it.
     */
    @Test
    public void t_ui_76_designatedApproverWithoutApproveHasNothingAwaiting() throws Exception {
        as("u1", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "run", "d1"));
        as("u1", () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "count-x"),
                Arrays.asList(GrantAction.CONFIGURE), 30, "fix", "d1"));
        as("u1", () -> ActivationService.get().create(job, ActivationRequest.Action.ACTIVATE, "go live", List.of("d1")));
        assertEquals(1, RunRequestService.get().countPendingFor(auth("d1")).getAwaitingDecision(), "guard: d1 with Approve awaits the run request");
        assertEquals(1, GrantRequestService.get().countPendingFor(auth("d1")).getAwaitingDecision(), "guard: d1 with Approve awaits the grant request");
        assertEquals(1, ActivationService.get().countPendingFor(auth("d1")).getAwaitingDecision(), "guard: d1 with Approve awaits the activation");

        j.jenkins.setAuthorizationStrategy(strategy(false));
        assertEquals(0, RunRequestService.get().countPendingFor(auth("d1")).getCount(), "without Approve d1 must count no run request");
        assertEquals(0, GrantRequestService.get().countPendingFor(auth("d1")).getCount(), "without Approve d1 must count no grant request");
        assertEquals(0, ActivationService.get().countPendingFor(auth("d1")).getCount(), "without Approve d1 must count no activation");
        assertEquals(0, ActivationService.get().listAwaitingDecision(auth("d1")).size(), "without Approve nothing awaits d1");
        assertEquals(1, RunRequestService.get().countPendingFor(auth("u1")).getOwn(), "guard: the requester's own count is unchanged");
    }

    /** T-UI-77: anonymous gets NONE from every service, even with requests pending. */
    @Test
    public void t_ui_77_anonymousGetsNone() throws Exception {
        as("u1", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "run", "a1"));
        as("u1", () -> ActivationService.get().create(job, ActivationRequest.Action.ACTIVATE, "go live", List.of("a1")));
        as("u1", () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "count-x"),
                Arrays.asList(GrantAction.CONFIGURE), 30, "fix", "a1"));
        Authentication anon = Jenkins.ANONYMOUS2;
        assertEquals(PendingCount.NONE, RunRequestService.get().countPendingFor(anon), "anonymous: run requests");
        assertEquals(PendingCount.NONE, GrantRequestService.get().countPendingFor(anon), "anonymous: grant requests");
        assertEquals(PendingCount.NONE, ActivationService.get().countPendingFor(anon), "anonymous: activations");
        assertTrue(PendingCount.NONE.isEmpty() && PendingCount.NONE.getCount() == 0, "NONE must be empty");
        assertEquals(1, RunRequestService.get().countPendingFor(auth("a1")).getAwaitingDecision(), "guard: a1 does count it");
    }

    // ---------------------------------------------------------------- helpers

    /** {@code n} pending requests by u1 designating a1. */
    private void assertCounts(Function<Authentication, PendingCount> count, int n) {
        PendingCount approver = count.apply(auth("a1"));
        assertEquals(n, approver.getAwaitingDecision(), "the designated approver must have " + n + " awaiting: " + approver);
        assertEquals(n, approver.getCount(), "the designated approver's count: " + approver);
        PendingCount requester = count.apply(auth("u1"));
        assertEquals(n, requester.getOwn(), "the requester must have " + n + " own pending: " + requester);
        assertEquals(0, requester.getAwaitingDecision(), "the requester (no Approve) awaits nothing: " + requester);
        assertEquals(n, requester.getCount(), "the requester's count: " + requester);
        PendingCount other = count.apply(auth("a2"));
        assertEquals(0, other.getAwaitingDecision(), "an approver who is not designated awaits nothing: " + other);
        assertEquals(0, other.getCount(), "an approver who is not designated: " + other);
        assertEquals(0, count.apply(auth("viewer")).getCount(), "a user who is neither: 0");
    }

    private void assertBadge(String user, String section, int expectedCount) throws Exception {
        PendingCount expected = switch (section) {
            case "requests" -> RunRequestService.get().countPendingFor(auth(user));
            case "grants" -> GrantRequestService.get().countPendingFor(auth(user));
            default -> ActivationService.get().countPendingFor(auth(user));
        };
        assertEquals(expectedCount, expected.getCount(), user + ": premise: countPendingFor(" + section + ")");
        String badge = badge(page(user), section);
        assertEquals(expectedCount == 0 ? null : String.valueOf(expectedCount), badge,
                user + ": the " + section + " tab badge must show countPendingFor(...).getCount()");
    }

    private static Authentication auth(String id) {
        return User.getById(id, true).impersonate2();
    }

    private static <T> T as(String id, Callable<T> body) throws Exception {
        try (ACLContext ignored = ACL.as2(auth(id))) {
            return body.call();
        }
    }

    private HtmlPage page(String user) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        Page p = wc.login(user).getPage(new URL(j.getURL(), ROOT));
        assertEquals(200, p.getWebResponse().getStatusCode(), user + " GET /" + ROOT + ": " + excerpt(p.getWebResponse().getContentAsString()));
        return (HtmlPage) p;
    }

    /** The badge text of a tab, or null when the tab has no (non-empty, non-zero) badge (as SectionTabsTest). */
    private static String badge(HtmlPage page, String section) {
        for (Object o : page.querySelectorAll("nav[data-batch-control-tabs] a[data-batch-control-tab]")) {
            DomElement tab = (DomElement) o;
            if (!section.equals(tab.getAttribute("data-batch-control-tab"))) {
                continue;
            }
            for (HtmlElement e : tab.getHtmlElementDescendants()) {
                if (e.getAttribute("class").contains("badge")) {
                    String text = e.asNormalizedText().trim();
                    return text.isEmpty() || "0".equals(text) ? null : text;
                }
            }
        }
        return null;
    }
}
