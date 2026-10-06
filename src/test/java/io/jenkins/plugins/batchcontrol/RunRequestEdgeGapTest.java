package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 3, scenarios L3-23 and L3-24 (the row that needs no restart): reasons with
 * supplementary characters and lone surrogates, and an approved run whose queued run was cancelled.
 * Matrix rows T-GAP-381 .. T-GAP-383 (note 279); the restart half of L3-24 is T-GAP-384 in
 * {@link StoreRestartGapTest}.
 *
 * <p>Basis: SPEC 5 (D-72b) "Every stored value obeys the display length limit and contains only
 * characters XML can store; a failed save leaves nothing behind" and LIMITATIONS 31 ("A character that
 * XML 1.0 cannot store ... is refused ... in the reason"); SPEC 5 (D-74) "the approved run's queue item
 * is cancelled (the request stays APPROVED and is never submitted again)"; SPEC 7 approved-run timeout;
 * LIMITATIONS 32 "The request stays APPROVED until the approved-run timeout ends it as EXPIRED, with the
 * reason 'Expired: approved but not started within &lt;N&gt; minutes; its queued run was cancelled.'";
 * SPEC 6 usability (history says why). ARCHITECTURE 5: {@code requests/run/<id>.xml}.
 *
 * <p>Run control on; u1 holds Overall/Read, Item/Read and BatchControl/Request; a1 approves.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-72b and D-74, docs/LIMITATIONS.md and
 * docs/ARCHITECTURE.md only (no src/main knowledge).
 */
@WithJenkins
public class RunRequestEdgeGapTest {

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
        job = j.createFreeStyleProject("l3-edge");
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
    }

    /**
     * T-GAP-381 (L3-23; SPEC 5 D-72b: only characters XML cannot store are refused): u1's
     * {@code RunRequestService.get().create} with the reason {@code deploy 🚀} (a supplementary
     * character, which XML stores) creates the request; the stored reason is exactly that text, and the
     * request page shows it unchanged.
     */
    @Test
    public void t_gap_381_supplementaryCharacterInTheReasonIsKept() throws Exception {
        String reason = "deploy 🚀";
        RunRequest request = as("u1", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), reason, "a1"));
        assertNotNull(request, "the request is created");
        assertEquals(reason, RunRequestService.get().load(request.getId()).getReason(), "the stored reason is unchanged");
        HtmlPage page = UsabilityFixtures.htmlPage(j, "u1", "batch-control/requests/" + request.getId() + "/");
        assertEquals(200, page.getWebResponse().getStatusCode(), "u1 opens the request page");
        assertTrue(page.asNormalizedText().contains(reason), "the page shows the reason unchanged: "
                + excerpt(WindowStateFixtures.mainPanel(page).asNormalizedText()));
    }

    /**
     * T-GAP-382 (L3-23; SPEC 5 D-72b, LIMITATIONS 31): u1's {@code create} with the reason
     * {@code \uD800x} (a lone high surrogate) and with {@code x\uDC00} (a lone low surrogate) is refused
     * each time, and nothing is added under {@code requests/run/}. Guard: the reason {@code x} is
     * accepted and adds the request's file.
     */
    @Test
    public void t_gap_382_loneSurrogatesInTheReasonAreRefusedWithoutAFile() throws Exception {
        Path dir = j.jenkins.getRootDir().toPath().resolve("batch-control/requests/run");
        for (String reason : new String[] {"\uD800x", "x\uDC00"}) {
            Set<String> before = files(dir);
            Throwable refused = null;
            try {
                as("u1", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), reason, "a1"));
            } catch (Exception | Error e) {
                refused = e;
            }
            assertNotNull(refused, "D-72b: a reason holding a lone surrogate must be refused (" + escape(reason) + ")");
            assertTrue(!(refused instanceof AssertionError), "fixture: " + refused);
            assertEquals(before, files(dir), "D-72b: a refused request leaves nothing under requests/run/ (" + escape(reason) + ")");
        }
        Set<String> before = files(dir);
        RunRequest ok = as("u1", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "x", "a1"));
        Set<String> after = files(dir);
        after.removeAll(before);
        assertTrue(after.contains(ok.getId() + ".xml"), "guard: an accepted request is stored as requests/run/<id>.xml, added: " + after);
    }

    /**
     * T-GAP-383 (L3-24; SPEC 5 D-74, SPEC 7, LIMITATIONS 32): the approval-required job {@code l3-edge}
     * is restricted to a label no agent has; u1's request on it is approved by a1 at T0 (plugin clock)
     * and its run waits in the queue. That queue item is cancelled: the request stays APPROVED, nothing
     * runs. At T0 + approvedRunTimeoutMinutes + 1 the periodic work expires it, and its decision comment
     * says it was approved but not started in time and that its queued run was cancelled. Guard: u1's
     * request on {@code l3-edge2}, approved at T0 while a queue handler of the test refused its
     * submission (approved, never queued, nothing cancelled; QueueRefusalFixtures), expires in the same
     * run with a comment that says it was not started and does not mention a cancellation.
     */
    @Test
    public void t_gap_383_cancelledQueuedRunExpiresWithAReasonNamingTheCancellation() throws Exception {
        FreeStyleProject other = j.createFreeStyleProject("l3-edge2");
        setBatchControl(other, new BatchControlJobProperty(true));
        job.setAssignedLabel(j.jenkins.getLabel("l3-nowhere"));
        Instant t0 = Instant.parse("2026-10-06T08:00:30Z");
        BatchClock.setForTest(Clock.fixed(t0, ZoneOffset.UTC));
        RunRequest cancelled = as("u1", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "label-bound run", "a1"));
        approve(cancelled.getId());
        assertNotNull(j.jenkins.getQueue().getItem(job), "premise: the approved run of l3-edge waits in the queue");
        RunRequest notQueued = as("u1", () -> RunRequestService.get().create(other, new LinkedHashMap<>(), "refused run", "a1"));
        QueueRefusalFixtures.refusedBeforeTheGate(other, () -> approve(notQueued.getId()));
        assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(notQueued.getId()).getStatus(), "premise: the guard is APPROVED");
        assertNull(j.jenkins.getQueue().getItem(other), "premise: the guard's run was never queued");

        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            assertNotNull(j.jenkins.getQueue().getItem(job), "premise: the approved run of l3-edge still waits in the queue");
            assertTrue(j.jenkins.getQueue().cancel(job), "fixture: the queued run is cancelled");
        }
        assertNull(j.jenkins.getQueue().getItem(job), "premise: the queue holds nothing for l3-edge");
        assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(cancelled.getId()).getStatus(),
                "SPEC 5 D-74: the request stays APPROVED after its queued run was cancelled");

        int minutes = BatchControlGlobalConfiguration.get().getApprovedRunTimeoutMinutes();
        BatchClock.setForTest(Clock.fixed(t0.plus(Duration.ofMinutes(minutes + 1)), ZoneOffset.UTC));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();

        RunRequest expired = RunRequestService.get().load(cancelled.getId());
        assertEquals(RequestStatus.EXPIRED, expired.getStatus(), "LIMITATIONS 32: the approved-run timeout ends the request as EXPIRED");
        String comment = String.valueOf(expired.getDecisionComment()).toLowerCase(Locale.ROOT);
        assertTrue(comment.contains("not started") && comment.contains("cancel"),
                "LIMITATIONS 32, SPEC 6: the comment says it was approved but not started in time and that its queued run was cancelled: "
                        + expired.getDecisionComment());
        assertTrue(job.getBuilds().isEmpty(), "SPEC 5 D-74: the cancelled run is never submitted again");

        RunRequest guard = RunRequestService.get().load(notQueued.getId());
        assertEquals(RequestStatus.EXPIRED, guard.getStatus(), "guard (SPEC 7): an approved request not queued in time expires");
        String guardComment = String.valueOf(guard.getDecisionComment()).toLowerCase(Locale.ROOT);
        assertTrue(guardComment.contains("not started") && !guardComment.contains("cancel"),
                "guard: its comment says it was not started and does not mention a cancellation: " + guard.getDecisionComment());
    }

    /** Refuses the guard job's submission before Batch Control's queue gate (QueueRefusalFixtures). */
    @TestExtension
    public static final class RefuseBeforeGate extends QueueRefusalFixtures.RefusingHandler {
    }

    private static void approve(String id) throws Exception {
        as("a1", () -> {
            RunRequestService.get().approve(id, "ok");
            return null;
        });
    }

    // ------------------------------------------------------------------ helpers

    private static Set<String> files(Path dir) throws Exception {
        Set<String> out = new TreeSet<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> list = Files.list(dir)) {
                list.forEach(p -> out.add(p.getFileName().toString()));
            }
        }
        return out;
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            out.append(c < 0x7f ? String.valueOf(c) : String.format(Locale.ROOT, "\\u%04X", (int) c));
        }
        return out.toString();
    }

    private interface Body<T> {
        T run() throws Exception;
    }

    private static <T> T as(String userId, Body<T> body) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(userId, true).impersonate2())) {
            return body.run();
        }
    }
}
