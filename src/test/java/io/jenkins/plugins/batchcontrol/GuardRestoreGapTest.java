package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Extension;
import hudson.XmlFile;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Saveable;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.SaveableListener;
import hudson.security.AuthorizationMatrixProperty;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.has;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Coverage lane 2, scenario L2-05 (matrix rows T-GAP-208 .. T-GAP-214, note 277): the guard and the
 * creation lock when their own write fails.
 *
 * <p>Basis: SPEC item 2 "the change is reverted and recorded as GRANT_VIOLATION" and "a payload's
 * authorization property is removed and recorded as GRANT_VIOLATION" (D-35b, D-35c, D-58);
 * docs/reports/security-05 S-06, whose fix direction is to "record a {@code GRANT_VIOLATION} with a
 * 'restore failed' detail"; DECISIONS D-35d (4) (the baseline is the last recorded snapshot; a stale
 * or damaged baseline never brings an entry back); SPEC item 8 D-31/D-34 (every newly created job
 * starts with {@code approvalRequired=true}); ARCHITECTURE section 1 (records are always written,
 * recording is independent of control).
 *
 * <p>Fault injection (scenario file, Part B rule 6): a save listener and an item listener of this
 * class run before all others ({@code @Extension(ordinal = 100000)}); each is inert until armed for one
 * item's full name, fires once, and then replaces that item's {@code config.xml} with a non-empty
 * directory, so the next write of the item fails (root-safe). Every test restores the file in
 * {@code finally}. Other store faults use a corrupt or unreadable snapshot file and a directory in
 * place of the month's change file ({@code changes/2026-09.jsonl}; the clock is fixed in September
 * 2026).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md, docs/ARCHITECTURE.md, docs/reports/security-05.md
 * and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class GuardRestoreGapTest {

    /** The full name of the item whose next save the save listener breaks, or null. */
    static final AtomicReference<String> BREAK_ON_SAVE = new AtomicReference<>();
    /** The full name of the item whose creation (or copy) the item listener breaks, or null. */
    static final AtomicReference<String> BREAK_ON_CREATE = new AtomicReference<>();

    private static final Pattern FAILED = Pattern.compile("(?i)fail|could not|couldn't|unable|cannot|error");
    private static final String CLOSE = "</hudson.security.AuthorizationMatrixProperty>";

    private JenkinsRule j;
    private BatchControlMatrixAuthorizationStrategy strategy;
    private final List<File> broken = new ArrayList<>();
    private final List<Path> restoreAfter = new ArrayList<>();

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        strategy = StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy());
        j.jenkins.setAuthorizationStrategy(strategy);
        StrategyFixtures.changeControlOn();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    public void tearDown() throws Exception {
        BREAK_ON_SAVE.set(null);
        BREAK_ON_CREATE.set(null);
        for (File config : broken) {
            if (config.isDirectory()) {
                deleteTree(config.toPath());
            }
        }
        for (Path p : restoreAfter) {
            if (Files.isDirectory(p)) {
                deleteTree(p);
            }
            if (Files.exists(p)) {
                p.toFile().setReadable(true, false);
            }
        }
        BatchClock.reset();
    }

    /** Breaks the next save of an armed item: its config.xml becomes a non-empty directory. */
    @Extension(ordinal = 100000)
    public static class BreakNextSave extends SaveableListener {
        @Override
        public void onChange(Saveable o, XmlFile file) {
            String target = BREAK_ON_SAVE.get();
            if (target != null && o instanceof Item && target.equals(((Item) o).getFullName())
                    && BREAK_ON_SAVE.compareAndSet(target, null)) {
                breakFile(file.getFile());
            }
        }
    }

    /** Breaks the first save after an armed item's creation or copy. */
    @Extension(ordinal = 100000)
    public static class BreakAfterCreate extends ItemListener {
        @Override
        public void onCreated(Item item) {
            breakCreated(item);
        }

        @Override
        public void onCopied(Item src, Item item) {
            breakCreated(item);
        }

        private static void breakCreated(Item item) {
            String target = BREAK_ON_CREATE.get();
            if (target != null && target.equals(item.getFullName()) && BREAK_ON_CREATE.compareAndSet(target, null)) {
                breakFile(new File(item.getRootDir(), "config.xml"));
            }
        }
    }

    private static void breakFile(File config) {
        try {
            Files.deleteIfExists(config.toPath());
            Files.createDirectories(config.toPath());
            Files.writeString(config.toPath().resolve("keep"), "a file is expected here", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("fixture: cannot break " + config, e);
        }
    }

    // ------------------------------------------------------------------ L2-05 guard restore failures

    /**
     * T-GAP-208 (L2-05, SPEC 2 D-35b, security-05 S-06): bob (grant-only Configure on {@code gj})
     * adds an authorization entry for himself through {@code config.xml}; the listener breaks the
     * save that would restore the property. A GRANT_VIOLATION names bob and {@code gj} and says that
     * removing the added entries failed and that an administrator must check the item. Guard: the same
     * change without the fault writes a GRANT_VIOLATION that reports no failure.
     */
    @Test
    public void t_gap_208_failedRestoreOfAGrantHoldersEntryIsRecorded() throws Exception {
        FreeStyleProject guardJob = jobWithAlice("gj-ok");
        FreeStyleProject job = jobWithAlice("gj");
        StrategyFixtures.grant("bob", "gj-ok", Arrays.asList(GrantAction.CONFIGURE));
        StrategyFixtures.grant("bob", "gj", Arrays.asList(GrantAction.CONFIGURE));

        int before = violations().size();
        postConfigXml("bob", guardJob, withEntry(guardJob, "hudson.model.Item.Configure", "bob"));
        ChangeRecord normal = lastViolation(before, "gj-ok");
        assertFalse(FAILED.matcher(String.valueOf(normal.getDetail())).find(),
                "guard: a successful revert must not report a failure: " + normal.getDetail());

        int beforeFault = violations().size();
        String xml = withEntry(job, "hudson.model.Item.Configure", "bob");
        arm(BREAK_ON_SAVE, job);
        postConfigXml("bob", job, xml);
        assertEquals(null, BREAK_ON_SAVE.get(), "fixture: the listener must have broken the save");

        ChangeRecord record = lastViolation(beforeFault, "gj");
        assertEquals("bob", record.getUser(), "the GRANT_VIOLATION names bob");
        assertRestoreFailed(record, "removing the added entries");
    }

    /**
     * T-GAP-209 (L2-05, SPEC 2 D-58 line): job {@code kj} is guarded by bob's CONFIGURE window; carol
     * (native Configure through the job's own property) widens it with an entry for alice2; the
     * listener breaks the save that would undo it. A GRANT_VIOLATION names carol and {@code kj} and
     * says that undoing the widening failed. Guard: without the fault the record reports no failure.
     */
    @Test
    public void t_gap_209_failedUndoOfAWideningIsRecorded() throws Exception {
        FreeStyleProject guardJob = jobWithAliceAndCarol("kj-ok");
        FreeStyleProject job = jobWithAliceAndCarol("kj");
        StrategyFixtures.grant("bob", "kj-ok", Arrays.asList(GrantAction.CONFIGURE));
        StrategyFixtures.grant("bob", "kj", Arrays.asList(GrantAction.CONFIGURE));

        int before = violations().size();
        postConfigXml("carol", guardJob, withEntry(guardJob, "hudson.model.Item.Build", "alice2"));
        ChangeRecord normal = lastViolation(before, "kj-ok");
        assertFalse(FAILED.matcher(String.valueOf(normal.getDetail())).find(),
                "guard: a successful undo must not report a failure: " + normal.getDetail());

        int beforeFault = violations().size();
        String xml = withEntry(job, "hudson.model.Item.Build", "alice2");
        arm(BREAK_ON_SAVE, job);
        postConfigXml("carol", job, xml);
        assertEquals(null, BREAK_ON_SAVE.get(), "fixture: the listener must have broken the save");

        ChangeRecord record = lastViolation(beforeFault, "kj");
        assertEquals("carol", record.getUser(), "the GRANT_VIOLATION names carol");
        assertRestoreFailed(record, "undoing the widening");
    }

    /**
     * T-GAP-210 (L2-05, SPEC 2 D-58 "authorization entries on an item created inside a guarded
     * folder"): folder {@code g} is guarded by bob's CONFIGURE window; carol (native Create in {@code g},
     * native Configure on the source) copies job {@code src} (alice's entry) into {@code g}; the
     * listener breaks the first save after the copy. A GRANT_VIOLATION names {@code g/cp} and says that
     * removing its authorization entries failed. Guard: the same copy without the fault records a
     * GRANT_VIOLATION that reports no failure.
     */
    @Test
    public void t_gap_210_failedRemovalAfterACopyIntoAGuardedFolderIsRecorded() throws Exception {
        FreeStyleProject src = j.createFreeStyleProject("src");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        amp.add(Item.CONFIGURE, PermissionEntry.user("carol"));
        src.addProperty(amp);
        Folder g = folderWithCreateFor("g", "carol");
        StrategyFixtures.grant("bob", "g", Arrays.asList(GrantAction.CONFIGURE));

        int before = violations().size();
        createItem("carol", g, "name=cp-ok&mode=copy&from=/src", null);
        assertNotNull(g.getItem("cp-ok"), "guard fixture: carol's copy must exist");
        ChangeRecord normal = lastViolation(before, "g/cp-ok");
        assertFalse(FAILED.matcher(String.valueOf(normal.getDetail())).find(),
                "guard: a successful removal must not report a failure: " + normal.getDetail());

        int beforeFault = violations().size();
        BREAK_ON_CREATE.set("g/cp");
        createItem("carol", g, "name=cp&mode=copy&from=/src", null);
        assertEquals(null, BREAK_ON_CREATE.get(), "fixture: the listener must have broken the first save of g/cp");
        broken.add(new File(g.getItem("cp").getRootDir(), "config.xml"));

        ChangeRecord record = lastViolation(beforeFault, "g/cp");
        assertRestoreFailed(record, "removing the copied item's entries");
    }

    /**
     * T-GAP-211 (L2-05, SPEC 2 "a payload's authorization property is removed and recorded as
     * GRANT_VIOLATION", security-05 S-06 direction; scenario note: expected to fail): bob holds a CREATE
     * window on folder {@code p} and POSTs {@code createItem} with a config.xml carrying an
     * authorization property; the listener breaks the first save after the creation. The creation is
     * answered without a crash page, the item exists, and a GRANT_VIOLATION names bob and {@code p/new}
     * and records that removing the property failed. Guard: without the fault the record reports no
     * failure.
     */
    @Test
    public void t_gap_211_failedRemovalOfACreationPayloadPropertyIsRecorded() throws Exception {
        Folder p = j.jenkins.createProject(Folder.class, "p");
        StrategyFixtures.grant("bob", "p", Arrays.asList(GrantAction.CREATE));
        String payload = "<?xml version='1.1' encoding='UTF-8'?><project><properties><hudson.security.AuthorizationMatrixProperty>"
                + "<inheritanceStrategy class=\"org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy\"/>"
                + "<permission>USER:hudson.model.Item.Configure:bob</permission>" + CLOSE
                + "</properties><builders/><publishers/><buildWrappers/></project>";

        int before = violations().size();
        createItem("bob", p, "name=new-ok", payload);
        assertNotNull(p.getItem("new-ok"), "guard fixture: the creation must exist");
        ChangeRecord normal = lastViolation(before, "p/new-ok");
        assertFalse(FAILED.matcher(String.valueOf(normal.getDetail())).find(),
                "guard: a successful removal must not report a failure: " + normal.getDetail());

        int beforeFault = violations().size();
        BREAK_ON_CREATE.set("p/new");
        Page answer = createItem("bob", p, "name=new", payload);
        assertEquals(null, BREAK_ON_CREATE.get(), "fixture: the listener must have broken the first save of p/new");
        Item created = p.getItem("new");
        assertNotNull(created, "the creation must have happened");
        broken.add(new File(created.getRootDir(), "config.xml"));
        UsabilityFixtures.assertPlainRefusal("creation whose payload removal failed", UsabilityFixtures.text(answer), null);

        List<ChangeRecord> after = newer(violations(), beforeFault);
        assertFalse(after.isEmpty(), "SPEC 2: the payload's authorization property must be recorded as GRANT_VIOLATION"
                + " even when removing it could not be saved; no record was written");
        ChangeRecord record = after.get(after.size() - 1);
        assertEquals("bob", record.getUser(), "the GRANT_VIOLATION names bob");
        assertTrue(String.valueOf(record.getTarget()).contains("p/new"), "the GRANT_VIOLATION names p/new: " + describe(record));
        assertRestoreFailed(record, "removing the payload's property");
    }

    // ------------------------------------------------------------------ damaged baseline and store

    /**
     * T-GAP-212 (L2-05, D-35d (4)): the stored snapshot of job {@code sj} ({@code snapshots/sj.xml},
     * premise: it exists) is overwritten with corrupt XML; bob (grant-only Configure) adds an entry for
     * himself. His entry is still removed and a GRANT_VIOLATION is recorded. The same with job
     * {@code sj2} whose snapshot is made unreadable (chmod 000; skipped where that does not take effect,
     * e.g. as root).
     */
    @Test
    public void t_gap_212_damagedSnapshotDoesNotSwitchTheProtectionOff() throws Exception {
        FreeStyleProject job = jobWithAlice("sj");
        FreeStyleProject job2 = jobWithAlice("sj2");
        StrategyFixtures.grant("bob", "sj", Arrays.asList(GrantAction.CONFIGURE));
        StrategyFixtures.grant("bob", "sj2", Arrays.asList(GrantAction.CONFIGURE));

        Path snapshot = store().resolve("snapshots/sj.xml");
        assertTrue(Files.isRegularFile(snapshot), "premise (ARCHITECTURE 5): the snapshot of sj is stored at " + snapshot);
        Files.writeString(snapshot, "<<< not xml", StandardCharsets.UTF_8);
        int before = violations().size();
        postConfigXml("bob", job, withEntry(job, "hudson.model.Item.Configure", "bob"));
        assertFalse(jobMentions("sj", "bob"), "with a corrupt snapshot bob's entry must still be removed");
        assertTrue(violations().size() > before, "with a corrupt snapshot the revert must still be recorded");

        Path snapshot2 = store().resolve("snapshots/sj2.xml");
        assertTrue(Files.isRegularFile(snapshot2), "premise: the snapshot of sj2 is stored at " + snapshot2);
        PlatformFixtures.assumeCanMakeUnreadable();
        Files.setPosixFilePermissions(snapshot2, PosixFilePermissions.fromString("---------"));
        restoreAfter.add(snapshot2);
        assumeTrue(!Files.isReadable(snapshot2), "chmod 000 did not make the snapshot unreadable (root?): skipped");
        int before2 = violations().size();
        postConfigXml("bob", job2, withEntry(job2, "hudson.model.Item.Configure", "bob"));
        assertFalse(jobMentions("sj2", "bob"), "with an unreadable snapshot bob's entry must still be removed");
        assertTrue(violations().size() > before2, "with an unreadable snapshot the revert must still be recorded");
    }

    /**
     * T-GAP-213 (L2-05, ARCHITECTURE 1): the month's change file {@code changes/2026-09.jsonl}
     * (premise: it exists) is replaced by a non-empty directory, so no record can be written; bob
     * (grant-only Configure on {@code cj}) adds an entry for himself. The entry is still removed (in
     * memory and on disk). Guard: the change file existed and is a directory during the save.
     */
    @Test
    public void t_gap_213_unwritableChangeLogDoesNotUndoTheRevert() throws Exception {
        FreeStyleProject job = jobWithAlice("cj");
        StrategyFixtures.grant("bob", "cj", Arrays.asList(GrantAction.CONFIGURE));
        Path month = store().resolve("changes/" + YearMonth.from(T0.atZone(ZoneOffset.UTC)) + ".jsonl");
        assertTrue(Files.isRegularFile(month), "premise (ARCHITECTURE 5): the month's change file is " + month);
        String xml = withEntry(job, "hudson.model.Item.Configure", "bob");
        Files.delete(month);
        Files.createDirectories(month);
        Files.writeString(month.resolve("keep"), "x", StandardCharsets.UTF_8);
        restoreAfter.add(month);

        postConfigXml("bob", job, xml);
        assertTrue(Files.isDirectory(month), "premise: the change file was not writable during the save");
        assertFalse(jobMentions("cj", "bob"), "the revert must stand although its record cannot be written");
        assertFalse(j.jenkins.getItemByFullName("cj", FreeStyleProject.class).getConfigFile().asString().contains(":bob</permission>"),
                "the revert must be on disk");
        assertTrue(has(j.jenkins.getItemByFullName("cj"), "alice", Item.CONFIGURE), "guard: alice's entry stays");
    }

    // ------------------------------------------------------------------ creation lock

    /**
     * T-GAP-214 (L2-05, SPEC 8 D-31/D-34): run control on; the administrator creates {@code locked-new}
     * through the New Item form while the listener breaks the first save after its creation. The
     * creation succeeds without a crash page, and u1's manual build of the new job is refused (queue
     * empty, next build number unchanged, no build). Guard: a job created before run control was
     * switched on (no Batch Control property) is built by u1 normally.
     */
    @Test
    public void t_gap_214_failedLockSaveStillLeavesTheNewJobLocked() throws Exception {
        FreeStyleProject before = j.createFreeStyleProject("pre-existing");
        strategy.add(Item.BUILD, PermissionEntry.user("u1"));
        strategy.add(Jenkins.READ, PermissionEntry.user("u1"));
        strategy.add(Item.READ, PermissionEntry.user("u1"));
        strategy.add(io.jenkins.plugins.batchcontrol.security.BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.save();
        BatchControlFixtures.uncontrolled(before);

        BREAK_ON_CREATE.set("locked-new");
        JenkinsRule.WebClient admin = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        WebRequest create = new WebRequest(admin.createCrumbedUrl("createItem"), HttpMethod.POST);
        create.setRequestParameters(Arrays.asList(new NameValuePair("name", "locked-new"),
                new NameValuePair("mode", FreeStyleProject.class.getName())));
        Page answer = admin.getPage(create);
        assertEquals(null, BREAK_ON_CREATE.get(), "fixture: the listener must have broken the first save of locked-new");
        FreeStyleProject created = j.jenkins.getItemByFullName("locked-new", FreeStyleProject.class);
        assertNotNull(created, "the creation must succeed");
        broken.add(new File(created.getRootDir(), "config.xml"));
        UsabilityFixtures.assertPlainRefusal("creation whose first save failed", UsabilityFixtures.text(answer), null);
        assertTrue(answer.getWebResponse().getStatusCode() < 500, "the creator must see no error page, got "
                + answer.getWebResponse().getStatusCode());

        int next = created.getNextBuildNumber();
        PluginInteractionFixtures.post(j, "u1", created.getUrl() + "build?delay=0sec");
        PluginInteractionFixtures.assertBlocked(j, created, next, 0);
        BatchControlJobProperty property = created.getProperty(BatchControlJobProperty.class);
        assertTrue(property != null && property.isApprovalRequired(), "D-31: the new job must require approval");

        PluginInteractionFixtures.post(j, "u1", before.getUrl() + "build?delay=0sec");
        j.waitUntilNoActivity();
        assertEquals(1, before.getBuilds().size(), "guard: u1 builds a job that is not under run control");
    }

    // ---------------------------------------------------------------- helpers

    private Path store() {
        return j.jenkins.getRootDir().toPath().resolve("batch-control");
    }

    private void arm(AtomicReference<String> flag, Item item) {
        flag.set(item.getFullName());
        broken.add(new File(item.getRootDir(), "config.xml"));
    }

    private FreeStyleProject jobWithAlice(String name) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject(name);
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        p.addProperty(amp);
        assertFalse(has(p, "bob", Item.CONFIGURE), "premise: bob holds no native Configure on " + name);
        return p;
    }

    private FreeStyleProject jobWithAliceAndCarol(String name) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject(name);
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        amp.add(Item.CONFIGURE, PermissionEntry.user("carol"));
        p.addProperty(amp);
        assertTrue(has(p, "carol", Item.CONFIGURE), "premise: carol configures " + name + " natively");
        return p;
    }

    private Folder folderWithCreateFor(String name, String user) throws Exception {
        Folder f = j.jenkins.createProject(Folder.class, name);
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty fp =
                new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(new HashMap<Permission, Set<String>>());
        fp.add(Item.CREATE, PermissionEntry.user(user));
        f.addProperty(fp);
        assertTrue(has(f, user, Item.CREATE), "premise: " + user + " creates in " + name + " natively");
        return f;
    }

    private static String withEntry(FreeStyleProject job, String permissionId, String sid) throws Exception {
        String xml = job.getConfigFile().asString();
        assertTrue(xml.contains(CLOSE), "fixture: the job must carry an authorization property: " + xml);
        return xml.replace(CLOSE, "<permission>USER:" + permissionId + ":" + sid + "</permission>" + CLOSE);
    }

    private boolean jobMentions(String name, String sid) {
        AuthorizationMatrixProperty amp = j.jenkins.getItemByFullName(name, FreeStyleProject.class)
                .getProperty(AuthorizationMatrixProperty.class);
        return amp != null && amp.getGrantedPermissionEntries().values().stream().flatMap(Set::stream)
                .anyMatch(e -> sid.equals(e.getSid()));
    }

    private int postConfigXml(String user, FreeStyleProject job, String xml) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        WebRequest req = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        req.setRequestBody(xml);
        return wc.getPage(req).getWebResponse().getStatusCode();
    }

    private Page createItem(String user, Folder container, String query, String body) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        URL url = new URL(wc.createCrumbedUrl(container.getUrl() + "createItem").toExternalForm() + "&" + query);
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        if (body != null) {
            req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
            req.setRequestBody(body);
        }
        return wc.getPage(req);
    }

    private static List<ChangeRecord> violations() {
        return StrategyFixtures.records(ChangeType.GRANT_VIOLATION);
    }

    private static List<ChangeRecord> newer(List<ChangeRecord> all, int before) {
        return new ArrayList<>(all.subList(Math.min(before, all.size()), all.size()));
    }

    /** The last GRANT_VIOLATION written after {@code before} records that names {@code target}. */
    private static ChangeRecord lastViolation(int before, String target) {
        List<ChangeRecord> after = newer(violations(), before);
        ChangeRecord found = null;
        for (ChangeRecord r : after) {
            if (String.valueOf(r.getTarget()).equals(target) || String.valueOf(r.getDetail()).contains(target)) {
                found = r;
            }
        }
        assertNotNull(found, "a GRANT_VIOLATION naming " + target + " must be written; new records: " + describe(after));
        return found;
    }

    private static void assertRestoreFailed(ChangeRecord record, String what) {
        String detail = String.valueOf(record.getDetail());
        assertTrue(FAILED.matcher(detail).find(), "the GRANT_VIOLATION must say that " + what + " failed: " + describe(record));
        assertTrue(detail.toLowerCase(Locale.ROOT).contains("administrator"),
                "the GRANT_VIOLATION must say that an administrator must check the item: " + describe(record));
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static String describe(ChangeRecord r) {
        return r.getType() + " user=" + r.getUser() + " target=" + r.getTarget() + " detail=" + r.getDetail();
    }

    private static String describe(List<ChangeRecord> records) {
        List<String> out = new ArrayList<>();
        records.forEach(r -> out.add(describe(r)));
        return out.toString();
    }
}
