package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.activate;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.describe;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.records;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Fail-closed regressions. Matrix rows T-GAP-437 and T-GAP-438 (note 284).
 *
 * <p>Basis: LIMITATIONS 39 ("while the job's directory cannot be read, so that it cannot be confirmed as the same
 * one, the job counts as not activated and a warning naming it is logged"); ARCHITECTURE 5 ("A job whose directory
 * marker (identity) cannot be read counts as not activated"); LIMITATIONS 13 (a failed creation lock save is
 * retried every minute by the periodic work; the retry that succeeds is recorded as a CONFIGURE by SYSTEM).
 *
 * <p>Faults: T-GAP-437 makes the job directory unreadable with chmod 000 (skipped by assumption where it stays
 * readable, as under root). T-GAP-438 reuses the creation fault of {@link CreationSaveRetryGapTest} (its armed
 * item listener turns the new job's config.xml into a non-empty directory). The periodic work is run by calling
 * {@link ExpiryPeriodicWork#doRun()}; time moves only with {@link BatchClock}.
 *
 * <p>Written from docs/SPEC.md, docs/LIMITATIONS.md, docs/ARCHITECTURE.md and docs/TEST-MATRIX.md only (no
 * src/main knowledge).
 */
@WithJenkins
public class FailClosedGapTest {

    private JenkinsRule j;
    private Path chmodded;
    private Set<PosixFilePermission> chmoddedOriginal;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        CreationSaveRetryGapTest.BREAK.set(null);
        CreationSaveRetryGapTest.KEPT.set(null);
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    public void tearDown() throws Exception {
        CreationSaveRetryGapTest.BREAK.set(null);
        if (chmodded != null) {
            Files.setPosixFilePermissions(chmodded, chmoddedOriginal);
            chmodded = null;
        }
        BatchClock.reset();
    }

    /**
     * T-GAP-437 (LIMITATIONS 39, ARCHITECTURE 5, SPEC 6a fails closed): run control on; J (switches cleared, so only
     * activation can block it) is activated by an approved ACTIVATE and its timer runs. J's directory is made
     * unreadable (chmod 000 on its parent {@code jobs/}, so the directory cannot even be stat'ed): J counts as not activated, a warning naming J is logged and a timer run of J is
     * refused with nothing queued. With the permission restored J is activated again and its timer runs.
     */
    @Test
    public void t_gap_437_unreadableJobDirectoryCountsAsNotActivated() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        FreeStyleProject job = j.createFreeStyleProject("fc-j");
        BatchControlJobProperty open = new BatchControlJobProperty(true);
        open.setBlockTimer(false);
        open.setBlockUpstream(false);
        setBatchControl(job, open);
        activate(job, "u1", "a1");
        assertTrue(isActivated(job), "premise: J is activated by the approved ACTIVATE");
        assertNotNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "premise: J's timer runs while activated");
        j.waitUntilNoActivity();
        int buildsBefore = job.getBuilds().size();
        int nextBefore = job.getNextBuildNumber();
        assertEquals(1, buildsBefore, "premise: one timer build of J");

        // The job directory's identity is its file key (D-71b, ARCHITECTURE 5 "directory marker (identity)"); a stat of
        // the directory needs search permission on its parent, so the parent (JENKINS_HOME/jobs) is made unsearchable.
        Path dir = job.getRootDir().toPath().getParent();
        Path jobDir = job.getRootDir().toPath();
        Set<PosixFilePermission> original;
        try {
            original = Files.getPosixFilePermissions(dir);
        } catch (UnsupportedOperationException e) {
            original = null;
        }
        assumeTrue(original != null, "POSIX permissions are needed to make the job directory unreadable");
        List<LogRecord> warnings = new ArrayList<>();
        Handler handler = capture(warnings);
        Logger root = Logger.getLogger("");
        root.addHandler(handler);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("---------"));
            chmodded = dir;
            chmoddedOriginal = original;
            assumeTrue(!Files.isReadable(jobDir), "the job directory must really be unreadable (not when run as root)");

            assertFalse(isActivated(job), "LIMITATIONS 39: while J's directory cannot be read J counts as not activated");
            assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()),
                    "LIMITATIONS 39: a timer run of J is refused while its directory cannot be read");
            assertBlocked(j, job, nextBefore, buildsBefore);
            boolean named;
            synchronized (warnings) {
                named = warnings.stream().anyMatch(r -> text(r).contains("fc-j"));
            }
            assertTrue(named, "LIMITATIONS 39: a warning naming J is logged");
        } finally {
            root.removeHandler(handler);
            if (chmodded != null) {
                Files.setPosixFilePermissions(dir, original);
                chmodded = null;
            }
        }

        assertTrue(isActivated(job), "with J's directory readable again J is activated again");
        assertNotNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "J's timer runs again");
        j.waitUntilNoActivity();
        assertEquals(buildsBefore + 1, job.getBuilds().size(), "one more timer build of J after the permission is restored");
    }

    /**
     * T-GAP-438 (LIMITATIONS 13, ARCHITECTURE 4): run control on; the creation lock save of {@code fc-gone} fails
     * (the fault of T-GAP-430). Before any periodic retry the administrator deletes {@code fc-gone}. The periodic
     * work (at T0+2 min, and again at T0+11 min) throws nothing, does not re-create the job (no item and no
     * directory at the name) and writes no CONFIGURE record for it.
     */
    @Test
    public void t_gap_438_pendingLockRetryOfADeletedJobDoesNothing() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.ADMINISTER).everywhere().to("admin"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.save();
        String payload = "<?xml version='1.1' encoding='UTF-8'?><project><description>no lock in the payload</description>"
                + "<builders/><publishers/><buildWrappers/></project>";

        CreationSaveRetryGapTest.BREAK.set("fc-gone");
        int code = post("admin", "createItem", "name=fc-gone", payload);
        FreeStyleProject job = j.jenkins.getItemByFullName("fc-gone", FreeStyleProject.class);
        assertNotNull(job, "fixture: fc-gone exists after createItem (HTTP " + code + ")");
        assertNull(CreationSaveRetryGapTest.BREAK.get(), "fixture: the listener broke fc-gone's config.xml right after creation");
        File dir = job.getRootDir();
        assertTrue(job.getConfigFile().getFile().isDirectory(), "premise: fc-gone's lock save failed (config.xml unwritable)");
        int configureBefore = records(ChangeType.CONFIGURE, "fc-gone").size();
        // Core cannot delete a job whose config.xml is a non-empty directory, so the directory is emptied: it still
        // cannot be replaced by a save (so the retry stays pending whenever the Jenkins timer runs the periodic work),
        // but the deletion can remove it.
        Path brokenConfig = job.getConfigFile().getFile().toPath();
        Files.delete(brokenConfig.resolve("keep"));
        assertTrue(Files.isDirectory(brokenConfig), "fixture: fc-gone's config.xml is still an (empty) directory");

        int deleted = post("admin", "job/fc-gone/doDelete", null, null);
        assertNull(j.jenkins.getItemByFullName("fc-gone"), "fixture: the administrator deleted fc-gone (HTTP " + deleted + ")");
        assertFalse(dir.exists(), "LIMITATIONS 13: fc-gone's directory stays gone after the deletion (a pending lock retry,"
                + " including one the Jenkins timer runs while the deletion is under way, must not write it again): "
                + (dir.exists() ? Arrays.toString(dir.list()) : ""));

        for (long minutes : new long[] {2, 11}) {
            BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(minutes)), ZoneOffset.UTC));
            assertDoesNotThrow(() -> ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun(),
                    "the periodic work at T0+" + minutes + " min throws nothing for a deleted job's pending retry");
            assertNull(j.jenkins.getItemByFullName("fc-gone"), "the periodic retry does not re-create fc-gone (T0+" + minutes + " min)");
            assertFalse(dir.exists(), "the periodic retry writes no directory for fc-gone (T0+" + minutes + " min)");
            List<ChangeRecord> configure = records(ChangeType.CONFIGURE, "fc-gone");
            assertEquals(configureBefore, configure.size(),
                    "no CONFIGURE record is written for the deleted fc-gone (T0+" + minutes + " min): " + describe(configure));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Handler capture(List<LogRecord> sink) {
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel().intValue() >= Level.WARNING.intValue()) {
                    synchronized (sink) {
                        sink.add(r);
                    }
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        h.setLevel(Level.WARNING);
        return h;
    }

    private static String text(LogRecord r) {
        String msg = r.getMessage() == null ? "" : r.getMessage();
        if (r.getParameters() != null) {
            msg = msg + " " + Arrays.toString(r.getParameters());
        }
        return msg;
    }

    private int post(String user, String path, String query, String body) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).withRedirectEnabled(false).login(user);
        String url = wc.createCrumbedUrl(path).toExternalForm() + (query == null ? "" : "&" + query);
        WebRequest req = new WebRequest(new URL(url), HttpMethod.POST);
        if (body != null) {
            req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
            req.setRequestBody(body);
        }
        return wc.getPage(req).getWebResponse().getStatusCode();
    }
}
