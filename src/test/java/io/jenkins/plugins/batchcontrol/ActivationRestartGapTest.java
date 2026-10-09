package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
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
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.get;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.activate;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Coverage lane 1, scenario L1-04 (F), the restart cases: activation state fails closed after a
 * disk fault between two sessions. Matrix rows T-GAP-112 .. T-GAP-114 (note 276; T-GAP-113 inverted by
 * D-80, note 303).
 *
 * <p>Basis: SPEC 6a "activation state is truthful and fails closed: a job re-created under a
 * deleted job's name, a job whose state file could not be deleted ... starts not activated";
 * LIMITATIONS 39 "The state fails closed"; SPEC 6a "the job page shows whether the job is activated
 * or on hold"; SPEC 6 / #21 (a refused unattended submission writes TRIGGER_BLOCKED naming the
 * switch); SPEC 7 (D-21, a request whose job is gone does not run; SPEC 6 usability "why a request
 * was invalidated"). The restart is the clean one {@link JenkinsSessionExtension} performs; the
 * faults are made on disk while Jenkins is stopped. A chmod case is skipped (assumption) where the
 * file stays readable (root, Windows).
 *
 * <p>Written from docs/SPEC.md, docs/LIMITATIONS.md and docs/ARCHITECTURE.md section 5 only (no
 * src/main knowledge).
 */
public class ActivationRestartGapTest {

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    /**
     * T-GAP-112 (L1-04 case 1): J is activated; while Jenkins is stopped J's activation state file
     * is made unreadable (chmod 000). After the restart J's timer submission is refused (blocking
     * triple) and recorded as TRIGGER_BLOCKED naming activation, and J's page says it is not
     * activated. Guard (session 1): J's timer runs while the file is readable.
     */
    @Test
    public void t_gap_112_unreadableStateFileFailsClosedAfterRestart() throws Throwable {
        AtomicReference<Path> stateFile = new AtomicReference<>();
        session.then(r -> {
            secure(r);
            FreeStyleProject job = cleared(r.createFreeStyleProject("gap-unreadable"));
            activate(job, "u1", "a1");
            String found = null;
            for (String name : stateFiles(r)) {
                if (java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8).equals("gap-unreadable.xml")) {
                    found = name;
                }
            }
            assertNotNull(found, "fixture: the activation is stored as activations/gap-unreadable.xml (ARCHITECTURE 5): " + stateFiles(r));
            stateFile.set(activations(r).resolve(found));
            r.assertBuildStatusSuccess(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        });
        Path file = stateFile.get();
        PlatformFixtures.assumeCanMakeUnreadable();
        Set<PosixFilePermission> original = posix(file);
        assumeTrue(original != null, "POSIX permissions are needed to make the state file unreadable");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        try {
            assumeTrue(!Files.isReadable(file), "the state file must really be unreadable (not when run as root)");
            session.then(r -> {
                secure(r);
                FreeStyleProject job = r.jenkins.getItemByFullName("gap-unreadable", FreeStyleProject.class);
                assertNotNull(job, "the job survives the restart");
                assertFalse(isActivated(job), "an unreadable state file must fail closed: not activated");
                int next = job.getNextBuildNumber();
                int builds = job.getBuilds().size();
                assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "the timer is refused");
                assertBlocked(r, job, next, builds);
                List<ChangeRecord> blocked = recordsFor(ChangeType.TRIGGER_BLOCKED, "gap-unreadable");
                assertTrue(blocked.stream().anyMatch(rec -> rec.getDetail() != null
                                && rec.getDetail().contains("TIMER")
                                && rec.getDetail().toLowerCase(Locale.ROOT).contains("activation")),
                        "a TRIGGER_BLOCKED record naming TIMER and activation: "
                                + blocked.stream().map(ChangeRecord::getDetail).collect(Collectors.toList()));
                WebResponse page = get(r, "u1", job.getUrl());
                assertEquals(200, page.getStatusCode(), "the job page opens");
                String lower = page.getContentAsString().toLowerCase(Locale.ROOT);
                assertTrue(lower.contains("not activated") || lower.contains("on hold"),
                        "the job page says the job is not activated: " + excerpt(page.getContentAsString()));
            });
        } finally {
            Files.setPosixFilePermissions(file, original);
        }
    }

    /**
     * T-GAP-113 (L1-04 case 2; inverted by D-80, note 303): K is activated and its timer runs; while Jenkins
     * is stopped K's job directory is replaced by a copy of itself (copied to a temporary directory, the
     * original deleted, the copy copied back), so it is a new directory (a new file key where the platform has
     * one) with the same content, as a backup restore or a move of JENKINS_HOME to another volume leaves it.
     * After the restart K is still activated: its timer and an upstream run of the activated job U succeed,
     * and its page does not say "not activated". Before D-80 this row asserted the opposite, reading the
     * replaced directory as a job re-created outside Jenkins; the owner ruled (D-80) that the activation
     * identity is the marker kept in the job's directory, so a copy that keeps the marker keeps the
     * activation. The copy without the marker is T-06a-65.
     */
    @Test
    public void t_gap_113_replacedJobDirectoryKeepsItsActivation() throws Throwable {
        AtomicReference<File> jobDir = new AtomicReference<>();
        session.then(r -> {
            secure(r);
            FreeStyleProject job = cleared(r.createFreeStyleProject("gap-replaced"));
            FreeStyleProject up = cleared(r.createFreeStyleProject("gap-replaced-up"));
            activate(job, "u1", "a1");
            activate(up, "u1", "a1");
            r.assertBuildStatusSuccess(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
            jobDir.set(job.getRootDir());
        });
        Path dir = jobDir.get().toPath();
        Object keyBefore = fileKey(dir);
        Path copy = Files.createTempDirectory("gap-replaced-copy").resolve("job");
        copyTree(dir, copy);
        // Built beside the old directory (both exist at once) so the new directory cannot reuse the freed inode.
        Path replacement = dir.resolveSibling(dir.getFileName() + ".replacement");
        copyTree(copy, replacement);
        deleteTree(dir);
        Files.move(replacement, dir);
        deleteTree(copy);
        assertTrue(Files.isRegularFile(dir.resolve("config.xml")), "fixture: the replaced directory holds the job configuration");
        Object keyAfter = fileKey(dir);
        if (keyBefore != null && keyAfter != null) {
            assertFalse(keyBefore.equals(keyAfter), "premise: the replaced directory is a new directory (new file key)");
        }
        session.then(r -> {
            secure(r);
            FreeStyleProject job = r.jenkins.getItemByFullName("gap-replaced", FreeStyleProject.class);
            assertNotNull(job, "the job loads from the replaced directory");
            assertTrue(isActivated(job), "D-80 (a): a job whose directory was replaced by a copy of itself (marker included) stays activated");
            r.assertBuildStatusSuccess(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
            FreeStyleProject up = r.jenkins.getItemByFullName("gap-replaced-up", FreeStyleProject.class);
            assertNotNull(up, "premise: U survived the restart");
            hudson.model.FreeStyleBuild upstream = r.assertBuildStatusSuccess(up.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
            r.assertBuildStatusSuccess(job.scheduleBuild2(0, new hudson.model.Cause.UpstreamCause(upstream)));
            String lower = get(r, "u1", job.getUrl()).getContentAsString().toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("not activated"), "D-80 (a): the restored job's page does not say it is not activated");
        });
    }

    /**
     * T-GAP-114 (L1-04 case 3; SPEC 7 D-21 and SPEC 6 usability): M has a pending ACTIVATE request;
     * while Jenkins is stopped M's job directory is deleted. After the restart nothing breaks, and
     * either startup already ended the request as INVALIDATED, or approving it is refused with a
     * plain message that the job no longer exists and the request is then INVALIDATED. Either way
     * no activation exists for M.
     */
    @Test
    public void t_gap_114_pendingRequestOfAJobDeletedOnDiskIsInvalidated() throws Throwable {
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<File> jobDir = new AtomicReference<>();
        session.then(r -> {
            secure(r);
            FreeStyleProject job = cleared(r.createFreeStyleProject("gap-vanished"));
            id.set(submitActivationOk(r, "u1", job, "ACTIVATE", "go live", "a1"));
            jobDir.set(job.getRootDir());
        });
        deleteTree(jobDir.get().toPath());
        session.then(r -> {
            secure(r);
            assertNull(r.jenkins.getItemByFullName("gap-vanished"), "premise: the job is gone after the restart");
            RequestStatus status = ActivationService.get().load(id.get()).getStatus();
            if (status == RequestStatus.PENDING) {
                WebResponse approve = decideActivation(r, "a1", id.get(), "approve", "ok");
                assertTrue(approve.getStatusCode() >= 400 && approve.getStatusCode() < 500,
                        "approving the request of a job that no longer exists must be refused, got HTTP "
                                + approve.getStatusCode() + ": " + excerpt(approve.getContentAsString()));
                UsabilityFixtures.assertPlainRefusal("the refused approval", approve.getContentAsString(),
                        Pattern.compile("(?i)no longer exist|does not exist|not found|deleted|missing"));
                status = ActivationService.get().load(id.get()).getStatus();
            }
            assertEquals(RequestStatus.INVALIDATED, status, "the request of a job deleted on disk ends INVALIDATED");
            assertTrue(recordsFor(ChangeType.ACTIVATED, "gap-vanished").isEmpty(), "no activation was recorded");
            assertEquals(200, get(r, "a1", "batch-control/activations/").getStatusCode(), "the activations page still opens");
        });
    }

    // ------------------------------------------------------------------ helpers

    /** A persistable security setup and run control with approver a1 (re-applied in every session). */
    private static void secure(JenkinsRule r) throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    private static FreeStyleProject cleared(FreeStyleProject job) throws Exception {
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(job, property);
        return job;
    }

    private static Path activations(JenkinsRule r) {
        return r.jenkins.getRootDir().toPath().resolve("batch-control").resolve("activations");
    }

    private static Set<String> stateFiles(JenkinsRule r) throws IOException {
        Set<String> out = new TreeSet<>();
        if (Files.isDirectory(activations(r))) {
            try (Stream<Path> files = Files.list(activations(r))) {
                files.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".xml")).forEach(out::add);
            }
        }
        return out;
    }

    private static Object fileKey(Path dir) throws IOException {
        return Files.readAttributes(dir, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
    }

    private static Set<PosixFilePermission> posix(Path file) {
        try {
            return Files.getPosixFilePermissions(file);
        } catch (UnsupportedOperationException | IOException e) {
            return null;
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path p : paths.collect(Collectors.toList())) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(p);
            }
        }
        assertFalse(Files.exists(dir), "fixture: " + dir + " must be gone");
    }

    @SuppressWarnings("unused")
    private static ACLContext as(String userId) {
        return ACL.as2(BatchControlFixtures.token(userId));
    }
}
