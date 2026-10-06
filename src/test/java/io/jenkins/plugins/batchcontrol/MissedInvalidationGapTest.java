package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.nio.charset.StandardCharsets;
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
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An invalidation that an open request missed because its file could not be read is kept in memory, and the
 * per-minute work ends the request INVALIDATED once its file can be read and written, without anyone deciding
 * it; a request on an unrelated job is not touched. Matrix rows T-GAP-432 .. T-GAP-436 (note 283).
 *
 * <p>Basis: SPEC 7 (D-21: a PENDING request whose job is renamed or moved ends INVALIDATED), SPEC 6a (activation
 * requests are decided like run requests; deleting the job ends its open activation requests), SPEC 4 (the
 * per-minute work), LIMITATIONS 30 and 32 ("within a minute of its file becoming readable and writable it ends
 * INVALIDATED if the invalidation concerns its job"). Fixtures as in {@link UnreadableOpenRequestGapTest}: a
 * request file is replaced with the first half of its own XML and later written back byte for byte; time moves
 * only with the plugin clock ({@link BatchClock}); {@link ExpiryPeriodicWork#doRun()} is invoked directly. No
 * request is decided by anyone in these rows. No src/main knowledge.
 */
@WithJenkins
public class MissedInvalidationGapTest {

    private static final Instant T0 = Instant.parse("2026-10-06T00:00:00Z");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        at(T0);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("r")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.setPendingTimeoutHours(1);
        cfg.save();
    }

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
    }

    /**
     * T-GAP-432 (SPEC 6a, 7 D-21, LIMITATIONS 32): r's pending ACTIVATE request A1 on {@code mi-j}, its file
     * damaged; the administrator renames {@code mi-j}; A1's file written back; the periodic work at T0+1 min
     * ends A1 INVALIDATED with a reason naming the rename, decided by nobody.
     */
    @Test
    public void t_gap_432_missedRenameEndsActivationRequestOnThePeriodicPath() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("mi-j");
        String a1 = activate(job, "mi one");
        Path f = activationFile(a1);
        byte[] original = damage(f);
        rename(job, "mi-j2");
        Files.write(f, original);
        periodicAt(1);
        ActivationRequest req = ActivationService.get().load(a1);
        assertEquals(RequestStatus.INVALIDATED, req.getStatus(), "LIMITATIONS 32, D-21: the periodic work ends the restored request");
        assertMentionsRename(req.getDecisionComment());
        assertNotEquals("a1", req.getDecidedBy(), "nobody approved it");
    }

    /**
     * T-GAP-433 (SPEC 6a, LIMITATIONS 32): folder {@code mi-f} with {@code mi-f/j} and r's pending ACTIVATE
     * request A2 on it, A2's file damaged; the administrator deletes the folder; A2's file written back; the
     * periodic work at T0+1 min ends A2 INVALIDATED.
     */
    @Test
    public void t_gap_433_missedFolderDeletionEndsActivationRequestOnThePeriodicPath() throws Exception {
        Folder folder = j.jenkins.createProject(Folder.class, "mi-f");
        FreeStyleProject job = folder.createProject(FreeStyleProject.class, "j");
        String a2 = activate(job, "mi folder");
        Path f = activationFile(a2);
        byte[] original = damage(f);
        as("admin", () -> {
            folder.delete();
            return null;
        });
        assertNull(j.jenkins.getItemByFullName("mi-f/j"), "premise: the job is gone with its folder");
        Files.write(f, original);
        periodicAt(1);
        ActivationRequest req = ActivationService.get().load(a2);
        System.out.println("T-GAP-433 observation: reason: " + req.getDecisionComment());
        assertEquals(RequestStatus.INVALIDATED, req.getStatus(), "SPEC 6a, LIMITATIONS 32: the restored request of a deleted job ends INVALIDATED");
        assertNotEquals("a1", req.getDecidedBy(), "nobody approved it");
    }

    /**
     * T-GAP-434 (SPEC 7 D-21, LIMITATIONS 32): r's pending run request R1 on the approval-required
     * {@code mi-run}, its file damaged; renamed; R1's file written back; the periodic work at T0+1 min ends R1
     * INVALIDATED with a reason naming the rename, with no approval attempt; nothing runs or is queued.
     */
    @Test
    public void t_gap_434_missedRenameEndsRunRequestOnThePeriodicPath() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("mi-run");
        setBatchControl(job, new BatchControlJobProperty(true));
        String r1 = as("r", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "mi run", "a1").getId());
        Path f = store().resolve("requests/run/" + r1 + ".xml");
        byte[] original = damage(f);
        rename(job, "mi-run2");
        Files.write(f, original);
        periodicAt(1);
        RunRequest req = RunRequestService.get().load(r1);
        assertEquals(RequestStatus.INVALIDATED, req.getStatus(), "SPEC 7 D-21, LIMITATIONS 32: the periodic work ends the restored request");
        assertMentionsRename(req.getDecisionComment());
        assertNull(req.getExecutedRunId(), "no run");
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "nothing ran");
        assertEquals(0, j.jenkins.getQueue().getItems().length, "nothing queued");
    }

    /**
     * T-GAP-435 (LIMITATIONS 32 "if the invalidation concerns its job"): ACTIVATE requests on {@code mi-jj}
     * (guard) and on the unrelated {@code mi-k}, both damaged during {@code mi-jj}'s rename, both written back;
     * the periodic work: the guard is INVALIDATED, {@code mi-k}'s request stays PENDING (also after another run).
     */
    @Test
    public void t_gap_435_unrelatedJobRequestStaysPending() throws Exception {
        FreeStyleProject renamed = j.createFreeStyleProject("mi-jj");
        FreeStyleProject other = j.createFreeStyleProject("mi-k");
        String a1 = activate(renamed, "mi renamed");
        String k1 = activate(other, "mi other");
        Path fa = activationFile(a1);
        Path fk = activationFile(k1);
        byte[] origA = damage(fa);
        byte[] origK = damage(fk);
        rename(renamed, "mi-jj2");
        Files.write(fa, origA);
        Files.write(fk, origK);
        periodicAt(1);
        assertEquals(RequestStatus.INVALIDATED, ActivationService.get().load(a1).getStatus(), "guard: the renamed job's request is ended");
        assertEquals(RequestStatus.PENDING, ActivationService.get().load(k1).getStatus(), "LIMITATIONS 32: the unrelated job's request stays PENDING");
        periodicAt(2);
        assertEquals(RequestStatus.PENDING, ActivationService.get().load(k1).getStatus(), "still PENDING after another run");
    }

    /**
     * T-GAP-436 (LIMITATIONS 32): A1 damaged, its job renamed; the periodic work at T0+1 min runs while A1 is
     * still unreadable: nothing thrown, the file untouched; A1 written back; the work at T0+2 min ends it
     * INVALIDATED with a reason naming the rename.
     */
    @Test
    public void t_gap_436_stillUnreadableThenRestored() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("mi-s");
        String a1 = activate(job, "mi still");
        Path f = activationFile(a1);
        byte[] original = damage(f);
        byte[] broken = Files.readAllBytes(f);
        rename(job, "mi-s2");
        at(T0.plus(Duration.ofMinutes(1)));
        assertDoesNotThrow(() -> ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun(),
                "the periodic work survives a still-unreadable request");
        assertArrayEquals(broken, Files.readAllBytes(f), "the unreadable file is left untouched");
        Files.write(f, original);
        periodicAt(2);
        ActivationRequest req = ActivationService.get().load(a1);
        assertEquals(RequestStatus.INVALIDATED, req.getStatus(), "LIMITATIONS 32: INVALIDATED once readable");
        assertMentionsRename(req.getDecisionComment());
    }

    // ------------------------------------------------------------------ helpers

    private String activate(FreeStyleProject job, String reason) throws Exception {
        return as("r", () -> ActivationService.get().create(job, ActivationRequest.Action.ACTIVATE, reason, List.of("a1")).getId());
    }

    private Path activationFile(String id) {
        return store().resolve("activation-requests/" + id + ".xml");
    }

    private void rename(FreeStyleProject job, String to) throws Exception {
        as("admin", () -> {
            job.renameTo(to);
            return null;
        });
        assertNotNull(j.jenkins.getItemByFullName(to), "premise: renamed to " + to);
    }

    private static void periodicAt(int minutes) throws Exception {
        at(T0.plus(Duration.ofMinutes(minutes)));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
    }

    private static byte[] damage(Path file) throws Exception {
        assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): the request is stored at " + file);
        byte[] original = Files.readAllBytes(file);
        String xml = new String(original, StandardCharsets.UTF_8);
        String damaged = xml.substring(0, xml.length() / 2);
        assertThrows(RuntimeException.class, () -> Jenkins.XSTREAM2.fromXML(damaged), "premise: the damaged copy is not readable XML");
        Files.writeString(file, damaged, StandardCharsets.UTF_8);
        return original;
    }

    private static void assertMentionsRename(String comment) {
        String reason = String.valueOf(comment);
        System.out.println("T-GAP observation: reason: " + reason);
        assertTrue(reason.toLowerCase(Locale.ROOT).contains("renam"), "the reason names the rename: " + reason);
    }

    private Path store() {
        return j.jenkins.getRootDir().toPath().resolve("batch-control");
    }

    private static void at(Instant instant) {
        BatchClock.setForTest(Clock.fixed(instant, ZoneOffset.UTC));
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
