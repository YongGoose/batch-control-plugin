package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An approval racing the change-control switch-off leaves no window behind (issue #39). After a switch-off
 * has returned, no permission window is active and none becomes active: an approval that overlaps the
 * switch-off is either refused with the "change control is off" refusal, or registers a window that the
 * switch-off revokes; either way, turning change control back on confers nothing from it. Matrix row
 * T-01-20 (note 307).
 *
 * <p>Basis: SPEC 1 (a switch change and its side effects, such as revoking the active grants when change
 * control is turned off; a direct setter call applies the value, D-42), SPEC 8 (a window confers only while
 * it is active; D-63), LIMITATIONS 34 ("the off period accumulates nothing"), and the Wave A contract for #39
 * frozen by the main session on 2026-10-10.
 *
 * <p>The race is timing-dependent, so the row is a black-box stress test: {@value #ROUNDS} rounds, each one a
 * fresh CONFIGURE request of u1 on {@code batch-x}, then a1's service approval and the administrator's direct
 * {@code setChangeControlEnabled(false)} released together from a barrier, the switch-off delayed by a
 * per-round step (0 to 3.1 ms in 0.1 ms steps, two passes) so the rounds sweep the overlap. After both have returned the invariant is
 * checked, change control is turned back on with the setter (which revokes nothing, note 214) and the
 * window must confer nothing. Violations are counted over all rounds and reported together. No timer is
 * waited on; the delay is a scheduling offset only.
 *
 * <p>Fixture: Batch Control matrix strategy (so a window confers); u1 Overall/Read, Item/Read,
 * RequestGrant; a1 Overall/Read, Item/Read, Approve (the only approver); admin Administer.
 *
 * <p>Written from docs/SPEC.md items 1 and 8, docs/TEST-MATRIX.md, the issue text of #39 and the Wave A
 * contract only (no src/main knowledge).
 */
@WithJenkins
public class GrantSwitchOffRaceTest {

    private static final int ROUNDS = 64;
    private static final long STEP_NANOS = 100_000L;
    private static final int STEPS = 32;

    private JenkinsRule j;
    private FreeStyleProject job;
    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        job = j.createFreeStyleProject("batch-x");
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.setChangeControlEnabled(true);
        cfg.save();
    }

    /**
     * T-01-20 (#39, SPEC 1 side effects, LIMITATIONS 34). Guard first, sequentially: an approval without a
     * concurrent switch-off opens a window that confers Item/Configure on {@code batch-x} to u1 (so the
     * checks below can see a window); the switch-off then ends it, and turning change control back on
     * confers nothing. Then {@value #ROUNDS} racing rounds: after the approval and the switch-off have both
     * returned, change control is off, no active window of u1 is listed and {@code hasActiveGrant} is false;
     * a refused approval says "change control"; after change control is back on, {@code hasActiveGrant} is
     * false and u1's ACL on {@code batch-x} denies Item/Configure. The switch-off never throws.
     */
    @Test
    public void t_01_20_anApprovalRacingTheSwitchOffLeavesNoWindow() throws Exception {
        String guard = request("guard: sequential approval");
        try (ACLContext ignored = as("a1")) {
            GrantRequestService.get().approve(guard, "ok");
        }
        assertTrue(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE),
                "guard: an approval without a concurrent switch-off opens the window");
        assertTrue(confersConfigure(), "guard: the open window confers Item/Configure on batch-x to u1");
        switchOff();
        assertTrue(activeWindowsOfU1().isEmpty(), "guard: the switch-off revokes the window");
        switchOn();
        assertFalse(confersConfigure(), "guard: after change control is back on the revoked window confers nothing");

        List<String> violations = new ArrayList<>();
        List<String> foreignRefusals = new ArrayList<>();
        int refused = 0;
        for (int round = 0; round < ROUNDS; round++) {
            String id = request("race round " + round);
            long delay = (round % STEPS) * STEP_NANOS;
            CyclicBarrier start = new CyclicBarrier(2);
            AtomicReference<Throwable> approveFailure = new AtomicReference<>();
            AtomicReference<Throwable> offFailure = new AtomicReference<>();
            Thread approver = new Thread(() -> {
                try (ACLContext ignored = as("a1")) {
                    start.await(30, TimeUnit.SECONDS);
                    GrantRequestService.get().approve(id, "ok");
                } catch (Throwable t) {
                    approveFailure.set(t);
                }
            }, "t-01-20 approve " + round);
            Thread switcher = new Thread(() -> {
                try (ACLContext ignored = as("admin")) {
                    start.await(30, TimeUnit.SECONDS);
                    if (delay > 0) {
                        LockSupport.parkNanos(delay);
                    }
                    cfg.setChangeControlEnabled(false);
                } catch (Throwable t) {
                    offFailure.set(t);
                }
            }, "t-01-20 switch-off " + round);
            approver.start();
            switcher.start();
            approver.join(TimeUnit.SECONDS.toMillis(60));
            switcher.join(TimeUnit.SECONDS.toMillis(60));
            assertFalse(approver.isAlive() || switcher.isAlive(), "round " + round + ": both calls return");
            assertNull(offFailure.get(), "round " + round + ": the switch-off never throws: " + offFailure.get());
            assertFalse(cfg.isChangeControlEnabled(), "round " + round + ": premise: change control is off");

            Throwable refusal = approveFailure.get();
            if (refusal != null) {
                refused++;
                if (!String.valueOf(refusal.getMessage()).toLowerCase(Locale.ROOT).contains("change control")) {
                    foreignRefusals.add("round " + round + ": " + refusal);
                }
            }
            List<Grant> afterOff = activeWindowsOfU1();
            boolean activeAfterOff = !afterOff.isEmpty() || GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE);
            switchOn();
            boolean activeAfterOn = GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE);
            boolean confers = confersConfigure();
            if (activeAfterOff || activeAfterOn || confers) {
                violations.add("round " + round + " (switch-off delayed " + delay / 1000 + " us, approval "
                        + (refusal == null ? "accepted" : "refused") + "): active after the switch-off " + activeAfterOff
                        + " " + afterOff.stream().map(Grant::getId).collect(Collectors.toList())
                        + ", active after switching back on " + activeAfterOn + ", confers Item/Configure " + confers);
                for (Grant g : activeWindowsOfU1()) {
                    GrantService.get().revoke(g.getId()); // keep the rounds independent
                }
            }
        }
        System.out.println("T-01-20 observation: " + ROUNDS + " rounds, " + refused + " approvals refused, "
                + violations.size() + " violations, refusals not naming change control: " + foreignRefusals);
        assertEquals(0, violations.size(), "#39, LIMITATIONS 34: after the switch-off has returned no window may be active, "
                + "and none may confer once change control is back on; " + violations.size() + " of " + ROUNDS
                + " rounds left one: " + violations);
        assertTrue(foreignRefusals.isEmpty(), "#39: an overlapping approval that is refused is refused because change control is off: "
                + foreignRefusals);
    }

    // ------------------------------------------------------------------ helpers

    private String request(String reason) throws Exception {
        assertTrue(cfg.isChangeControlEnabled(), "premise: change control is on when the request is made");
        try (ACLContext ignored = as("u1")) {
            return GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "batch-x"),
                    Arrays.asList(GrantAction.CONFIGURE), 60, reason, "a1").getId();
        }
    }

    private void switchOff() throws Exception {
        try (ACLContext ignored = as("admin")) {
            cfg.setChangeControlEnabled(false);
        }
        assertFalse(cfg.isChangeControlEnabled(), "premise: change control is off");
    }

    private void switchOn() throws Exception {
        try (ACLContext ignored = as("admin")) {
            cfg.setChangeControlEnabled(true);
        }
        assertTrue(cfg.isChangeControlEnabled(), "premise: change control is back on");
    }

    private List<Grant> activeWindowsOfU1() {
        return GrantService.get().listActive().stream().filter(g -> "u1".equals(g.getUser())).collect(Collectors.toList());
    }

    private boolean confersConfigure() {
        return job.getACL().hasPermission2(token("u1"), Item.CONFIGURE);
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
