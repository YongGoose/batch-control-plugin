package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.Cause;
import hudson.model.Failure;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Items;
import hudson.model.Result;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedRunAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Future;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Red-team integrity rows adopted into SPEC (D-16, D-21, D-22, D-23).
 * Matrix rows T-RT-01 (empty upstream allow list blocks all), T-RT-02 (marker bound to the
 * request and consumed once), T-RT-03 (rename/move invalidates; a swapped job never runs),
 * T-RT-19 (field size limits).
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class RequestIntegrityTest {

    private JenkinsRule j;

    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /** T-RT-01: blockUpstream=true with an explicitly EMPTY allow list blocks every upstream job (D-16). */
    @Test
    public void t_rt_01_blockUpstreamWithEmptyAllowListBlocksAll() throws Exception {
        FreeStyleProject protectedJob = j.createFreeStyleProject("protected-b");
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockUpstream(true);
        property.setAllowedUpstreamJobs(Collections.emptyList()); // empty == unset == block all
        // run control is already on, so D-31 gave the job a property at creation; install this
        // one as the only one or blockUpstream would be shadowed (see BatchControlFixtures)
        setBatchControl(protectedJob, property);

        WorkflowJob caller = j.createProject(WorkflowJob.class, "caller-a");
        caller.setDefinition(new CpsFlowDefinition("build job: 'protected-b', wait: false", true));
        j.buildAndAssertStatus(Result.FAILURE, caller); // PoC side effect: blocked build step fails the caller

        j.waitUntilNoActivity();
        assertEquals(0, j.jenkins.getQueue().getItems().length);
        assertTrue(protectedJob.getBuilds().isEmpty(), "an empty allow list must block every upstream job");
        assertEquals(1, protectedJob.getNextBuildNumber());
    }

    /** T-RT-02: the approval marker is bound to the request and consumed by exactly one submission (D-23). */
    @Test
    public void t_rt_02_approvalMarkerIsSingleUseAndBoundToRequest() throws Exception {
        FreeStyleProject jobX = j.createFreeStyleProject("guarded-x");
        jobX.addProperty(new BatchControlJobProperty(true));
        FreeStyleProject jobY = j.createFreeStyleProject("guarded-y");
        jobY.addProperty(new BatchControlJobProperty(true));

        RunRequest request = createAs("u1", jobX);
        approveAs("a1", request.getId());
        j.waitUntilNoActivity();
        assertEquals(1, jobX.getBuilds().size(), "the approved submission runs exactly once");

        FreeStyleBuild firstBuild = jobX.getBuildByNumber(1);
        ApprovedRunAction marker = firstBuild.getAction(ApprovedRunAction.class);
        assertNotNull(marker, "the executed run must carry the marker action");
        assertEquals(request.getId(), marker.getRequestId(), "the marker must be bound to the request id");

        // replaying the very same marker on the same job (re-queue / rebuild path) must be blocked
        assertScheduleRefused("a consumed marker must not schedule the same job again",
                () -> jobX.scheduleBuild2(0, new Cause.UserIdCause(), marker));
        // and the marker must never authorize a different job
        assertScheduleRefused("a marker bound to job X must never authorize job Y",
                () -> jobY.scheduleBuild2(0, new Cause.UserIdCause(), marker));

        j.waitUntilNoActivity();
        assertEquals(1, jobX.getBuilds().size(), "job X must still have exactly one build");
        assertTrue(jobY.getBuilds().isEmpty(), "job Y must have no build at all");
    }

    /** T-RT-03: renaming the target of a PENDING request invalidates it; a name-swapped job never runs (D-21). */
    @Test
    public void t_rt_03_renameInvalidatesPendingAndSwappedJobNeverRuns() throws Exception {
        FreeStyleProject alpha = j.createFreeStyleProject("alpha");
        alpha.addProperty(new BatchControlJobProperty(true));
        RunRequest request = createAs("u1", alpha);

        alpha.renameTo("alpha-renamed");
        assertEquals(RequestStatus.INVALIDATED, RunRequestService.get().load(request.getId()).getStatus(), "renaming the target job must invalidate the request");

        // swap attack: a different, unreviewed job takes over the original name
        FreeStyleProject beta = j.createFreeStyleProject("beta");
        beta.renameTo("alpha");

        assertRefused("approving an INVALIDATED request must be refused",
                () -> approveAs("a1", request.getId()));

        j.waitUntilNoActivity();
        assertTrue(alpha.getBuilds().isEmpty(), "the reviewed (renamed) job must not have run");
        assertTrue(beta.getBuilds().isEmpty(), "the swapped-in job must never run under the old approval");
        RunRequest reloaded = RunRequestService.get().load(request.getId());
        assertEquals(RequestStatus.INVALIDATED, reloaded.getStatus());
        assertNull(reloaded.getExecutedRunId());
    }

    /** T-RT-03 (move variant): moving the target job into a folder invalidates the PENDING request (D-21). */
    @Test
    public void t_rt_03_moveIntoFolderInvalidatesPendingRequest() throws Exception {
        FreeStyleProject gamma = j.createFreeStyleProject("gamma");
        gamma.addProperty(new BatchControlJobProperty(true));
        RunRequest request = createAs("u1", gamma);

        Folder folder = j.jenkins.createProject(Folder.class, "team");
        Items.move(gamma, folder);

        assertEquals(RequestStatus.INVALIDATED, RunRequestService.get().load(request.getId()).getStatus(), "moving the target job must invalidate the request");
        assertRefused("approving after the move must be refused",
                () -> approveAs("a1", request.getId()));
        j.waitUntilNoActivity();
        assertTrue(gamma.getBuilds().isEmpty());
    }

    /** T-RT-03 (approved variant): rename after approval but before submission also invalidates (D-21). */
    @Test
    public void t_rt_03_renameAfterApprovalInvalidatesBeforeSubmit() throws Exception {
        FreeStyleProject delta = j.createFreeStyleProject("delta");
        delta.addProperty(new BatchControlJobProperty(true));
        RunRequest request = createAs("u1", delta);

        j.jenkins.doQuietDown(); // keep the approval from starting a build
        approveAs("a1", request.getId());
        j.jenkins.getQueue().clear(); // model "approved but not yet submitted"
        assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(request.getId()).getStatus());

        delta.renameTo("delta-renamed");
        assertEquals(RequestStatus.INVALIDATED, RunRequestService.get().load(request.getId()).getStatus(), "renaming an APPROVED (not yet submitted) target must invalidate the request");

        j.jenkins.doCancelQuietDown();
        j.waitUntilNoActivity();
        assertTrue(delta.getBuilds().isEmpty(), "the invalidated approval must never execute");
    }

    /** T-RT-19: reason over 4,000 chars or a parameter value over 10,000 chars rejects creation (D-22). */
    @Test
    public void t_rt_19_sizeLimitsRejectOversizedRequests() throws Exception {
        FreeStyleProject target = j.createFreeStyleProject("sized-x");
        target.addProperty(new BatchControlJobProperty(true));

        assertRejectedAsInvalid("a reason over 4,000 characters must reject creation",
                () -> createWith(target, "r".repeat(4001), param("DATA", "small")));
        assertRejectedAsInvalid("a parameter value over 10,000 characters must reject creation",
                () -> createWith(target, "normal batch reason", param("DATA", "x".repeat(10001))));
        assertTrue(RunRequestService.get().list().isEmpty(), "nothing may be stored from rejected creations");

        // boundary values are still accepted
        RunRequest atLimit = createWith(target, "k".repeat(4000), param("DATA", "y".repeat(10000)));
        assertEquals(RequestStatus.PENDING, atLimit.getStatus());
    }

    // ---------------------------------------------------------------- helpers

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private RunRequest createAs(String userId, FreeStyleProject target) {
        try (ACLContext ignored = as(userId)) {
            return RunRequestService.get()
                    .create(target, new LinkedHashMap<>(), "integrity check run", "a1");
        }
    }

    private RunRequest createWith(FreeStyleProject target, String reason, Map<String, String> parameters) {
        try (ACLContext ignored = as("u1")) {
            return RunRequestService.get().create(target, parameters, reason, "a1");
        }
    }

    private void approveAs(String userId, String requestId) {
        try (ACLContext ignored = as(userId)) {
            RunRequestService.get().approve(requestId, "ok");
        }
    }

    private static Map<String, String> param(String key, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    /** A refused schedule may either return null (silent refusal) or throw Failure (user guidance). */
    private static void assertScheduleRefused(String message, ScheduleAttempt attempt) throws Exception {
        try {
            Future<?> future = attempt.call();
            assertNull(future, message);
        } catch (Failure expectedGuidance) {
            // throwing the guidance Failure is equally acceptable
        }
    }

    @FunctionalInterface
    private interface ScheduleAttempt {
        Future<?> call() throws Exception;
    }

    private static void assertRejectedAsInvalid(String message, Executable action) {
        boolean rejected = false;
        try {
            action.execute();
        } catch (IllegalArgumentException | Failure expected) {
            rejected = true;
        } catch (Throwable other) {
            throw new AssertionError(message + " - expected IllegalArgumentException or Failure, got " + other, other);
        }
        assertTrue(rejected, message);
    }

    private static void assertRefused(String message, Executable action) {
        boolean refused = false;
        try {
            action.execute();
        } catch (RuntimeException expected) {
            refused = true;
        } catch (Throwable other) {
            throw new AssertionError(message + " - unexpected exception " + other, other);
        }
        assertTrue(refused, message);
    }
}
