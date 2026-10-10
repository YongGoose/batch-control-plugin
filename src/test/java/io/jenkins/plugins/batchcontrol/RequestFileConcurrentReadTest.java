package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 4 (the store) with the SPEC 4 state machine {@code PENDING -> APPROVED -> EXECUTED}:
 * a short concurrent read of a run request's stored file does not make an approval fail.
 * Matrix row T-04-21 (note 292).
 *
 * <p>Background: on Windows a file cannot be replaced while another handle has it open without
 * delete-sharing, and {@link FileInputStream} opens files that way. On ci.jenkins.io (windows,
 * JDK 21) T-05-14/15 failed intermittently because the replace of
 * {@code requests/run/<id>.xml} by its temporary file was refused ({@code AccessDeniedException}),
 * so the request stayed APPROVED. The contract agreed with core-dev: while something holds the
 * request's stored file open for reading with a plain {@code FileInputStream} for about
 * 300-500 ms, an approval still completes (EXECUTED, decidedBy the approver, exactly one build);
 * the store tolerates a transient open of at least about one second. On Linux and macOS an open
 * reader never blocks a replace, so this row passes there with or without the fix; it is the
 * Windows workflow that makes it meaningful.
 *
 * <p>Each round holds the file twice, so that both writes of the request are made while it is
 * held: (A) the approval's own write, sent while the file is held and while no executor is
 * available, so the build cannot start yet; (B) the run-start write (APPROVED to EXECUTED),
 * with the file held again before an executor is given back and kept held until the build
 * exists plus 400 ms. Three rounds on three jobs make a lucky pass on an unfixed Windows store
 * unlikely.
 *
 * <p>The stored file is located from the documented layout (ARCHITECTURE section 5):
 * {@code JENKINS_HOME/batch-control/requests/run/<id>.xml}.
 *
 * Written from docs/SPEC.md, docs/ARCHITECTURE.md section 5 and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
@Tag("core")
public class RequestFileConcurrentReadTest {

    private static final Logger LOGGER = Logger.getLogger(RequestFileConcurrentReadTest.class.getName());

    /** How long the reader keeps the file open once the write it targets has been set in motion. */
    private static final long HOLD_MILLIS = 400;

    /** Upper bound for any wait on the other thread; never reached on a passing run. */
    private static final long BOUND_SECONDS = 60;

    private static final int ROUNDS = 3;

    private JenkinsRule j;
    private final List<FreeStyleProject> jobs = new ArrayList<>();

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        // created while run control is still off, so the jobs are in service (D-45)
        for (int i = 1; i <= ROUNDS; i++) {
            jobs.add(j.createFreeStyleProject("batch-read-" + i));
        }

        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        for (FreeStyleProject job : jobs) {
            setBatchControl(job, new BatchControlJobProperty(true));
        }
    }

    /**
     * T-04-21: a run request's stored file is held open by a plain {@code FileInputStream} for
     * about 400 ms while the approval writes it, and again while the approved run starts -> the
     * approval succeeds, the request is EXECUTED, decided by a1, exactly one build runs and the
     * next build number is 2; guard: a second approval is refused and still one build. Three rounds.
     */
    @Test
    public void t_04_21_approvalSurvivesConcurrentReadOfRequestFile() throws Exception {
        int executors = j.jenkins.getNumExecutors();
        assertTrue(executors > 0, "fixture: the controller must have an executor to give back");
        for (int round = 1; round <= ROUNDS; round++) {
            runRound(round, jobs.get(round - 1), executors);
        }
    }

    private void runRound(int round, FreeStyleProject job, int executors) throws Exception {
        String what = "round " + round + " (" + job.getFullName() + "): ";
        String id = ApproverFormFixtures.submitRunOk(j, "u1", job, "month-end batch " + round, "a1");
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus(), what + "fixture: the request is PENDING");
        assertEquals(1, job.getNextBuildNumber(), what + "fixture: no build number consumed yet");

        Path file = StoreDataFixtures.storeDir().resolve("requests").resolve("run").resolve(id + ".xml");
        assertTrue(Files.isRegularFile(file), what + "fixture: the request is stored at the documented location " + file);

        // Log in and warm the request's pages before anything is held, so that the approval POST
        // below reaches the store write within a few milliseconds of being sent.
        JenkinsRule.WebClient a1 = ApproverFormFixtures.client(j, "a1");
        WebResponse warm = a1.getPage(new WebRequest(new java.net.URL(j.getURL(), "batch-control/requests/" + id + "/"),
                HttpMethod.GET)).getWebResponse();
        assertEquals(200, warm.getStatusCode(), what + "fixture: the designated approver opens the request page");

        // ---- (A) the approval's write, made while the file is held and no executor is available
        j.jenkins.setNumExecutors(0);
        CountDownLatch postSent = new CountDownLatch(1);
        Holder holdA = Holder.start(file, () -> await(postSent), () -> statusOf(id));
        WebResponse response;
        long answeredAt;
        try {
            holdA.awaitOpen(what);
            WebRequest approve = new WebRequest(a1.createCrumbedUrl("batch-control/requests/" + id + "/approve"),
                    HttpMethod.POST);
            approve.setRequestParameters(new ArrayList<>(List.of(new NameValuePair("comment", "ok"))));
            postSent.countDown(); // the holder's 400 ms start now, with the POST on its way
            response = a1.getPage(approve).getWebResponse();
            answeredAt = System.nanoTime();
        } finally {
            postSent.countDown();
            holdA.finish(what + "(A) ");
        }
        long responseAfterRelease = answeredAt - holdA.closedAt.get();
        LOGGER.info(what + "(A) status just before the read handle was closed: " + holdA.statusAtClose.get()
                + "; approval answered HTTP " + response.getStatusCode() + ", "
                + TimeUnit.NANOSECONDS.toMillis(responseAfterRelease) + " ms after the read handle was closed"
                + " (negative: answered while it was still open)");

        ApproverFormFixtures.assertSuccess(response, what + "the approval while the request file is held open for reading");
        RunRequest approved = RunRequestService.get().load(id);
        assertEquals(RequestStatus.APPROVED, approved.getStatus(),
                what + "the approval must have been stored although the file was held open for reading");
        assertEquals("a1", approved.getDecidedBy(), what + "the approval must name a1 as the decider");
        assertTrue(job.getBuilds().isEmpty(), what + "fixture: no build before an executor is available");

        // ---- (B) the run-start write (APPROVED -> EXECUTED), made while the file is held again
        Holder holdB = Holder.start(file, () -> waitFor(() -> job.getLastBuild() != null), () -> statusOf(id));
        try {
            holdB.awaitOpen(what);
            j.jenkins.setNumExecutors(executors);
            j.jenkins.getQueue().scheduleMaintenance();
        } finally {
            holdB.finish(what + "(B) ");
        }
        LOGGER.info(what + "(B) status just before the read handle was closed: " + holdB.statusAtClose.get()
                + " (EXECUTED: the run-start write was made while the file was held; APPROVED: the store was waiting)");

        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size(), what + "the approved request must have run exactly once");
        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build, what + "build #1 must exist");
        j.assertBuildStatusSuccess(build);
        RunRequest executed = RunRequestService.get().load(id);
        assertEquals(RequestStatus.EXECUTED, executed.getStatus(),
                what + "the request must move to EXECUTED, not stay APPROVED, although its file was held open for reading");
        assertEquals("a1", executed.getDecidedBy(), what + "the decider must still be a1");
        assertEquals(0, j.jenkins.getQueue().getItems().length, what + "nothing further may be queued");
        assertEquals(2, job.getNextBuildNumber(), what + "exactly one build number must have been consumed");

        // ---- guard: the request is decided once
        ApproverFormFixtures.assertClientError(ApproverFormFixtures.decideRun(j, "a1", id, "approve", "again"),
                what + "a second approval of the executed request");
        j.waitUntilNoActivity();
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(id).getStatus(), what + "the request stays EXECUTED");
        assertEquals(1, job.getBuilds().size(), what + "the refused second approval must not run the job again");
        assertEquals(2, job.getNextBuildNumber(), what + "the refused second approval must not consume a build number");
        assertEquals(0, j.jenkins.getQueue().getItems().length, what + "the refused second approval must queue nothing");
    }

    // ---------------------------------------------------------------- helpers

    /** The request's status for the timing log; never asserted, so a failure to read is just reported. */
    private static String statusOf(String id) {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // test diagnostics only, outside any permission check
            return String.valueOf(RunRequestService.get().load(id).getStatus());
        } catch (Exception e) {
            return "unavailable (" + e + ")";
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(BOUND_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the main thread never signalled");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void waitFor(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("the awaited condition never became true");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * A background reader: opens {@code file} with a plain {@link FileInputStream} (the Windows
     * sharing mode without delete-sharing), reads one byte, signals that it is open, waits for its
     * trigger (the write it targets has been set in motion), keeps the file open {@link #HOLD_MILLIS}
     * longer, then closes it.
     */
    private static final class Holder {
        private final CountDownLatch open = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicLong closedAt = new AtomicLong();
        private final AtomicReference<String> statusAtClose = new AtomicReference<>();
        private Thread thread;

        static Holder start(Path file, Runnable trigger, Supplier<String> diagnostic) {
            Holder holder = new Holder();
            holder.thread = new Thread(() -> {
                try (FileInputStream in = new FileInputStream(file.toFile())) {
                    if (in.read() < 0) {
                        throw new IllegalStateException("the request file is empty: " + file);
                    }
                    holder.open.countDown();
                    trigger.run();
                    Thread.sleep(HOLD_MILLIS); // holding a read handle, not waiting for an expiry
                    holder.statusAtClose.set(diagnostic.get()); // for the log only, never asserted
                } catch (Throwable t) {
                    holder.failure.set(t);
                } finally {
                    holder.closedAt.set(System.nanoTime());
                    holder.open.countDown();
                }
            }, "request-file-reader");
            holder.thread.setDaemon(true);
            holder.thread.start();
            return holder;
        }

        void awaitOpen(String what) throws InterruptedException {
            assertTrue(open.await(BOUND_SECONDS, TimeUnit.SECONDS), what + "fixture: the reader must open the file");
            assertNull(failure.get(), what + "fixture: the reader must open and read the request file: " + failure.get());
        }

        void finish(String what) throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS + 5));
            assertFalse(thread.isAlive(), what + "fixture: the reader must have closed the file");
            assertNull(failure.get(), what + "fixture: the reader failed: " + failure.get());
        }
    }
}
