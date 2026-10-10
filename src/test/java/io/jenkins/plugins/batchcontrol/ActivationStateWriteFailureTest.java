package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.decideActivation;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.recordsFor;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivationOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.get;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.activate;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Issue #36 (TEST-MATRIX note 340, rows T-06a-108 .. T-06a-110): approving an ACTIVATE or HOLD request leaves
 * memory, the activation-state file and the request status consistent, also across a restart. When the
 * activation state cannot be written ({@code batch-control/activations/} refuses writes), the approval is
 * refused before any state changes: a 4xx/5xx answer that says the decision could not be saved (not the
 * "Oops!" page), the request stays PENDING, memory and the file keep the old state, the job's other pending
 * requests are not invalidated, no {@code ACTIVATED}/{@code HELD} record is written, and once the store is
 * writable again the same approval works normally.
 *
 * <p>Basis: SPEC 6a ("Approval marks the job activated and writes an {@code ACTIVATED} change record"; "An
 * approved hold marks the job not activated and writes a {@code HELD} change record"; "activation state is
 * truthful and fails closed ... an approved HOLD ... is never undone by a stale cached value"; "approving one
 * request invalidates the job's other pending activation requests"), SPEC 4 (restart durability), the SPEC 6
 * Usability line (no "Oops!" page from our own code), ARCHITECTURE 5 (activation store
 * {@code activations/<encoded job full name>.xml}, requests in {@code activation-requests/}) and the wave-B
 * contract for #36 (main session, 2026-10-10). The fault is the one of the existing storage-fault rows (a
 * directory and its files made read-only, proven by a refused create), skipped where the platform cannot
 * refuse its owner a write (Windows, root; note 291).
 *
 * <p>Written from docs/SPEC.md items 4 and 6a, docs/ARCHITECTURE.md section 5, issue #36 and the wave-B
 * contract only (no src/main knowledge).
 */
public class ActivationStateWriteFailureTest {

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    /**
     * T-06a-108 (P0, #36): {@code hold-w} is activated (its timer runs) and has two PENDING HOLD requests (a1,
     * a2). With {@code activations/} unwritable a1 approves the first: refused (4xx/5xx, "could not be saved",
     * no "Oops!"); both requests stay PENDING; the job is still activated in memory and its state file is
     * unchanged; no HELD record; its timer still runs; u1 can file another HOLD. Guard: with the store writable
     * again the same approval succeeds: APPROVED, not activated, the two other HOLD requests INVALIDATED, one
     * HELD record, the state file rewritten, the timer refused (blocking triple).
     */
    @Test
    public void t_06a_108_holdApprovalIsRefusedWhenTheActivationStateCannotBeWritten() throws Throwable {
        session.then(r -> {
            secure(r);
            FreeStyleProject job = cleared(r.createFreeStyleProject("hold-w"));
            activate(job, "u1", "a1");
            r.assertBuildStatusSuccess(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
            String hold = submitActivationOk(r, "u1", job, "HOLD", "vendor outage", "a1");
            String other = submitActivationOk(r, "u1", job, "HOLD", "second hold", "a2");
            Path state = stateFile(r, "hold-w");
            byte[] before = Files.readAllBytes(state);
            assertTrue(recordsFor(ChangeType.HELD, "hold-w").isEmpty(), "premise: no HELD record yet");

            try (Fault ignored = unwritable(activationsDir(r))) {
                assertRefusedAsUnsaved(decideActivation(r, "a1", hold, "approve", "hold it"), "HOLD");
            }

            assertEquals(RequestStatus.PENDING, status(hold), "#36: the refused approval leaves the HOLD request PENDING");
            assertEquals(RequestStatus.PENDING, status(other), "#36: the job's other pending HOLD request is not invalidated by a refused approval");
            assertTrue(isActivated(job), "#36: memory keeps the old state: hold-w is still activated");
            assertArrayEquals(before, Files.readAllBytes(state), "#36: the state file keeps the old state");
            assertTrue(recordsFor(ChangeType.HELD, "hold-w").isEmpty(), "#36: a refused HOLD approval writes no HELD record");
            r.assertBuildStatusSuccess(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
            String third = submitActivationOk(r, "u1", job, "HOLD", "third hold", "a2");

            // guard: once the store is writable again the same approval works normally
            assertSuccess(decideActivation(r, "a1", hold, "approve", "hold it now"), "guard: a1's approval with a writable store");
            assertEquals(RequestStatus.APPROVED, status(hold), "guard: the HOLD request is APPROVED");
            assertFalse(isActivated(job), "guard: an approved hold marks hold-w not activated");
            assertEquals(RequestStatus.INVALIDATED, status(other), "guard (security-13): the other pending HOLD is INVALIDATED");
            assertEquals(RequestStatus.INVALIDATED, status(third), "guard (security-13): the HOLD filed after the refusal is INVALIDATED");
            assertEquals(1, recordsFor(ChangeType.HELD, "hold-w").size(), "guard: one HELD record");
            assertFalse(Arrays.equals(before, Files.readAllBytes(state)), "guard: the state file now records the hold");
            assertTimerRefused(r, job);
        });
    }

    /**
     * T-06a-109 (P0, #36, restart): {@code hold-r} is activated (its timer runs) and has a PENDING HOLD request.
     * With {@code activations/} unwritable a1 approves it; the store is made writable again and u1 reads the job
     * page. After a restart the job's state equals what the page said before it, and that state is the old one:
     * activated, the HOLD request PENDING, the timer runs. Guard: a1's approval after the restart succeeds: not
     * activated, one HELD record, the timer refused (blocking triple).
     */
    @Test
    public void t_06a_109_refusedHoldApprovalLeavesTheSameStateAfterARestart() throws Throwable {
        AtomicReference<String> hold = new AtomicReference<>();
        AtomicReference<Boolean> pageSaidActivated = new AtomicReference<>();
        AtomicReference<String> observed = new AtomicReference<>();
        session.then(r -> {
            secure(r);
            FreeStyleProject job = cleared(r.createFreeStyleProject("hold-r"));
            activate(job, "u1", "a1");
            r.assertBuildStatusSuccess(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
            hold.set(submitActivationOk(r, "u1", job, "HOLD", "vendor outage", "a1"));
            int code;
            try (Fault ignored = unwritable(activationsDir(r))) {
                code = decideActivation(r, "a1", hold.get(), "approve", "hold it").getStatusCode();
            }
            String page = get(r, "u1", job.getUrl()).getContentAsString().toLowerCase(Locale.ROOT);
            boolean notInService = page.contains("not activated") || page.contains("on hold");
            assertTrue(notInService || page.contains("activated"), "premise: the job page states the activation state");
            pageSaidActivated.set(!notInService);
            observed.set("before the restart the approval answered HTTP " + code + ", the request was " + status(hold.get())
                    + ", the page said " + (notInService ? "not activated / on hold" : "activated")
                    + ", memory said activated=" + isActivated(job));
        });
        session.then(r -> {
            secure(r);
            FreeStyleProject job = r.jenkins.getItemByFullName("hold-r", FreeStyleProject.class);
            assertEquals(pageSaidActivated.get(), isActivated(job),
                    "#36: after the restart hold-r's activation must be what its page said before the restart (" + observed.get() + ")");
            assertTrue(isActivated(job), "#36: the refused HOLD approval left hold-r activated (" + observed.get() + ")");
            assertEquals(RequestStatus.PENDING, status(hold.get()), "#36: the HOLD request is still PENDING after the restart");
            r.assertBuildStatusSuccess(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));

            assertSuccess(decideActivation(r, "a1", hold.get(), "approve", "hold it now"), "guard: a1's approval after the restart");
            assertFalse(isActivated(job), "guard: hold-r is held");
            assertEquals(1, recordsFor(ChangeType.HELD, "hold-r").size(), "guard: one HELD record");
            assertTimerRefused(r, job);
        });
    }

    /**
     * T-06a-110 (P0, #36): {@code act-w} is not activated and has two PENDING ACTIVATE requests (a1, a2). With
     * {@code activations/} unwritable a1 approves the first: refused (4xx/5xx, "could not be saved", no
     * "Oops!"); both requests stay PENDING; the job is not activated and its timer is refused (blocking triple);
     * no ACTIVATED record. Guard: with the store writable again the approval succeeds: APPROVED, activated, the
     * other request INVALIDATED, one ACTIVATED record, the timer runs.
     */
    @Test
    public void t_06a_110_activateApprovalIsRefusedWhenTheActivationStateCannotBeWritten() throws Throwable {
        session.then(r -> {
            secure(r);
            activate(cleared(r.createFreeStyleProject("act-seed")), "u1", "a1"); // activations/ holds a state file
            FreeStyleProject job = cleared(r.createFreeStyleProject("act-w"));
            assertFalse(isActivated(job), "premise: a job created under run control is not activated");
            String act = submitActivationOk(r, "u1", job, "ACTIVATE", "go live", "a1");
            String other = submitActivationOk(r, "u1", job, "ACTIVATE", "go live too", "a2");

            try (Fault ignored = unwritable(activationsDir(r))) {
                assertRefusedAsUnsaved(decideActivation(r, "a1", act, "approve", "ok"), "ACTIVATE");
            }

            assertEquals(RequestStatus.PENDING, status(act), "#36: the refused approval leaves the ACTIVATE request PENDING");
            assertEquals(RequestStatus.PENDING, status(other), "#36: the job's other pending ACTIVATE request is not invalidated by a refused approval");
            assertFalse(isActivated(job), "#36: memory keeps the old state: act-w is not activated");
            assertTrue(recordsFor(ChangeType.ACTIVATED, "act-w").isEmpty(), "#36: a refused ACTIVATE approval writes no ACTIVATED record");
            assertTimerRefused(r, job);

            assertSuccess(decideActivation(r, "a1", act, "approve", "ok now"), "guard: a1's approval with a writable store");
            assertEquals(RequestStatus.APPROVED, status(act), "guard: the ACTIVATE request is APPROVED");
            assertTrue(isActivated(job), "guard: act-w is activated");
            assertEquals(RequestStatus.INVALIDATED, status(other), "guard (security-13): the other pending ACTIVATE is INVALIDATED");
            assertEquals(1, recordsFor(ChangeType.ACTIVATED, "act-w").size(), "guard: one ACTIVATED record");
            r.assertBuildStatusSuccess(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        });
    }

    // ------------------------------------------------------------------ helpers

    private static void assertRefusedAsUnsaved(WebResponse answer, String action) {
        int code = answer.getStatusCode();
        String body = answer.getContentAsString();
        assertTrue(code >= 400 && code < 600, "#36: the approval of the " + action + " request must be refused while the activation"
                + " state cannot be written, got HTTP " + code + ": " + excerpt(body));
        assertFalse(body.contains("Oops!"), "#36 (SPEC 6 Usability): the refusal must be a plain message, not the 'Oops!' page: " + excerpt(body));
        assertTrue(body.toLowerCase(Locale.ROOT).contains("could not be saved"), "#36: the refusal must say the decision could not be saved: "
                + excerpt(body));
    }

    private static void assertTimerRefused(JenkinsRule r, FreeStyleProject job) throws Exception {
        int next = job.getNextBuildNumber();
        int builds = job.getBuilds().size();
        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "the timer of " + job.getFullName() + " is refused");
        assertBlocked(r, job, next, builds);
    }

    private static RequestStatus status(String requestId) {
        return ActivationService.get().load(requestId).getStatus();
    }

    /** A persistable security setup and run control with approvers a1 and a2 (applied in every session). */
    private static void secure(JenkinsRule r) throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.save();
    }

    /** Timer and upstream unblocked, so only the activation decides (D-46). */
    private static FreeStyleProject cleared(FreeStyleProject job) throws Exception {
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(job, property);
        return job;
    }

    private static Path activationsDir(JenkinsRule r) {
        return r.jenkins.getRootDir().toPath().resolve("batch-control").resolve("activations");
    }

    /** {@code activations/<name>.xml} of a top-level job (ARCHITECTURE 5), which must exist. */
    private static Path stateFile(JenkinsRule r, String name) {
        Path file = activationsDir(r).resolve(name + ".xml");
        assertTrue(Files.isRegularFile(file), "fixture: the activation of " + name + " is stored as " + file + " (ARCHITECTURE 5)");
        return file;
    }

    /** The restore handle of one injected fault. */
    private static final class Fault implements AutoCloseable {
        private final Map<Path, Set<PosixFilePermission>> original;

        Fault(Map<Path, Set<PosixFilePermission>> original) {
            this.original = original;
        }

        @Override
        public void close() throws IOException {
            for (Map.Entry<Path, Set<PosixFilePermission>> e : original.entrySet()) {
                Files.setPosixFilePermissions(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * Makes {@code dir} and everything below it read-only (directories {@code r-xr-xr-x}, files
     * {@code r--r--r--}) and proves that a file can no longer be created in it; skipped where the platform
     * cannot refuse its owner a write (note 291).
     */
    private static Fault unwritable(Path dir) throws IOException {
        assertTrue(Files.isDirectory(dir), "fixture: " + dir + " must exist before it is made unwritable");
        PlatformFixtures.assumeCanMakeUnwritable();
        List<Path> all;
        try (Stream<Path> walk = Files.walk(dir)) {
            all = walk.collect(Collectors.toList());
        }
        Map<Path, Set<PosixFilePermission>> original = new LinkedHashMap<>();
        for (Path p : all) {
            try {
                original.put(p, Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS));
            } catch (UnsupportedOperationException e) {
                assumeTrue(false, "POSIX permissions are needed to make " + dir + " unwritable");
            }
        }
        Fault fault = new Fault(original);
        List<Path> reversed = new ArrayList<>(all);
        Collections.reverse(reversed);
        for (Path p : reversed) {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(Files.isDirectory(p) ? "r-xr-xr-x" : "r--r--r--"));
        }
        boolean refused;
        try {
            Files.writeString(dir.resolve("probe-" + System.nanoTime() + ".xml"), "probe", StandardCharsets.UTF_8);
            refused = false;
        } catch (IOException expected) {
            refused = true;
        }
        if (!refused) {
            fault.close();
            assumeTrue(false, "the platform did not refuse writes to " + dir + " (root?), so the fault cannot be built here");
        }
        return fault;
    }
}
