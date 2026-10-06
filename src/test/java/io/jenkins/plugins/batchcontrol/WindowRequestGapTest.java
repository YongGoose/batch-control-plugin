package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.AuthorizationMatrixProperty;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 2, scenario L2-08 (matrix rows T-GAP-224 .. T-GAP-228 and T-GAP-230, note 277): the
 * checks of a permission window request. The edited-file bullet (L2-08f) needs a restart and is
 * {@link WindowStoredFileGapTest}.
 *
 * <p>Basis: SPEC item 8 "행위(CREATE/CONFIGURE/DELETE 중 다중 선택) ... 사유"; SPEC 5 reason rules
 * (a blank reason is refused; a reason over 4,000 characters is refused; a blank reject comment is
 * refused); the D-40 line ("a CREATE request may carry an optional name restriction") with D-74 (4)
 * and issue #107 (the restriction belongs to Create, TEST-MATRIX T-08-192); SPEC 7 pending timeout
 * ({@code pendingTimeoutHours}; expiry is decided by comparing times when checked, D-08, SPEC 7 D-20
 * check-at-submit); DECISIONS P-10 (approval re-validates the scope as the caller) and P-09 (a
 * request the caller may not see answers as if it did not exist); LIMITATIONS 29 (with change control
 * off the Grants screen is closed, requesting or approving a window is refused with a message that says
 * why, plus a {@code GRANT_REQUEST_BLOCKED} record, and the URL itself still answers).
 *
 * <p>Users from {@link StrategyFixtures}: bob and carol request, a1 approves; d1 holds Item/Discover
 * on {@code secret} only. Time moves through {@link BatchClock}.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md, docs/LIMITATIONS.md and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class WindowRequestGapTest {

    private JenkinsRule j;
    private BatchControlMatrixAuthorizationStrategy strategy;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        strategy = StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy());
        strategy.add(Jenkins.READ, PermissionEntry.user("d1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("d1"));
        j.jenkins.setAuthorizationStrategy(strategy);
        StrategyFixtures.changeControlOn();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        j.createFreeStyleProject("batch-x");
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-GAP-224 (L2-08, SPEC 8 and SPEC 5 reason rules): bob's request on {@code batch-x} with no
     * action, with a blank reason, or with a reason of 4,001 characters is refused and stores nothing.
     * A CONFIGURE-only request carrying a name restriction is refused or stored without the restriction
     * (D-74 (4), #107: the restriction belongs to Create; see the ambiguity in note 277). Guard: a
     * reason of 4,000 characters is accepted.
     */
    @Test
    public void t_gap_224_requestWithoutActionOrValidReasonIsRefused() throws Exception {
        GrantScope scope = new GrantScope(GrantScope.Type.ITEM, "batch-x");
        assertRefused("no action", () -> GrantRequestService.get().create(scope, Collections.emptyList(), 30,
                "maintenance", "a1"));
        assertRefused("a blank reason", () -> GrantRequestService.get().create(scope, Arrays.asList(GrantAction.CONFIGURE), 30,
                "   ", "a1"));
        assertRefused("a reason of 4,001 characters", () -> GrantRequestService.get().create(scope,
                Arrays.asList(GrantAction.CONFIGURE), 30, "r".repeat(4001), "a1"));

        int before = GrantRequestService.get().list().size();
        GrantRequest withPattern = null;
        try (ACLContext ignored = ACL.as2(User.getById("bob", true).impersonate2())) {
            withPattern = GrantRequestService.get().create(scope, Arrays.asList(GrantAction.CONFIGURE), 30, "maintenance",
                    Collections.singletonList("a1"), "/ok-.*/");
        } catch (RuntimeException refused) {
            assertEquals(before, GrantRequestService.get().list().size(), "a refused restriction without Create stores nothing");
        }
        if (withPattern != null) {
            String stored = GrantRequestService.get().load(withPattern.getId()).getCreateNamePattern();
            assertTrue(stored == null || stored.isEmpty(), "a restriction without Create must not be stored, was '" + stored + "'");
        }

        GrantRequest longest = as("bob", () -> GrantRequestService.get().create(scope, Arrays.asList(GrantAction.CONFIGURE), 30,
                "r".repeat(4000), "a1"));
        assertNotNull(GrantRequestService.get().load(longest.getId()), "guard: a reason of 4,000 characters is accepted");
    }

    /**
     * T-GAP-225 (L2-08, P-09, P-10): d1 holds Item/Discover on job {@code secret} but not Item/Read.
     * d1's request on {@code secret} is refused with the same message as d1's request on a name with no
     * item (the item's existence is not disclosed). Guard: bob, who holds Item/Read, may request a window
     * on {@code secret}.
     */
    @Test
    public void t_gap_225_discoverOnlyRequesterGetsTheNoItemMessage() throws Exception {
        FreeStyleProject secret = j.createFreeStyleProject("secret");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.DISCOVER, PermissionEntry.user("d1"));
        secret.addProperty(amp);
        assertTrue(StrategyFixtures.has(secret, "d1", Item.DISCOVER), "premise: d1 discovers secret");
        assertFalse(StrategyFixtures.has(secret, "d1", Item.READ), "premise: d1 cannot read secret");

        String onSecret = refusalMessage("d1", "secret");
        String onNothing = refusalMessage("d1", "zzzzzz");
        assertEquals(onNothing.replace("zzzzzz", "<name>"), onSecret.replace("secret", "<name>"),
                "a Discover-only requester must get the same message as for a name with no item");

        GrantRequest guard = as("bob", () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "secret"),
                Arrays.asList(GrantAction.CONFIGURE), 30, "maintenance", "a1"));
        assertNotNull(guard, "guard: a requester with Item/Read may request a window on secret");
    }

    /**
     * T-GAP-226 (L2-08, SPEC 7 pending timeout, D-08): {@code pendingTimeoutHours} is 1; bob's request
     * is PENDING; the clock moves past the hour and a1 approves without the periodic work having run:
     * refused, no window, and the request is EXPIRED. Guard: a request approved before the hour passes
     * opens a window.
     */
    @Test
    public void t_gap_226_approvalAfterThePendingTimeoutIsRefusedAndExpires() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setPendingTimeoutHours(1);
        cfg.save();
        j.createFreeStyleProject("batch-y");
        GrantRequest late = request("bob", "batch-x");
        GrantRequest early = request("carol", "batch-y");

        as("a1", () -> GrantRequestService.get().approve(early.getId(), "in time"));
        assertTrue(GrantService.get().hasActiveGrant("carol", "batch-y", Item.CONFIGURE), "guard: the timely approval opens a window");

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(61)), ZoneOffset.UTC));
        assertRefused("approving after the pending timeout", "a1", () -> GrantRequestService.get().approve(late.getId(), "too late"));
        assertEquals(RequestStatus.EXPIRED, GrantRequestService.get().load(late.getId()).getStatus(),
                "the request past its pending timeout must be EXPIRED");
        assertFalse(GrantService.get().hasActiveGrant("bob", "batch-x", Item.CONFIGURE), "no window may exist for it");
    }

    /**
     * T-GAP-227 (L2-08, SPEC 5 "반려 사유가 비어 있으면 반려가 거부된다" reused for windows): a1 rejects
     * bob's request with a blank comment: refused, still PENDING. Guard: with a comment it is REJECTED.
     */
    @Test
    public void t_gap_227_rejectWithABlankCommentIsRefused() throws Exception {
        GrantRequest request = request("bob", "batch-x");
        assertRefused("rejecting with a blank comment", "a1", () -> GrantRequestService.get().reject(request.getId(), "  "));
        assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(request.getId()).getStatus(), "the request stays PENDING");
        as("a1", () -> GrantRequestService.get().reject(request.getId(), "not now"));
        assertEquals(RequestStatus.REJECTED, GrantRequestService.get().load(request.getId()).getStatus(), "guard: with a comment it is rejected");
    }

    /** T-GAP-228 (L2-08): approving an unknown id is refused and opens no window. */
    @Test
    public void t_gap_228_approvingAnUnknownIdIsRefused() throws Exception {
        assertRefused("approving an unknown id", "a1", () -> GrantRequestService.get().approve("no-such-request-id", "ok"));
        assertTrue(GrantService.get().listActive().isEmpty(), "no window may be opened");
    }

    /**
     * T-GAP-230 (L2-08g, LIMITATIONS 29, P-15): run control on, change control off. The Batch Control
     * landing page offers bob no link to the Grants screen, while {@code batch-control/grants/} still
     * answers (no 404) and says that change control is off; bob's {@code grants/create} POST is refused
     * with a message naming change control and writes a {@code GRANT_REQUEST_BLOCKED} record; a1's
     * approval of a request filed while change control was on is refused the same way. Guard: with
     * change control on the same POST creates a request.
     */
    @Test
    public void t_gap_230_changeControlOffRefusesRequestsWithAnExplanation() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.save();
        String pending = ApproverFormFixtures.submitGrantOk(j, "bob", "batch-x", Arrays.asList("CONFIGURE"), 30,
                "guard: change control on", null, "a1");
        cfg.setChangeControlEnabled(false);
        cfg.save();
        int blockedBefore = blocked().size();

        WebResponse root = ApproverFormFixtures.get(j, "bob", "batch-control/");
        assertEquals(200, root.getStatusCode(), "fixture: bob reaches the Batch Control landing page");
        assertFalse(root.getContentAsString().contains("batch-control/grants/\"") || root.getContentAsString().contains("href=\"grants/\""),
                "with change control off the landing page must not link the Grants screen");
        WebResponse screen = ApproverFormFixtures.get(j, "bob", "batch-control/grants/");
        assertTrue(screen.getStatusCode() != 404 && screen.getStatusCode() < 500,
                "the Grants URL must still answer (an old bookmark reaches the explanation), got " + screen.getStatusCode());
        assertTrue(RenameRefusalFixtures.visible(screen.getContentAsString()).toLowerCase(Locale.ROOT).contains("change control"),
                "the Grants URL must say that change control is off: " + ApproverFormFixtures.excerpt(screen.getContentAsString()));

        int requests = GrantRequestService.get().list().size();
        WebResponse create = ApproverFormFixtures.submitGrant(j, "bob", "batch-x", Arrays.asList("CONFIGURE"), 30,
                "while change control is off", null, "a1");
        assertTrue(create.getStatusCode() >= 400 && create.getStatusCode() < 500, "requesting a window must be refused, got "
                + create.getStatusCode());
        assertTrue(RenameRefusalFixtures.visible(create.getContentAsString()).toLowerCase(Locale.ROOT).contains("change control"),
                "the refusal must say why: " + ApproverFormFixtures.excerpt(create.getContentAsString()));
        assertEquals(requests, GrantRequestService.get().list().size(), "no request may be stored");
        assertEquals(blockedBefore + 1, blocked().size(), "the refused request must write one GRANT_REQUEST_BLOCKED record");

        WebResponse approve = ApproverFormFixtures.decideGrant(j, "a1", pending, "approve", "ok");
        assertTrue(approve.getStatusCode() >= 400 && approve.getStatusCode() < 500, "approving must be refused, got "
                + approve.getStatusCode());
        assertTrue(RenameRefusalFixtures.visible(approve.getContentAsString()).toLowerCase(Locale.ROOT).contains("change control"),
                "the refusal must say why: " + ApproverFormFixtures.excerpt(approve.getContentAsString()));
        assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(pending).getStatus(), "the request stays PENDING");
        assertEquals(blockedBefore + 2, blocked().size(), "the refused approval must write one GRANT_REQUEST_BLOCKED record");
        assertNull(GrantService.get().listActive().stream().filter(g -> "bob".equals(g.getUser())).findFirst().orElse(null),
                "no window may be opened");
    }

    // ---------------------------------------------------------------- helpers

    private GrantRequest request(String user, String item) throws Exception {
        return as(user, () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, item),
                Arrays.asList(GrantAction.CONFIGURE), 30, "maintenance of " + item, "a1"));
    }

    private String refusalMessage(String user, String item) {
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, item), Arrays.asList(GrantAction.CONFIGURE),
                    30, "maintenance", "a1");
        } catch (RuntimeException refused) {
            assertNotNull(refused.getMessage(), "the refusal on " + item + " must carry a message");
            return refused.getMessage();
        }
        throw new AssertionError(user + "'s request on " + item + " must be refused");
    }

    private static List<ChangeRecord> blocked() {
        return StrategyFixtures.records(ChangeType.GRANT_REQUEST_BLOCKED);
    }

    private static <V> V as(String user, Callable<V> call) throws Exception {
        return StrategyFixtures.as(user, call);
    }

    private static void assertRefused(String what, Callable<?> call) {
        assertRefused(what, "bob", call);
    }

    private static void assertRefused(String what, String user, Callable<?> call) {
        int before = GrantRequestService.get().list().size();
        int windows = GrantService.get().listActive().size();
        boolean refused = false;
        try {
            StrategyFixtures.as(user, call);
        } catch (RuntimeException expected) {
            refused = true;
        } catch (Exception other) {
            throw new AssertionError(what + ": unexpected exception " + other, other);
        }
        assertTrue(refused, what + " must be refused");
        assertEquals(before, GrantRequestService.get().list().size(), what + " must store no new request");
        assertEquals(windows, GrantService.get().listActive().size(), what + " must open no window");
    }
}
