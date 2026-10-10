package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run request does not outlive its job (issue #38). Deleting a job, directly or with a folder above it,
 * ends its PENDING run requests INVALIDATED with a reason that names the deletion; approving a run request
 * whose job no longer exists, or was re-created under the same name after the request was made, is refused
 * with a 4xx and schedules no build; a job re-created under the old name never receives a build from a
 * request made for the deleted job. Matrix rows T-07-13 .. T-07-16 (note 306).
 *
 * <p>Basis: SPEC 7 (D-21: a PENDING/APPROVED request whose job is renamed or moved ends INVALIDATED and
 * stays in the history), SPEC 6a (deleting a job removes its activation and ends its pending activation
 * requests; a job re-created under a deleted job's name starts over), SPEC 6 usability ("why a request was
 * invalidated"; no refusal from our own code is an "Oops!" page), and the Wave A contract for #38 frozen by
 * the main session on 2026-10-10 (deletion invalidates PENDING run requests, approval of a request whose job
 * is gone or re-created is refused, the re-created job never runs it).
 *
 * <p>Fixture: run control on; r (Overall/Read, Item/Read, Item/Build, BatchControl/Request) requests; a1
 * (Overall/Read, Item/Read, Approve; the only approver) decides; admin deletes and re-creates jobs. The
 * missed-event row (T-07-16) replaces a request file with the first half of its own XML while the job is
 * deleted and writes it back byte for byte afterwards (as UnreadableOpenRequestGapTest does for a rename).
 *
 * <p>Written from docs/SPEC.md items 6, 6a and 7, docs/ARCHITECTURE.md section 5 (store layout), the issue
 * text of #38 and the Wave A contract only (no src/main knowledge).
 */
@WithJenkins
public class RunRequestDeletedJobTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("r")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-07-13 (#38 contract 1 and 2): r's PENDING run requests on the approval-required {@code del-x} and
     * {@code del-keep}. The administrator deletes {@code del-x}. Its request ends INVALIDATED, with a reason
     * that names the deletion and the job; a1's approval of it is refused (4xx, no "Oops!" page), it stays
     * INVALIDATED without a run and nothing is queued. Guard: the request on {@code del-keep} is still PENDING;
     * a1's approval of it is accepted and {@code del-keep} runs exactly once.
     */
    @Test
    public void t_07_13_deletingAJobInvalidatesItsPendingRunRequest() throws Exception {
        FreeStyleProject deleted = controlled("del-x");
        FreeStyleProject kept = controlled("del-keep");
        String onDeleted = submitRunOk(j, "r", deleted, "month-end run on del-x", "a1");
        String onKept = submitRunOk(j, "r", kept, "month-end run on del-keep", "a1");

        as("admin", () -> {
            deleted.delete();
            return null;
        });
        assertNull(j.jenkins.getItemByFullName("del-x"), "premise: del-x is deleted");

        RunRequest invalidated = RunRequestService.get().load(onDeleted);
        assertEquals(RequestStatus.INVALIDATED, invalidated.getStatus(),
                "#38: deleting the job ends its PENDING run request INVALIDATED");
        assertMentionsDeletion(invalidated, "del-x");

        WebResponse refused = decideRun(j, "a1", onDeleted, "approve", "approved after the deletion");
        assertClientError(refused, "#38: approving the run request of a deleted job");
        assertNoOops(refused, "the refusal of the deleted job's request");
        RunRequest after = RunRequestService.get().load(onDeleted);
        assertEquals(RequestStatus.INVALIDATED, after.getStatus(), "the refused approval leaves the request INVALIDATED");
        assertNull(after.getExecutedRunId(), "the refused request has no run");
        j.waitUntilNoActivity();
        assertEquals(0, j.jenkins.getQueue().getItems().length, "nothing is queued for the deleted job's request");

        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(onKept).getStatus(),
                "guard: the request on the job that was not deleted is still PENDING");
        assertSuccess(decideRun(j, "a1", onKept, "approve", "ok"), "guard: a1's approval of the request on del-keep");
        j.waitUntilNoActivity();
        assertEquals(1, kept.getBuilds().size(), "guard: the approved request on del-keep runs exactly once");
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(onKept).getStatus(), "guard: EXECUTED");
    }

    /**
     * T-07-14 (#38 contract 1, deletion of a folder above the job): r's PENDING run requests on
     * {@code del-fold/a} and on {@code del-other/a} (same short name, another folder). The administrator
     * deletes the folder {@code del-fold}. The request on {@code del-fold/a} ends INVALIDATED with a reason
     * that names the deletion and the folder (or the job's full name, which contains it). Guard: the request
     * on {@code del-other/a} is still PENDING, and a1's approval of it runs {@code del-other/a} exactly once.
     */
    @Test
    public void t_07_14_deletingAFolderInvalidatesThePendingRunRequestsInside() throws Exception {
        Folder folder = j.jenkins.createProject(Folder.class, "del-fold");
        FreeStyleProject inside = controlled(folder.createProject(FreeStyleProject.class, "a"));
        Folder other = j.jenkins.createProject(Folder.class, "del-other");
        FreeStyleProject outside = controlled(other.createProject(FreeStyleProject.class, "a"));
        String onInside = submitRunOk(j, "r", inside, "run inside the deleted folder", "a1");
        String onOutside = submitRunOk(j, "r", outside, "run in the other folder", "a1");

        as("admin", () -> {
            folder.delete();
            return null;
        });
        assertNull(j.jenkins.getItemByFullName("del-fold/a"), "premise: del-fold/a is gone with its folder");

        RunRequest invalidated = RunRequestService.get().load(onInside);
        assertEquals(RequestStatus.INVALIDATED, invalidated.getStatus(),
                "#38: deleting a folder ends the PENDING run requests of the jobs inside it INVALIDATED");
        assertMentionsDeletion(invalidated, "del-fold");
        assertClientError(decideRun(j, "a1", onInside, "approve", "approved after the folder deletion"),
                "#38: approving the run request of a job deleted with its folder");

        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(onOutside).getStatus(),
                "guard: the request on the same-named job in another folder is still PENDING");
        assertSuccess(decideRun(j, "a1", onOutside, "approve", "ok"), "guard: a1's approval of del-other/a");
        j.waitUntilNoActivity();
        assertEquals(1, outside.getBuilds().size(), "guard: del-other/a runs exactly once");
    }

    /**
     * T-07-15 (#38 contract 2 and 3): r's PENDING run request R on {@code del-re}. The administrator deletes
     * {@code del-re} and creates a new approval-required job under the same name. a1's approval of R is
     * refused (4xx); R is neither APPROVED nor EXECUTED and has no run; the re-created job is not queued, has
     * no build and its next build number is unchanged. Guard: a request r makes on the re-created job after
     * its creation is approved by a1 and runs the re-created job exactly once.
     */
    @Test
    public void t_07_15_aJobReCreatedUnderTheOldNameNeverRunsTheDeletedJobsRequest() throws Exception {
        FreeStyleProject original = controlled("del-re");
        String stale = submitRunOk(j, "r", original, "run on the original del-re", "a1");

        as("admin", () -> {
            original.delete();
            return null;
        });
        FreeStyleProject recreated = controlled("del-re");
        assertNotEquals(original, recreated, "premise: a new job object stands at the old name");
        int nextBefore = recreated.getNextBuildNumber();

        WebResponse refused = decideRun(j, "a1", stale, "approve", "approved after the re-creation");
        assertClientError(refused, "#38: approving a request whose job was deleted and re-created under the same name");
        assertNoOops(refused, "the refusal of the stale request");
        RunRequest after = RunRequestService.get().load(stale);
        assertNotEquals(RequestStatus.APPROVED, after.getStatus(), "the stale request is not APPROVED");
        assertNotEquals(RequestStatus.EXECUTED, after.getStatus(), "the stale request is not EXECUTED");
        assertNull(after.getExecutedRunId(), "the stale request has no run");
        assertNotRun(recreated, nextBefore, "#38: the re-created del-re never receives a build from the deleted job's request");

        String fresh = submitRunOk(j, "r", recreated, "run on the re-created del-re", "a1");
        assertSuccess(decideRun(j, "a1", fresh, "approve", "ok"), "guard: a1's approval of a request on the re-created job");
        j.waitUntilNoActivity();
        assertEquals(1, recreated.getBuilds().size(), "guard: the re-created job runs exactly once on its own request");
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(fresh).getStatus(), "guard: EXECUTED");
    }

    /**
     * T-07-16 (#38 contract 2 and 3, independent of the deletion event): r's PENDING run requests on
     * {@code miss-re}, {@code miss-gone} and {@code miss-keep}; the three request files are replaced with the
     * first half of their own XML. The administrator deletes {@code miss-re} and {@code miss-gone} and
     * re-creates an approval-required {@code miss-re}; the three files are written back byte for byte. a1's
     * approvals of the requests on {@code miss-re} (re-created) and {@code miss-gone} (no longer exists) are
     * refused (4xx); neither request is APPROVED or EXECUTED or has a run; the re-created {@code miss-re} has
     * no build and its next build number is unchanged; nothing is queued. Guard: the damaged and restored
     * request on {@code miss-keep} is approved and runs {@code miss-keep} exactly once.
     */
    @Test
    public void t_07_16_approvalIsRefusedWhenTheJobVanishedWhileTheRequestWasUnreadable() throws Exception {
        FreeStyleProject reCreated = controlled("miss-re");
        FreeStyleProject gone = controlled("miss-gone");
        FreeStyleProject kept = controlled("miss-keep");
        String onReCreated = submitRunOk(j, "r", reCreated, "run on miss-re", "a1");
        String onGone = submitRunOk(j, "r", gone, "run on miss-gone", "a1");
        String onKept = submitRunOk(j, "r", kept, "run on miss-keep", "a1");
        Path fRe = requestFile(onReCreated);
        Path fGone = requestFile(onGone);
        Path fKept = requestFile(onKept);
        byte[] originalRe = damage(fRe);
        byte[] originalGone = damage(fGone);
        byte[] originalKept = damage(fKept);

        as("admin", () -> {
            reCreated.delete();
            gone.delete();
            return null;
        });
        FreeStyleProject newJob = controlled("miss-re");
        int nextBefore = newJob.getNextBuildNumber();
        assertNull(j.jenkins.getItemByFullName("miss-gone"), "premise: miss-gone is deleted");
        Files.write(fRe, originalRe);
        Files.write(fGone, originalGone);
        Files.write(fKept, originalKept);

        assertClientError(decideRun(j, "a1", onReCreated, "approve", "approved after the re-creation"),
                "#38: approving a request whose job was re-created under the same name while its file was unreadable");
        assertClientError(decideRun(j, "a1", onGone, "approve", "approved after the deletion"),
                "#38: approving a request whose job no longer exists");
        for (String id : new String[] {onReCreated, onGone}) {
            RunRequest req = RunRequestService.get().load(id);
            assertNotEquals(RequestStatus.APPROVED, req.getStatus(), id + " is not APPROVED");
            assertNotEquals(RequestStatus.EXECUTED, req.getStatus(), id + " is not EXECUTED");
            assertNull(req.getExecutedRunId(), id + " has no run");
        }
        assertNotRun(newJob, nextBefore, "#38: the re-created miss-re never receives a build from the deleted job's request");

        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(onKept).getStatus(),
                "guard: the restored request on miss-keep is PENDING");
        assertSuccess(decideRun(j, "a1", onKept, "approve", "ok"), "guard: a1's approval of the restored request on miss-keep");
        j.waitUntilNoActivity();
        assertEquals(1, kept.getBuilds().size(), "guard: miss-keep runs exactly once");
    }

    // ------------------------------------------------------------------ helpers

    private FreeStyleProject controlled(String name) throws Exception {
        return controlled(j.createFreeStyleProject(name));
    }

    private static FreeStyleProject controlled(FreeStyleProject job) throws Exception {
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    private void assertNotRun(FreeStyleProject job, int nextBefore, String what) throws Exception {
        j.waitUntilNoActivity();
        assertEquals(0, j.jenkins.getQueue().getItems().length, what + ": the queue is empty");
        assertTrue(job.getBuilds().isEmpty(), what + ": no build");
        assertEquals(nextBefore, job.getNextBuildNumber(), what + ": the next build number is unchanged");
    }

    private static void assertMentionsDeletion(RunRequest request, String name) {
        String reason = request.getDecisionComment();
        System.out.println("T-07 observation: invalidation reason: " + reason);
        assertNotNull(reason, "#38: the invalidation gives its reason");
        assertTrue(reason.toLowerCase(Locale.ROOT).contains("delet"), "#38: the reason names the deletion: " + reason);
        assertTrue(reason.contains(name), "#38: the reason names " + name + ": " + reason);
    }

    private static void assertNoOops(WebResponse response, String what) {
        String body = response.getContentAsString();
        System.out.println("T-07 observation: " + what + ": HTTP " + response.getStatusCode() + ": " + excerpt(body));
        assertFalse(body != null && body.contains("Oops!"), what + " is not an \"Oops!\" page: " + excerpt(body));
    }

    private Path requestFile(String id) {
        return j.jenkins.getRootDir().toPath().resolve("batch-control").resolve("requests/run/" + id + ".xml");
    }

    /** Replaces {@code file} with the first half of its own XML (premise: not readable XML); returns the original bytes. */
    private static byte[] damage(Path file) throws Exception {
        assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): the request is stored at " + file);
        byte[] original = Files.readAllBytes(file);
        String xml = new String(original, StandardCharsets.UTF_8);
        String damaged = xml.substring(0, xml.length() / 2);
        assertThrows(RuntimeException.class, () -> Jenkins.XSTREAM2.fromXML(damaged), "premise: the damaged copy is not readable XML");
        Files.writeString(file, damaged, StandardCharsets.UTF_8);
        return original;
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
