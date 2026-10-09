package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.get;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.activate;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug hunt A R2-03 (D-80, TEST-MATRIX note 303): the activation identity is a marker file kept in the job's
 * directory, not the directory's file key, so a copy of JENKINS_HOME keeps every activation (T-GAP-113,
 * {@link ActivationRestartGapTest}, inverted by D-80) while the protections of SPEC 6a security-13 stay:
 * a job deleted and re-created under the same name, a job made with Jenkins' copy (which copies only
 * {@code config.xml}) and a restored directory that lacks the marker all start not activated (fail closed).
 * Matrix rows T-06a-63 .. T-06a-65.
 *
 * <p>Basis: DECISIONS D-80 (a)..(d); SPEC 6a (unattended causes pass only for an activated job; "activation
 * state is truthful and fails closed: a job re-created under a deleted job's name ... starts not activated";
 * the job page shows the state); SPEC 6 (the blocking triple); ARCHITECTURE 5 (the marker file
 * {@code .batch-control-activation-id} in the job's directory, named on PR #46; this class uses the name only
 * to delete the file in T-06a-65, as the coordinator asked). The restarts are the clean ones
 * {@link JenkinsSessionExtension} performs; the directories are changed while Jenkins is stopped.
 *
 * <p>Written from docs/SPEC.md item 6a, docs/DECISIONS.md D-80 and docs/ARCHITECTURE.md section 5 only (no
 * src/main knowledge).
 */
public class ActivationHomeCopyRestartTest {

    /** ARCHITECTURE 5 (D-80): the marker file in the job's directory whose id the activation state stores. */
    static final String MARKER = ".batch-control-activation-id";

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    /**
     * T-06a-63 (P0, D-80 (b), SPEC 6a security-13): R is activated and its timer runs; while Jenkins is
     * stopped R's job directory is deleted (its stored activation state is left behind). After the restart
     * the administrator creates a new job R under the same name, with the timer unblocked: R is not activated,
     * its timer is refused (blocking triple) and its page says it is not activated.
     */
    @Test
    public void t_06a_63_jobDeletedOnDiskAndRecreatedStartsNotActivated() throws Throwable {
        AtomicReference<File> jobDir = new AtomicReference<>();
        session.then(r -> {
            secure(r);
            FreeStyleProject job = cleared(r.createFreeStyleProject("copy-recreated"));
            activate(job, "u1", "a1");
            r.assertBuildStatusSuccess(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
            jobDir.set(job.getRootDir());
        });
        deleteTree(jobDir.get().toPath());
        session.then(r -> {
            secure(r);
            assertNull(r.jenkins.getItemByFullName("copy-recreated"), "premise: the job is gone after the restart");
            FreeStyleProject job = cleared(r.createFreeStyleProject("copy-recreated"));
            assertFalse(isActivated(job), "D-80 (b): a job re-created under a deleted job's name starts not activated");
            assertTimerRefused(r, job);
            assertPageSaysNotActivated(r, job);
        });
    }

    /**
     * T-06a-64 (P0, D-80 (c)): S is activated and its timer runs; the administrator copies S to T with
     * Jenkins' copy (which copies only {@code config.xml}) and unblocks T's timer. T is not activated and its
     * timer is refused (blocking triple); S is still activated and its timer still runs.
     */
    @Test
    public void t_06a_64_jenkinsCopyOfAnActivatedJobStartsNotActivated() throws Throwable {
        session.then(r -> {
            secure(r);
            FreeStyleProject source = cleared(r.createFreeStyleProject("copy-source"));
            activate(source, "u1", "a1");
            r.assertBuildStatusSuccess(source.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));

            FreeStyleProject target = cleared((FreeStyleProject) r.jenkins.copy((hudson.model.TopLevelItem) source, "copy-target"));
            assertFalse(isActivated(target), "D-80 (c): a job made with Jenkins' copy starts not activated");
            assertTimerRefused(r, target);
            assertTrue(isActivated(source), "guard: the source stays activated");
            r.assertBuildStatusSuccess(source.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        });
    }

    /**
     * T-06a-65 (P0, D-80 (a) and (d)): M and G are activated and their timers run. While Jenkins is stopped
     * both job directories are replaced by copies of themselves, as a restore does; from M's copy the marker
     * file {@value #MARKER} is deleted. After the restart M is not activated (a missing marker fails closed):
     * its timer is refused (blocking triple) and its page says it is not activated. Guard: G, whose copy keeps
     * the marker, is activated and its timer runs.
     */
    @Test
    public void t_06a_65_restoredDirectoryWithoutTheMarkerStartsNotActivated() throws Throwable {
        AtomicReference<File> missingDir = new AtomicReference<>();
        AtomicReference<File> guardDir = new AtomicReference<>();
        session.then(r -> {
            secure(r);
            FreeStyleProject missing = cleared(r.createFreeStyleProject("copy-no-marker"));
            FreeStyleProject guard = cleared(r.createFreeStyleProject("copy-with-marker"));
            activate(missing, "u1", "a1");
            activate(guard, "u1", "a1");
            r.assertBuildStatusSuccess(missing.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
            r.assertBuildStatusSuccess(guard.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
            missingDir.set(missing.getRootDir());
            guardDir.set(guard.getRootDir());
        });
        replaceWithCopy(guardDir.get().toPath());
        Path missing = missingDir.get().toPath();
        replaceWithCopy(missing);
        Path marker = missing.resolve(MARKER);
        assertTrue(Files.isRegularFile(marker), "premise (D-80, ARCHITECTURE 5): the activated job keeps the marker " + marker
                + "; the directory holds " + list(missing));
        Files.delete(marker);
        session.then(r -> {
            secure(r);
            FreeStyleProject job = r.jenkins.getItemByFullName("copy-no-marker", FreeStyleProject.class);
            assertNotNull(job, "premise: the job loads from the restored directory");
            assertFalse(isActivated(job), "D-80 (d): a restored directory without the marker counts as not activated");
            assertTimerRefused(r, job);
            assertPageSaysNotActivated(r, job);

            FreeStyleProject guard = r.jenkins.getItemByFullName("copy-with-marker", FreeStyleProject.class);
            assertNotNull(guard, "premise: the guard job loads from its restored directory");
            assertTrue(isActivated(guard), "guard, D-80 (a): the restored directory with its marker keeps the activation");
            r.assertBuildStatusSuccess(guard.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        });
    }

    // ------------------------------------------------------------------ helpers

    private static void assertTimerRefused(JenkinsRule r, FreeStyleProject job) throws Exception {
        int next = job.getNextBuildNumber();
        int builds = job.getBuilds().size();
        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "the timer of " + job.getFullName() + " is refused");
        assertBlocked(r, job, next, builds);
    }

    private static void assertPageSaysNotActivated(JenkinsRule r, FreeStyleProject job) throws Exception {
        String lower = get(r, "u1", job.getUrl()).getContentAsString().toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("not activated") || lower.contains("on hold"), "the page of " + job.getFullName()
                + " says it is not activated");
    }

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

    /** Timer and upstream unblocked, so only the activation decides (D-46); installed and read back. */
    private static FreeStyleProject cleared(FreeStyleProject job) throws Exception {
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(job, property);
        return job;
    }

    /** Copies {@code dir} to a temporary directory, deletes it and copies it back: same content, a new directory. */
    private static void replaceWithCopy(Path dir) throws IOException {
        Path copy = Files.createTempDirectory("batch-control-home-copy").resolve("job");
        copyTree(dir, copy);
        deleteTree(dir);
        copyTree(copy, dir);
        deleteTree(copy);
        assertTrue(Files.isRegularFile(dir.resolve("config.xml")), "fixture: the restored directory holds the job configuration");
    }

    private static String list(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList()).toString();
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path p : paths.collect(Collectors.toList())) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isSymbolicLink(p)) {
                    Files.copy(p, target, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                } else if (Files.isDirectory(p)) {
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
}
