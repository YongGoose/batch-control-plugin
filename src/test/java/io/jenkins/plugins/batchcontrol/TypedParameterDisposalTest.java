package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.FilePath;
import hudson.model.FileParameterDefinition;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.file_parameters.StashedFileParameterDefinition;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.CORE_TMP_DIR;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.STASH_TMP_DIR;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.added;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.bytes;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.stillThere;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.submitRequest;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.tempFiles;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.under;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 5, D-72: file content stays where its parameter type stores it, and when a request
 * ends without a run (rejected, cancelled, expired, invalidated) Batch Control disposes of those
 * temporary files; once the approved run is queued, the queue and the build own them. Matrix rows
 * T-05-53 .. T-05-58 (note 260); T-05-88, T-05-89, T-05-91 and T-05-92 add "its approved run could
 * not be queued" with the queue refusing before and after Batch Control's gate (note 265,
 * spec-review-S7 M-1/M-2; {@link QueueRefusalFixtures}).
 *
 * <p>"The temporary copies that belonged to the request" are measured, not assumed: the regular
 * files under {@code $JENKINS_HOME/fileParameterValueFiles} and
 * {@code $JENKINS_HOME/stashedFileParameterValueFiles} that appeared with the submission (a premise
 * of every row asserts at least one in each directory). Empty directories are not pinned. Expiry
 * uses the plugin clock and the periodic work directly (matrix notes 1 and 2), never a wait.
 *
 * <p>Written from docs/SPEC.md item 5 and 7, docs/DECISIONS.md D-72 and the frozen D-72 contract
 * only (no src/main knowledge).
 */
@WithJenkins
public class TypedParameterDisposalTest {

    private static final Instant T0 = Instant.parse("2026-10-05T09:00:00Z");

    private JenkinsRule j;

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
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /** T-05-53: a rejected request's temporary files (core and stashed) are gone; nothing ran. */
    @Test
    public void t_05_53_rejectDisposesTheRequestsTemporaryFiles() throws Exception {
        FreeStyleProject job = twoFileJob("dispose-reject");
        Held held = submitWithBothFiles(job);

        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().reject(held.id, "wrong input files");
        }
        assertEquals(RequestStatus.REJECTED, RunRequestService.get().load(held.id).getStatus());
        assertDisposed("the rejected request", held);
        assertNothingRan(job);
    }

    /** T-05-54: a cancelled request's temporary files are gone; nothing ran. */
    @Test
    public void t_05_54_cancelDisposesTheRequestsTemporaryFiles() throws Exception {
        FreeStyleProject job = twoFileJob("dispose-cancel");
        Held held = submitWithBothFiles(job);

        try (ACLContext ignored = as("u1")) {
            RunRequestService.get().cancel(held.id);
        }
        assertEquals(RequestStatus.CANCELLED, RunRequestService.get().load(held.id).getStatus());
        assertDisposed("the cancelled request", held);
        assertNothingRan(job);
    }

    /** T-05-55: a request that expires while pending (pendingTimeoutHours) has its temporary files disposed of. */
    @Test
    public void t_05_55_pendingExpiryDisposesTheRequestsTemporaryFiles() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setPendingTimeoutHours(1);
        cfg.save();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        FreeStyleProject job = twoFileJob("dispose-expire");
        Held held = submitWithBothFiles(job);

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofHours(2)), ZoneOffset.UTC));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();

        assertEquals(RequestStatus.EXPIRED, RunRequestService.get().load(held.id).getStatus());
        assertDisposed("the expired request", held);
        assertNothingRan(job);
    }

    /** T-05-56: renaming the job invalidates the pending request and disposes of its temporary files. */
    @Test
    public void t_05_56_invalidationDisposesTheRequestsTemporaryFiles() throws Exception {
        FreeStyleProject job = twoFileJob("dispose-rename");
        Held held = submitWithBothFiles(job);

        job.renameTo("dispose-renamed");
        assertEquals(RequestStatus.INVALIDATED, RunRequestService.get().load(held.id).getStatus());
        assertDisposed("the invalidated request", held);
        assertNothingRan(job);
    }

    /**
     * T-05-57 (guard of T-05-53..56): disposing of one request's files leaves another pending
     * request's files in place, and that request's approved run still receives its own file.
     */
    @Test
    public void t_05_57_disposalTouchesOnlyTheEndedRequestsFiles() throws Exception {
        FreeStyleProject job = twoFileJob("dispose-two");
        Held first = submitWithBothFiles(job);
        byte[] secondContent = payload("second-request-marker-Gh72", 3000);
        Set<Path> before = tempFiles(j);
        Map<String, java.io.File> files = new LinkedHashMap<>();
        files.put("UPLOAD", uploadFile("second.csv", secondContent));
        files.put("DATA", uploadFile("second.bin", secondContent));
        String secondId = submitRequest(j, "u1", job, Map.of(), files);
        Set<Path> secondHeld = added(before, tempFiles(j));
        assertFalse(secondHeld.isEmpty(), "premise: the second request holds temporary files");

        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().reject(first.id, "only the second one");
        }
        assertDisposed("the rejected first request", first);
        assertEquals(secondHeld, stillThere(secondHeld), "the second, still pending request must keep its temporary files");

        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(secondId, "the second one");
        }
        j.waitUntilNoActivity();
        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build, "the second request must run");
        assertArrayEquals(secondContent, bytes(build.getWorkspace().child("UPLOAD")),
                "the second request's run must receive its own file");
    }

    /**
     * T-05-58: after the approved run, the request's temporary files are consumed as Jenkins
     * consumes them (core's file by the Freestyle build, the stashed file by the Pipeline), and none
     * is left behind; each run received its file (guard).
     */
    @Test
    public void t_05_58_approvedRunsConsumeTheTemporaryFilesWithoutLeftovers() throws Exception {
        byte[] coreContent = payload("consumed-core-marker-Jk19", 2500);
        FreeStyleProject freestyle = j.createFreeStyleProject("consume-core");
        freestyle.addProperty(new ParametersDefinitionProperty(new FileParameterDefinition("UPLOAD", "input")));
        setBatchControl(freestyle, new BatchControlJobProperty(true));
        Set<Path> before = tempFiles(j);
        String coreId = submitRequest(j, "u1", freestyle, Map.of(), Map.of("UPLOAD", uploadFile("data.csv", coreContent)));
        Set<Path> coreHeld = under(j, added(before, tempFiles(j)), CORE_TMP_DIR);
        assertFalse(coreHeld.isEmpty(), "premise: the core file is held under " + CORE_TMP_DIR);

        byte[] stashContent = payload("consumed-stash-marker-Lp83", 2500);
        WorkflowJob pipeline = j.createProject(WorkflowJob.class, "consume-stash");
        pipeline.setDefinition(new CpsFlowDefinition("node {\n  unstash 'DATA'\n}\n", true));
        pipeline.addProperty(new ParametersDefinitionProperty(new StashedFileParameterDefinition("DATA")));
        setBatchControl(pipeline, new BatchControlJobProperty(true));
        before = tempFiles(j);
        String stashId = submitRequest(j, "u1", pipeline, Map.of(), Map.of("DATA", uploadFile("report.bin", stashContent)));
        Set<Path> stashHeld = under(j, added(before, tempFiles(j)), STASH_TMP_DIR);
        assertFalse(stashHeld.isEmpty(), "premise: the stashed file is held under " + STASH_TMP_DIR);

        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(coreId, "ok");
            RunRequestService.get().approve(stashId, "ok");
        }
        j.waitUntilNoActivity();

        FreeStyleBuild build = freestyle.getBuildByNumber(1);
        assertNotNull(build);
        j.assertBuildStatusSuccess(build);
        assertArrayEquals(coreContent, bytes(build.getWorkspace().child("UPLOAD")), "guard: the Freestyle run received the file");
        j.assertBuildStatusSuccess(pipeline.getBuildByNumber(1));
        FilePath ws = j.jenkins.getWorkspaceFor(pipeline);
        assertArrayEquals(stashContent, bytes(ws.child("DATA")), "guard: the Pipeline run received the file");
        assertEquals(Set.of(), stillThere(coreHeld), "the Freestyle run consumed the core temporary file; nothing may be left");
        assertEquals(Set.of(), stillThere(stashHeld), "the Pipeline run consumed the stashed temporary file; nothing may be left");
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(coreId).getStatus());
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(stashId).getStatus());
    }

    // ---------------------------------------------------------------- the approved run could not be queued (note 265)

    /**
     * T-05-88 (S7 M-2, refused before the gate): with the queue refusing the job before Batch
     * Control's gate, a1 approves: APPROVED, nothing queued, and the request's files are kept while
     * it can still be submitted (guard); once {@code approvedRunTimeoutMinutes} passes it is EXPIRED
     * and none of its temporary files remains; nothing ran.
     */
    @Test
    public void t_05_88_approvedRunRefusedBeforeTheGateDisposesAtExpiry() throws Exception {
        approvedRunTimeout();
        FreeStyleProject job = twoFileJob("refuse-before-expire");
        Held held = submitWithBothFiles(job);

        QueueRefusalFixtures.refusedBeforeTheGate(job, () -> approve(held.id));
        assertApprovedNotQueued(job, held);

        expireApproval();
        assertEquals(RequestStatus.EXPIRED, RunRequestService.get().load(held.id).getStatus());
        assertDisposed("the approval that expired without being queued", held);
        assertNothingRan(job);
    }

    /**
     * T-05-89 (S7 M-1/M-2, refused after the gate): core's item-deletion veto refuses the approved
     * submission after Batch Control's gate accepted it: APPROVED, nothing queued, the files kept
     * while it can still be submitted (guard); at the approved-run timeout it is EXPIRED and none
     * of its temporary files remains.
     */
    @Test
    public void t_05_89_approvedRunRefusedAfterTheGateDisposesAtExpiry() throws Exception {
        approvedRunTimeout();
        FreeStyleProject job = twoFileJob("refuse-after-expire");
        Held held = submitWithBothFiles(job);

        QueueRefusalFixtures.refusedAfterTheGate(job, () -> approve(held.id));
        assertApprovedNotQueued(job, held);

        expireApproval();
        assertEquals(RequestStatus.EXPIRED, RunRequestService.get().load(held.id).getStatus());
        assertDisposed("the approval refused after the gate, then expired", held);
        assertNothingRan(job);
    }

    /**
     * T-05-91 (S7 M-2, D-21): an APPROVED request whose run was refused before the gate (never
     * queued) is INVALIDATED by a rename of its job, and none of its temporary files remains.
     */
    @Test
    public void t_05_91_renameOfAnApprovedNeverQueuedRequestDisposes() throws Exception {
        FreeStyleProject job = twoFileJob("refuse-before-rename");
        Held held = submitWithBothFiles(job);
        QueueRefusalFixtures.refusedBeforeTheGate(job, () -> approve(held.id));
        assertApprovedNotQueued(job, held);

        job.renameTo("refuse-before-renamed");
        assertEquals(RequestStatus.INVALIDATED, RunRequestService.get().load(held.id).getStatus());
        assertDisposed("the invalidated approval (refused before the gate)", held);
        assertNothingRan(job);
    }

    /**
     * T-05-92 (S7 M-1, D-21): the same with the run refused after the gate (core's item-deletion
     * veto): the rename INVALIDATES the request and none of its temporary files remains.
     */
    @Test
    public void t_05_92_renameOfAnApprovedRequestRefusedAfterTheGateDisposes() throws Exception {
        FreeStyleProject job = twoFileJob("refuse-after-rename");
        Held held = submitWithBothFiles(job);
        QueueRefusalFixtures.refusedAfterTheGate(job, () -> approve(held.id));
        assertApprovedNotQueued(job, held);

        job.renameTo("refuse-after-renamed");
        assertEquals(RequestStatus.INVALIDATED, RunRequestService.get().load(held.id).getStatus());
        assertDisposed("the invalidated approval (refused after the gate)", held);
        assertNothingRan(job);
    }

    // ---------------------------------------------------------------- helpers

    /** Refuses armed jobs before Batch Control's queue gate (QueueRefusalFixtures). */
    @TestExtension
    public static final class RefuseBeforeGate extends QueueRefusalFixtures.RefusingHandler {
    }

    private void approvedRunTimeout() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovedRunTimeoutMinutes(60);
        cfg.save();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
    }

    private void expireApproval() {
        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(61)), ZoneOffset.UTC));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
    }

    private void approve(String id) {
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(id, "approved; the queue refuses it");
        }
    }

    /** APPROVED, nothing queued or run, and every file of the request still there (it can still be submitted, SPEC item 4). */
    private void assertApprovedNotQueued(FreeStyleProject job, Held held) throws Exception {
        assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(held.id).getStatus(), "premise: approved");
        assertNothingRan(job);
        assertEquals(held.files, stillThere(held.files), "an approved request that can still be submitted must keep its files"
                + " (SPEC item 4: it is submitted after a restart; item 5: the build receives the original file)");
    }

    /** A request id with the temporary files that appeared with its submission. */
    private static final class Held {
        final String id;
        final Set<Path> files;

        Held(String id, Set<Path> files) {
            this.id = id;
            this.files = files;
        }
    }

    /** A Freestyle job with a core file parameter and a stashed file parameter (a Freestyle build never runs here). */
    private FreeStyleProject twoFileJob(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        job.addProperty(new ParametersDefinitionProperty(
                new FileParameterDefinition("UPLOAD", "core file"),
                new StashedFileParameterDefinition("DATA"),
                new StringParameterDefinition("DATE", "2000-01-01")));
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    /** Submits the Request Run form with both files; asserts the request holds a file in each directory. */
    private Held submitWithBothFiles(FreeStyleProject job) throws Exception {
        byte[] content = payload("dispose-marker-" + job.getName(), 2000);
        Set<Path> before = tempFiles(j);
        Map<String, java.io.File> files = new LinkedHashMap<>();
        files.put("UPLOAD", uploadFile("data.csv", content));
        files.put("DATA", uploadFile("report.bin", content));
        String id = submitRequest(j, "u1", job, Map.of("DATE", "2026-10-01"), files);
        Set<Path> held = added(before, tempFiles(j));
        assertFalse(under(j, held, CORE_TMP_DIR).isEmpty(), "premise: the core file value is held under " + CORE_TMP_DIR + ": " + held);
        assertFalse(under(j, held, STASH_TMP_DIR).isEmpty(), "premise: the stashed file value is held under " + STASH_TMP_DIR + ": " + held);
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus());
        return new Held(id, held);
    }

    private static void assertDisposed(String what, Held held) {
        assertEquals(Set.of(), stillThere(held.files), what + " must leave none of its temporary files behind");
    }

    private void assertNothingRan(FreeStyleProject job) throws Exception {
        j.waitUntilNoActivity();
        assertTrue(j.jenkins.getQueue().isEmpty(), "the queue must be empty");
        assertEquals(1, job.getNextBuildNumber(), "no build number may have been consumed");
        assertTrue(job.getBuilds().isEmpty(), "no build may exist");
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
