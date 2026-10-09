package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.ExtensionList;
import hudson.XmlFile;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Items;
import hudson.model.Saveable;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.SaveableListener;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import hudson.Extension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.describe;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.records;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saves made during an item's creation that fail at first and are retried by the per-minute periodic work.
 * Matrix rows T-GAP-430 and T-GAP-431 (note 282).
 *
 * <p>Basis: LIMITATIONS 13 ("The lock holds even when it cannot be saved ... A save that fails, on an unwritable
 * job directory for example, is logged as SEVERE and retried every minute by the periodic work; the retry that
 * succeeds is recorded as a CONFIGURE by SYSTEM"); LIMITATIONS 11, CREATE bullet ("Copying a folder that contains
 * items stops part-way ... Batch Control saves that folder as it is in memory, without the copied authorization
 * property ... If such a removal cannot be saved, see item 35") and LIMITATIONS 35 ("When the entries are already
 * gone from the item in memory, they no longer apply and the save is retried every minute"); SPEC 8 (D-31, D-34);
 * ARCHITECTURE 4 (the per-minute periodic work).
 *
 * <p>The fault: an item listener with the highest ordinal turns the new item's {@code config.xml} into a non-empty
 * directory as soon as the item is created or copied, keeping the content core wrote. Every later save of that item
 * fails until the test "repairs" it by writing that content back as a file, which is what a failed save leaves on
 * disk. The periodic work is run by calling {@link ExpiryPeriodicWork#doRun()}; time is moved with the plugin clock
 * ({@link BatchClock}), never by sleeping.
 *
 * <p>Written from docs/SPEC.md, docs/LIMITATIONS.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class CreationSaveRetryGapTest {

    /** The full name of the item whose config.xml the listener breaks right after its creation, or null. */
    static final AtomicReference<String> BREAK = new AtomicReference<>();
    /** The config.xml content at the moment it was broken. */
    static final AtomicReference<String> KEPT = new AtomicReference<>();
    /** The full name of the item whose config.xml the save listener breaks right after its first save, or null. */
    static final AtomicReference<String> BREAK_SAVE = new AtomicReference<>();
    /** The item directory made read-only by the save listener, or null. */
    static final AtomicReference<File> READ_ONLY = new AtomicReference<>();

    private static final String FOLDER_PROPERTY = "com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty";

    private JenkinsRule j;

    @Extension(ordinal = 100000) // first among the item listeners; inert unless armed
    public static final class BreakOnCreate extends ItemListener {
        @Override
        public void onCreated(Item item) {
            breakIt(item);
        }

        @Override
        public void onCopied(Item src, Item item) {
            breakIt(item);
        }

        private static void breakIt(Item item) {
            String target = BREAK.get();
            if (target != null && target.equals(item.getFullName()) && BREAK.compareAndSet(target, null)) {
                Path config = new File(item.getRootDir(), "config.xml").toPath();
                try {
                    KEPT.set(Files.readString(config, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new AssertionError("fixture: cannot read " + config, e);
                }
                makeUnwritable(config);
            }
        }
    }

    /**
     * Right after an armed item's first save, writes {@link #KEPT} (preset by the test: the content a failed first
     * save would have left) back into its config.xml and makes the item's directory read-only, so that every later
     * save fails while the file stays readable (LIMITATIONS 13 and 35 name "an unwritable item directory"). Used
     * where the item gets no item event: a copy that stops part-way fires no onCopied.
     */
    @Extension(ordinal = 100000) // inert unless armed
    public static final class ReadOnlyAfterFirstSave extends SaveableListener {
        @Override
        public void onChange(Saveable o, XmlFile file) {
            String target = BREAK_SAVE.get();
            if (target != null && o instanceof Item && target.equals(((Item) o).getFullName()) && BREAK_SAVE.compareAndSet(target, null)) {
                File config = file.getFile();
                try {
                    Files.writeString(config.toPath(), KEPT.get(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new AssertionError("fixture: cannot write " + config, e);
                }
                File dir = config.getParentFile();
                READ_ONLY.set(dir);
                if (!dir.setWritable(false, false)) {
                    throw new AssertionError("fixture: cannot make " + dir + " read-only");
                }
            }
        }
    }

    private static void makeUnwritable(Path config) {
        try {
            Files.delete(config);
            Files.createDirectories(config);
            Files.writeString(config.resolve("keep"), "a file is expected here", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("fixture: cannot break " + config, e);
        }
    }



    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        BREAK.set(null);
        BREAK_SAVE.set(null);
        KEPT.set(null);
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    public void tearDown() {
        BREAK.set(null);
        BREAK_SAVE.set(null);
        File dir = READ_ONLY.getAndSet(null);
        if (dir != null) {
            dir.setWritable(true, false);
        }
        BatchClock.reset();
    }

    /**
     * T-GAP-430 (LIMITATIONS 13; SPEC 8 D-31/D-34; ARCHITECTURE 4): run control on (change control off). Guard: the
     * administrator creates {@code lock-ok} over {@code createItem} with a config.xml carrying no Batch Control
     * property; its config.xml on disk carries the lock. Then {@code lock-j} is created the same way while the
     * listener breaks its config.xml right after creation. In memory the job is locked at once; one periodic run
     * while the directory is still unwritable changes nothing on disk; after the directory is repaired (the content
     * core wrote, without the lock, written back) the next periodic run persists the lock (approvalRequired,
     * blockTimer and blockUpstream on in the config.xml on disk) and writes a CONFIGURE record by SYSTEM for
     * {@code lock-j}.
     */
    @Test
    public void t_gap_430_failedCreationLockSaveIsRetriedByThePeriodicWork() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.ADMINISTER).everywhere().to("admin"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.save();
        String payload = "<?xml version='1.1' encoding='UTF-8'?><project><description>no lock in the payload</description>"
                + "<builders/><publishers/><buildWrappers/></project>";

        assertTrue(createItem("admin", "", "name=lock-ok", payload) < 400, "guard fixture: lock-ok is created");
        FreeStyleProject ok = j.jenkins.getItemByFullName("lock-ok", FreeStyleProject.class);
        assertNotNull(ok, "guard fixture: lock-ok exists");
        assertLocked(onDisk(ok), "guard (LIMITATIONS 13): without a fault the lock of lock-ok is on disk at once");

        BREAK.set("lock-j");
        int code = createItem("admin", "", "name=lock-j", payload);
        FreeStyleProject job = j.jenkins.getItemByFullName("lock-j", FreeStyleProject.class);
        assertNotNull(job, "fixture: lock-j exists after createItem (HTTP " + code + ")");
        assertEquals(null, BREAK.get(), "fixture: the listener broke lock-j's config.xml right after its creation");
        File config = job.getConfigFile().getFile();
        assertTrue(config.isDirectory(), "premise: lock-j's config.xml is still unwritable");
        String kept = KEPT.get();
        assertFalse(kept.contains("BatchControlJobProperty"), "premise: the content core wrote carries no lock: " + kept);
        assertLocked(job.getProperty(BatchControlJobProperty.class),
                "LIMITATIONS 13: the lock holds in memory even when it cannot be saved");

        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
        assertTrue(config.isDirectory(), "fixture: a periodic run while the directory is unwritable cannot write it");
        int before = records(ChangeType.CONFIGURE, "lock-j").size();

        repair(config, kept);
        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(2)), ZoneOffset.UTC));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();

        assertTrue(config.isFile(), "fixture: lock-j's config.xml is a file again");
        assertLocked(onDisk(job), "LIMITATIONS 13: the periodic work retries the failed save and the lock is persisted: "
                + Files.readString(config.toPath(), StandardCharsets.UTF_8));
        List<ChangeRecord> configure = records(ChangeType.CONFIGURE, "lock-j");
        assertEquals(before + 1, configure.size(),
                "LIMITATIONS 13: the retry that succeeds is recorded as one CONFIGURE: " + describe(configure));
        assertEquals("SYSTEM", String.valueOf(configure.get(configure.size() - 1).getUser()).toUpperCase(Locale.ROOT),
                "LIMITATIONS 13: the retry's CONFIGURE is by SYSTEM: " + describe(configure));
    }

    /**
     * T-GAP-431 (LIMITATIONS 11 CREATE bullet, LIMITATIONS 35; SPEC 2 D-35c): change control on, Batch Control
     * matrix strategy; folder {@code src-f} carries a folder authorization property (alice and bob Item/Configure)
     * and contains job {@code src-f/j}; bob holds only a CREATE window on folder {@code dest}. With the save listener
     * armed on {@code dest/copy-f} (right after core's first save of the copy, its config.xml is set back to the
     * source's config.xml, which core copies verbatim, and its directory is made read-only: a failed first save), bob copies {@code src-f} into {@code dest} over {@code createItem} (premise: the
     * child copy is refused and core leaves {@code dest/copy-f} in place). In memory the left folder carries no
     * entry of the source; its directory stays read-only through the end of the request and one periodic run.
     * After the directory is writable again (its config.xml still holds the copied content with the source's
     * entries) and the
     * periodic work runs again (and, at the latest, once more ten minutes later on the plugin clock), the config.xml
     * on disk carries no authorization entry for alice or bob.
     */
    @Test
    public void t_gap_431_failedCleanupOfAnAbortedFolderCopyIsRetried() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
        StrategyFixtures.changeControlOn();
        Folder src = j.jenkins.createProject(Folder.class, "src-f");
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty fp =
                new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(new HashMap<Permission, Set<String>>());
        fp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        fp.add(Item.CONFIGURE, PermissionEntry.user("bob"));
        src.addProperty(fp);
        src.createProject(FreeStyleProject.class, "j");
        j.jenkins.createProject(Folder.class, "dest");
        StrategyFixtures.grant("bob", "dest", Arrays.asList(GrantAction.CREATE));

        String copied = src.getConfigFile().asString();
        assertTrue(copied.contains(":alice</permission>"), "fixture: the source's config.xml carries alice's entry: " + copied);
        KEPT.set(copied); // core copies the source's config.xml verbatim before saving the copy
        PlatformFixtures.assumeCanMakeUnwritable();
        BREAK_SAVE.set("dest/copy-f");
        Page answer = createItemPage("bob", "job/dest/", "name=copy-f&mode=copy&from=/src-f", null);
        String outcome = "HTTP " + answer.getWebResponse().getStatusCode();
        Folder copy = j.jenkins.getItemByFullName("dest/copy-f", Folder.class);
        assertNotNull(copy, "premise (LIMITATIONS 11): core leaves the copied folder in place (" + outcome + ")");
        assertEquals(null, BREAK_SAVE.get(), "fixture: the listener broke dest/copy-f's config.xml after its first save");
        assertEquals(null, j.jenkins.getItemByFullName("dest/copy-f/j"), "premise (LIMITATIONS 11): the child copy is refused");
        File config = copy.getConfigFile().getFile();
        File dir = READ_ONLY.get();
        assertTrue(dir != null && !dir.canWrite(), "premise: the left folder's directory is still read-only after the request");
        assertTrue(Files.readString(config.toPath(), StandardCharsets.UTF_8).contains(":alice</permission>"),
                "premise: the failed saves left the copied content, with the source's entries, on disk");
        assertFalse(mentions(copy, "alice") || mentions(copy, "bob"),
                "LIMITATIONS 11/35: the left folder carries no entry of the source in memory");

        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
        assertTrue(Files.readString(config.toPath(), StandardCharsets.UTF_8).contains(":alice</permission>"),
                "fixture: a periodic run while the directory is read-only cannot write it");

        assertTrue(dir.setWritable(true, false), "fixture: the directory is writable again");
        READ_ONLY.set(null);
        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(2)), ZoneOffset.UTC));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
        String disk = Files.readString(config.toPath(), StandardCharsets.UTF_8);
        if (disk.contains(":alice</permission>")) {
            System.out.println("T-GAP-431 observation: not yet saved two minutes later; moving the plugin clock past ten minutes");
            BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(11)), ZoneOffset.UTC));
            ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
            disk = Files.readString(config.toPath(), StandardCharsets.UTF_8);
        }
        assertFalse(disk.contains(":alice</permission>") || disk.contains(":bob</permission>"),
                "LIMITATIONS 11/35: the retried save leaves no copied authorization entry on disk: " + disk);
        assertFalse(mentions(copy, "alice") || mentions(copy, "bob"), "the folder in memory still carries no entry of the source");
    }

    // ------------------------------------------------------------------ helpers

    private static void assertLocked(BatchControlJobProperty p, String message) {
        assertNotNull(p, message + " (no Batch Control job property)");
        assertTrue(p.isApprovalRequired() && p.isBlockTimer() && p.isBlockUpstream(), message);
    }

    /** The Batch Control job property of the job as read back from its config.xml on disk. */
    private static BatchControlJobProperty onDisk(FreeStyleProject job) throws IOException {
        FreeStyleProject read = (FreeStyleProject) new XmlFile(Items.XSTREAM2, job.getConfigFile().getFile()).read();
        return read.getProperty(BatchControlJobProperty.class);
    }

    private static boolean mentions(Folder f, String sid) {
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty fp =
                f.getProperties().get(com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class);
        return fp != null && fp.getGrantedPermissionEntries().values().stream()
                .flatMap(Set::stream).anyMatch(e -> sid.equals(e.getSid()));
    }

    /** Puts back what a failed save leaves on disk: the previous content, as a writable file. */
    private static void repair(File config, String content) throws IOException {
        try (Stream<Path> walk = Files.walk(config.toPath())) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
        Files.writeString(config.toPath(), content, StandardCharsets.UTF_8);
    }

    private int createItem(String user, String containerUrl, String query, String body) throws Exception {
        return createItemPage(user, containerUrl, query, body).getWebResponse().getStatusCode();
    }

    private Page createItemPage(String user, String containerUrl, String query, String body) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        URL url = new URL(wc.createCrumbedUrl(containerUrl + "createItem").toExternalForm() + "&" + query);
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        if (body != null) {
            req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
            req.setRequestBody(body);
        }
        return wc.getPage(req);
    }
}
