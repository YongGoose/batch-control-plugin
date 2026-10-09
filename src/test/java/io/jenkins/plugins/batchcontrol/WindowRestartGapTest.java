package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.TopLevelItem;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 3, scenarios L3-01, L3-04, L3-20 (restart half) and L3-21: store files that cannot be
 * read when Jenkins starts, ends found again at startup per reason, created-item records across restarts,
 * and a GRANT_REVOKE record that cannot be appended. Matrix rows T-GAP-301 .. T-GAP-304, T-GAP-314 ..
 * T-GAP-316, T-GAP-374, T-GAP-377, T-GAP-378 and T-GAP-387 (note 279).
 *
 * <p>Basis: SPEC 4 "컨트롤러 재시작 후에도 대기 중인 요청과 유효한 권한 부여가 유지된다"; SPEC 8 line 157 (a restart
 * keeps an unexpired window) and line 170; SPEC 6 usability (no stack trace or "Oops!" page from our own
 * code); SPEC 3 Grant {@code revokedBy}, {@code revokedReason} and D-63; LIMITATIONS 11 ("A window therefore
 * either applies to its item ... or has ended"; "Ending a window is built to survive a failed write ...
 * Either write succeeding is enough for the end to survive the restart"; "At startup, a grant file that
 * still says the window is open but has a GRANT_REVOKE record is ended again from that record"; "If the
 * change log cannot be read back that far ... every window still open ends: it is revoked by SYSTEM with
 * the reason 'its state could not be confirmed at startup'"; the created-item record bullet); ARCHITECTURE
 * 4 ("The startup re-end reads the GRANT_REVOKE records since the oldest open window was granted, bounded
 * by time ...; if they cannot all be read, every open window ends"); D-74, D-75 (2) ("the restart re-end
 * fails closed"; per the owner's ruling a damaged line inside the startup read ends every open window, and
 * LIMITATIONS 11's "counts as a record that was never written" is to be corrected, note 279).
 *
 * <p>Fault injection between sessions: chmod (skipped where this process can still read or write), a
 * non-empty directory where the change month file is expected, a hand-edited change month file, a grant
 * file copied while its window was open and copied back after it ended ("open again on disk": Jenkins
 * stopped between the GRANT_REVOKE record and the grant file write, LIMITATIONS 11), and an item directory
 * removed while Jenkins is down. Restored in {@code finally}.
 *
 * <p>Batch Control matrix strategy, change control on (run control too where run requests are used); u1 ..
 * u4 hold RequestGrant; r holds BatchControl/Request; a1 approves.
 *
 * <p>Written from docs/SPEC.md items 3, 4, 6 and 8, docs/DECISIONS.md D-63, D-74 and D-75,
 * docs/LIMITATIONS.md item 11 and docs/ARCHITECTURE.md sections 4 and 5 only (no src/main knowledge).
 */
public class WindowRestartGapTest {

    static final String UNCONFIRMED_REASON = "its state could not be confirmed at startup";
    static final String FOLLOW_REASON = "it could not follow its item";

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    private Path home;
    private final Map<String, String> ids = new HashMap<>();
    private final Map<String, String> reasons = new HashMap<>();
    private final Map<String, String> revokers = new HashMap<>();
    private final List<Path> restore = new ArrayList<>();

    // ------------------------------------------------------------------ L3-01

    /**
     * T-GAP-301 (L3-01 part 1; SPEC 4, SPEC 9, SPEC 8 line 170): session 1: u1's CONFIGURE window on job
     * {@code a}, jobs {@code b} and {@code c}, r's pending run requests R and R2 on the approval-required job
     * {@code J}. Between the sessions the grant file of u1's window and R's file {@code requests/run/<R>.xml}
     * are made unreadable. Session 2 starts; as SYSTEM, job {@code n} is created, {@code b} renamed to
     * {@code b2} and {@code c} deleted: each change happens and is recorded (CREATE, RENAME, DELETE). Guard,
     * session 3 after the permissions are restored: u1 configures {@code a} (the window was never ended) and R
     * is listed again, PENDING.
     */
    @Test
    public void t_gap_301_unreadableStoreFilesDoNotStopItemChangesAfterARestart() throws Throwable {
        unreadableStoreSession1();
        unreadableBetweenSessions();
        try {
            session.then(r -> {
                try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                    r.jenkins.createProject(FreeStyleProject.class, "n");
                    ((FreeStyleProject) r.jenkins.getItemByFullName("b")).renameTo("b2");
                    r.jenkins.getItemByFullName("c").delete();
                }
                assertNotNull(r.jenkins.getItemByFullName("n"), "the job n is created");
                assertNotNull(r.jenkins.getItemByFullName("b2"), "b is renamed to b2");
                assertNull(r.jenkins.getItemByFullName("c"), "c is deleted");
                assertTrue(recordsNaming(ChangeType.CREATE, "n"), "SPEC 9: a CREATE record names n");
                assertTrue(recordsNaming(ChangeType.RENAME, "b2"), "SPEC 9: a RENAME record names b2: "
                        + WindowStateFixtures.describe(ApproverFormFixtures.records(ChangeType.RENAME)));
                assertTrue(recordsNaming(ChangeType.DELETE, "c"), "SPEC 9: a DELETE record names c");
            });
        } finally {
            restorePermissions();
        }
        session.then(r -> {
            assertTrue(can("u1", r.jenkins.getItemByFullName("a"), Item.CONFIGURE), "guard (SPEC 4): with the file readable again the window"
                    + " was never ended and confers on a");
            RunRequest request = RunRequestService.get().load(ids.get("R"));
            assertNotNull(request, "guard (SPEC 4): R loads again");
            assertEquals(RequestStatus.PENDING, request.getStatus(), "guard: R is still PENDING");
            assertTrue(RunRequestService.get().list().stream().anyMatch(q -> ids.get("R").equals(q.getId())), "guard: R is listed again");
        });
    }

    /**
     * T-GAP-302 (L3-01 part 2; LIMITATIONS 11 fail closed, SPEC 6 usability; expected red at head, finding
     * F-3): with the same fault as T-GAP-301 in session 2, u1's {@code GET job/a/} answers 200 and
     * {@code GET job/a/configure} answers 403 (the window cannot be confirmed, so it confers nothing), not a
     * server error.
     */
    @Test
    public void t_gap_302_unreadableWindowFileConfersNothingButBreaksNoPage() throws Throwable {
        unreadableStoreSession1();
        unreadableBetweenSessions();
        try {
            session.then(r -> {
                assertEquals(200, ApproverFormFixtures.get(r, "u1", "job/a/").getStatusCode(), "u1's job page of a answers 200");
                WebResponse configure = ApproverFormFixtures.get(r, "u1", "job/a/configure");
                assertEquals(403, configure.getStatusCode(), "a window whose file cannot be read confers nothing, so job/a/configure answers"
                        + " 403, not a server error: " + excerpt(configure.getContentAsString()));
            });
        } finally {
            restorePermissions();
        }
    }

    /**
     * T-GAP-303 (L3-01 part 2; SPEC 6 usability; expected red at head, finding F-3): with the same fault as
     * T-GAP-301 in session 2, the administrator's {@code GET /} answers 200.
     */
    @Test
    public void t_gap_303_unreadableStoreFilesLeaveTheDashboardUsable() throws Throwable {
        unreadableStoreSession1();
        unreadableBetweenSessions();
        try {
            session.then(r -> {
                WebResponse root = ApproverFormFixtures.get(r, "admin", "");
                assertEquals(200, root.getStatusCode(), "the administrator's dashboard answers 200: " + excerpt(root.getContentAsString()));
            });
        } finally {
            restorePermissions();
        }
    }

    /**
     * T-GAP-304 (L3-01 part 2; SPEC 4, SPEC 2 line 51, SPEC 6 usability; expected red at head, finding F-3):
     * with the same fault as T-GAP-301 in session 2, r's {@code batch-control/requests/<R2>/} renders (200,
     * naming R2's reason), and {@code batch-control/requests/<R>/} answers 404, not a server error.
     */
    @Test
    public void t_gap_304_unreadableRequestFileHidesOnlyThatRequest() throws Throwable {
        unreadableStoreSession1();
        unreadableBetweenSessions();
        try {
            session.then(r -> {
                WebResponse r2 = ApproverFormFixtures.get(r, "r", "batch-control/requests/" + ids.get("R2") + "/");
                assertEquals(200, r2.getStatusCode(), "R2's page renders for r: " + excerpt(r2.getContentAsString()));
                assertTrue(r2.getContentAsString().contains("second request"), "R2's page shows R2");
                WebResponse unreadable = ApproverFormFixtures.get(r, "r", "batch-control/requests/" + ids.get("R") + "/");
                assertEquals(404, unreadable.getStatusCode(), "the request whose file cannot be read answers 404, not a server error: "
                        + excerpt(unreadable.getContentAsString()));
            });
        } finally {
            restorePermissions();
        }
    }

    // ------------------------------------------------------------------ L3-04

    /**
     * T-GAP-314 (L3-04; LIMITATIONS 11, ARCHITECTURE 4, D-63, D-75 (2)): session 1: u1's 120-minute
     * windows A ({@code ea}), B ({@code eb}) and C ({@code ec}) open, each grant file copied while open;
     * the administrator revokes A; C ends because it cannot follow its item (the grants directory refuses
     * writes while the administrator renames {@code ec} to {@code ec2}; writes are allowed again and the
     * periodic work runs); the administrator turns change control off (B ends) and on again; u1's window D
     * ({@code ed}) opens and is copied. Session 2 starts with every change month file unreadable: D ends at
     * startup. The stored reason and revoker of each window right after its end are kept. Jenkins stops;
     * the open copies of A .. D are copied back. Session 3: none of A .. D is active, and each stored window
     * reads back with the reason and revoker it had right after its original end (A: none, the
     * administrator; B: the switch-off reason; C: "it could not follow its item"; D: "its state could not be
     * confirmed at startup", SYSTEM). Guard: u1's window G ({@code eg}) opened in session 3 is still active
     * and confers after another restart.
     */
    @Test
    public void t_gap_314_endsFoundAgainAtStartupKeepTheirOriginalReasons() throws Throwable {
        Map<String, String> open = new LinkedHashMap<>();
        session.then(r -> {
            prepare(r, false);
            for (String name : new String[] {"ea", "eb", "ec", "ed", "eg"}) {
                r.jenkins.createProject(FreeStyleProject.class, name);
            }
            for (String w : new String[] {"A:ea", "B:eb", "C:ec"}) {
                String[] p = w.split(":");
                ids.put(p[0], window("u1", p[1], 120));
                open.put(p[0], Files.readString(grantFile(ids.get(p[0])), StandardCharsets.UTF_8));
            }
            try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
                GrantService.get().revoke(ids.get("A"));
            }
            remember("A");
            Path grants = home.resolve("batch-control/grants");
            try {
                PlatformFixtures.assumeCanMakeUnwritable();
                assertTrue(grants.toFile().setWritable(false, false), "fixture: grants/ made read-only");
                grantFile(ids.get("C")).toFile().setWritable(false, false);
                Assumptions.assumeTrue(writesRefused(grants), "the file system does not refuse writes to a read-only directory for this process");
                try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
                    ((FreeStyleProject) r.jenkins.getItemByFullName("ec")).renameTo("ec2");
                }
            } finally {
                grants.toFile().setWritable(true, false);
                grantFile(ids.get("C")).toFile().setWritable(true, false);
            }
            ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
            assertNull(WindowStateFixtures.active(ids.get("C")), "premise (D-75 (2)): C could not follow its item and ended");
            remember("C");
            assertTrue(reasons.get("C").contains(FOLLOW_REASON), "premise: C's stored reason says it could not follow: " + reasons.get("C"));
            BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
            try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
                cfg.setChangeControlEnabled(false);
                cfg.save();
                cfg.setChangeControlEnabled(true);
                cfg.save();
            }
            assertNull(WindowStateFixtures.active(ids.get("B")), "premise (LIMITATIONS 34): B ended when change control was turned off");
            remember("B");
            assertNotNull(reasons.get("B"), "premise (D-63): B's stored reason names the switch");
            ids.put("D", window("u1", "ed", 120));
            open.put("D", Files.readString(grantFile(ids.get("D")), StandardCharsets.UTF_8));
        });
        List<Path> months = monthFiles();
        try {
            PlatformFixtures.assumeCanMakeUnreadable();
            for (Path month : months) {
                assertTrue(month.toFile().setReadable(false, false), "fixture: " + month + " made unreadable");
            }
            Assumptions.assumeTrue(months.stream().noneMatch(Files::isReadable), "the file system does not refuse reads for this process");
            session.then(r -> {
                assertNull(WindowStateFixtures.active(ids.get("D")), "premise (ARCHITECTURE 4): D ended at a start with an unreadable change log");
                remember("D");
                assertTrue(reasons.get("D").contains(UNCONFIRMED_REASON), "premise: D's stored reason: " + reasons.get("D"));
            });
        } finally {
            for (Path month : months) {
                month.toFile().setReadable(true, false);
            }
        }
        for (Map.Entry<String, String> e : open.entrySet()) {
            Files.writeString(grantFile(ids.get(e.getKey())), e.getValue(), StandardCharsets.UTF_8);
        }
        session.then(r -> {
            for (String w : new String[] {"A", "B", "C", "D"}) {
                assertNull(WindowStateFixtures.active(ids.get(w)), "LIMITATIONS 11: window " + w + ", open again on disk, is ended again at startup");
                Grant stored = stored(ids.get(w));
                assertNotNull(stored.getRevokedAt(), "window " + w + " is stored as revoked");
                assertEquals(reasons.get(w), lower(stored.getRevokedReason()), "LIMITATIONS 11 (ended again from that record), D-63: window " + w
                        + " keeps the reason it had right after its original end");
                assertEquals(revokers.get(w), stored.getRevokedBy(), "window " + w + " keeps the account that ended it");
            }
            ids.put("G", window("u1", "eg", 120));
        });
        session.then(r -> {
            assertNotNull(WindowStateFixtures.active(ids.get("G")), "guard (SPEC 8 line 157): G, opened after every end, is active after a restart");
            assertTrue(can("u1", r.jenkins.getItemByFullName("eg"), Item.CONFIGURE), "guard: G confers on eg");
        });
    }

    /**
     * T-GAP-315 (L3-04; LIMITATIONS 11, ARCHITECTURE 4): session 1: u1's window A on {@code da} (copied while
     * open) and window K on {@code dk}; the administrator revokes A. Jenkins stops; A's open copy is copied
     * back and A's GRANT_REVOKE line is duplicated in the change month file (same content, a fresh id).
     * Session 2 starts (the restart completes: K is active and confers), A is not active, and the stored A
     * is revoked by the administrator, as its record says, and A is ended once: the records naming A are the
     * two lines on disk plus at most one written by the start (the count is printed).
     */
    @Test
    public void t_gap_315_duplicatedRevokeLineEndsTheWindowOnce() throws Throwable {
        String[] openCopy = new String[1];
        session.then(r -> {
            prepare(r, false);
            r.jenkins.createProject(FreeStyleProject.class, "da");
            r.jenkins.createProject(FreeStyleProject.class, "dk");
            ids.put("A", window("u1", "da", 120));
            ids.put("K", window("u1", "dk", 120));
            openCopy[0] = Files.readString(grantFile(ids.get("A")), StandardCharsets.UTF_8);
            try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
                GrantService.get().revoke(ids.get("A"));
            }
        });
        Files.writeString(grantFile(ids.get("A")), openCopy[0], StandardCharsets.UTF_8);
        Path month = newestMonth();
        String revoke = Files.readAllLines(month, StandardCharsets.UTF_8).stream()
                .filter(l -> l.contains("GRANT_REVOKE") && l.contains(ids.get("A"))).findFirst()
                .orElseThrow(() -> new AssertionError("premise: the change log holds A's GRANT_REVOKE record"));
        Matcher id = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(revoke);
        assertTrue(id.find(), "premise: the record line carries its id");
        Files.writeString(month, revoke.replace(id.group(1), UUID.randomUUID().toString()) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        session.then(r -> {
            assertNotNull(WindowStateFixtures.active(ids.get("K")), "the restart completes: K is active");
            assertTrue(can("u1", r.jenkins.getItemByFullName("dk"), Item.CONFIGURE), "K confers on dk");
            assertNull(WindowStateFixtures.active(ids.get("A")), "LIMITATIONS 11: A is ended again from its record");
            assertFalse(can("u1", r.jenkins.getItemByFullName("da"), Item.CONFIGURE), "A confers nothing");
            Grant stored = stored(ids.get("A"));
            assertNotNull(stored.getRevokedAt(), "A is stored as revoked");
            assertEquals("admin", stored.getRevokedBy(), "A is revoked by the administrator, as its record says");
            long records = ApproverFormFixtures.records(ChangeType.GRANT_REVOKE).stream()
                    .filter(rec -> WindowStateFixtures.identifies(rec, ids.get("A"), "da")).count();
            System.out.println("T-GAP-315 observation: GRANT_REVOKE records naming A after the start: " + records);
            assertTrue(records >= 2 && records <= 3, "L3-04 'A ends once': the two lines on disk name A, and the start ends A at most once more"
                    + " (at most one further GRANT_REVOKE record), got " + records);
        });
    }

    /**
     * T-GAP-316 (L3-04 torn-line part; D-75 (2) and ARCHITECTURE 4 govern: a damaged line inside the
     * startup read ends every open window; LIMITATIONS 11's "counts as a record that was never written" is
     * superseded, note 279): session 1: u1's windows E ({@code te}) and F ({@code tf}, copied while open);
     * the administrator revokes F. Jenkins stops; F's open copy is copied back and a damaged line (a
     * GRANT_REVOKE record cut off inside the user field, followed by a line end) is appended to the change
     * month file (the file as it stands once a torn line has been closed with a line end). Session 2: E is
     * not active, confers nothing and is stored as revoked by SYSTEM with the reason "its state could not be
     * confirmed at startup"; F is not active, confers nothing and is stored with its recorded end (revoked by
     * the administrator, no reason: its GRANT_REVOKE record lies before the damaged line, and LIMITATIONS 11
     * says such a window "is ended again from that record"). u1 opens window H ({@code th}). Guard, session 3
     * (plain restart): H, granted after the damaged line, is still active and confers (the damaged line is
     * read back by one start only, ARCHITECTURE 4's time bound).
     */
    @Test
    public void t_gap_316_damagedLineInsideTheStartupReadEndsEveryOpenWindow() throws Throwable {
        String[] openCopy = new String[1];
        session.then(r -> {
            prepare(r, false);
            for (String name : new String[] {"te", "tf", "th"}) {
                r.jenkins.createProject(FreeStyleProject.class, name);
            }
            ids.put("E", window("u1", "te", 120));
            ids.put("F", window("u1", "tf", 120));
            openCopy[0] = Files.readString(grantFile(ids.get("F")), StandardCharsets.UTF_8);
            try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
                GrantService.get().revoke(ids.get("F"));
            }
        });
        Files.writeString(grantFile(ids.get("F")), openCopy[0], StandardCharsets.UTF_8);
        Path month = newestMonth();
        assertTrue(Files.readString(month, StandardCharsets.UTF_8).endsWith("\n"), "premise: the month file ends with a line end");
        Files.writeString(month, "{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"GRANT_REVOKE\",\"target\":\"te\",\"user\":\"adm\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        session.then(r -> {
            assertNull(WindowStateFixtures.active(ids.get("E")), "D-75 (2): E ends at a start whose read meets a damaged line");
            assertFalse(can("u1", r.jenkins.getItemByFullName("te"), Item.CONFIGURE), "E confers nothing");
            Grant e = stored(ids.get("E"));
            assertEquals(ACL.SYSTEM_USERNAME, e.getRevokedBy(), "ARCHITECTURE 4: E is revoked by SYSTEM");
            assertTrue(lower(e.getRevokedReason()).contains(UNCONFIRMED_REASON), "E's stored reason: " + e.getRevokedReason());
            assertNull(WindowStateFixtures.active(ids.get("F")), "F, open again on disk, is ended at startup");
            assertFalse(can("u1", r.jenkins.getItemByFullName("tf"), Item.CONFIGURE), "F confers nothing");
            Grant f = stored(ids.get("F"));
            assertNotNull(f.getRevokedAt(), "F is stored as revoked");
            System.out.println("T-GAP-316 observation: F revoked by " + f.getRevokedBy() + " with reason '" + f.getRevokedReason() + "'");
            assertEquals("admin", f.getRevokedBy(), "LIMITATIONS 11 / ARCHITECTURE 4: F's GRANT_REVOKE record lies before the damaged line and"
                    + " is readable, so F \"is ended again from that record\": revoked by the administrator, as recorded (reason '"
                    + f.getRevokedReason() + "')");
            assertNull(f.getRevokedReason(), "F keeps its recorded end: the administrator's revoke gave no reason");
            ids.put("H", window("u1", "th", 120));
        });
        session.then(r -> {
            assertNotNull(WindowStateFixtures.active(ids.get("H")), "guard (SPEC 8 line 157, ARCHITECTURE 4): H, granted after the damaged line,"
                    + " is still active after a plain restart; last lines: " + tail(newestMonth(), 3));
            assertTrue(can("u1", r.jenkins.getItemByFullName("th"), Item.CONFIGURE), "guard: H confers on th");
        });
    }

    // ------------------------------------------------------------------ L3-20 (restart half)

    /**
     * T-GAP-374 (L3-20; D-35c, SPEC 4, LIMITATIONS 11 "At startup a record whose name no item has is
     * dropped"): session 1: u1's CREATE windows on folders {@code cf} and {@code cv}; u1 creates
     * {@code cf/x} and {@code cv/x} through them (premise: u1 configures both). Between the sessions
     * {@code cv/x}'s directory is removed. Session 2: u1 still configures {@code cf/x}; {@code cv/x} is gone,
     * and after the administrator creates a new {@code cv/x}, u1 holds no Configure on it.
     */
    @Test
    public void t_gap_374_createdItemRecordsSurviveARestartAndDropAVanishedName() throws Throwable {
        session.then(r -> {
            prepare(r, false);
            r.jenkins.createProject(Folder.class, "cf");
            r.jenkins.createProject(Folder.class, "cv");
            window("u1", "cf", 120, GrantAction.CREATE);
            window("u1", "cv", 120, GrantAction.CREATE);
            createAs(r, "u1", "cf", "x");
            createAs(r, "u1", "cv", "x");
            assertTrue(can("u1", r.jenkins.getItemByFullName("cf/x"), Item.CONFIGURE), "premise (D-35c): u1 configures cf/x");
            assertTrue(can("u1", r.jenkins.getItemByFullName("cv/x"), Item.CONFIGURE), "premise (D-35c): u1 configures cv/x");
        });
        deleteTree(home.resolve("jobs/cv/jobs/x"));
        session.then(r -> {
            assertTrue(can("u1", r.jenkins.getItemByFullName("cf/x"), Item.CONFIGURE), "SPEC 4, D-35c: u1 still configures cf/x after the restart");
            assertNull(r.jenkins.getItemByFullName("cv/x"), "premise: cv/x vanished while Jenkins was down");
            try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
                ((Folder) r.jenkins.getItemByFullName("cv")).createProject(FreeStyleProject.class, "x");
            }
            assertFalse(can("u1", r.jenkins.getItemByFullName("cv/x"), Item.CONFIGURE),
                    "LIMITATIONS 11: the record of the vanished cv/x is dropped, the new cv/x gives u1 nothing");
        });
    }

    // ------------------------------------------------------------------ L3-21

    /**
     * T-GAP-377 (L3-21; LIMITATIONS 11 "marked ended in memory first ... Either write succeeding is enough
     * for the end to survive the restart"): session 1: u1's window W on {@code rw}; the current change month
     * file is replaced by a non-empty directory; the administrator revokes W: W is not active at once and its
     * stored file says it was revoked. The month file is restored. Session 2: W is still ended and confers
     * nothing.
     */
    @Test
    public void t_gap_377_revokeSurvivesARestartWhenOnlyTheGrantFileWasWritten() throws Throwable {
        session.then(r -> {
            prepare(r, false);
            r.jenkins.createProject(FreeStyleProject.class, "rw");
            ids.put("W", window("u1", "rw", 120));
            Path month = newestMonth();
            Path aside = month.resolveSibling(month.getFileName() + ".aside");
            Files.move(month, aside);
            try {
                Files.createDirectories(month);
                Files.writeString(month.resolve("blocker.txt"), "a non-empty directory where the month file was", StandardCharsets.UTF_8);
                try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
                    GrantService.get().revoke(ids.get("W"));
                }
                assertNull(WindowStateFixtures.active(ids.get("W")), "LIMITATIONS 11: W stops applying at once");
                assertNotNull(stored(ids.get("W")).getRevokedAt(), "the stored W says it was revoked");
            } finally {
                deleteTree(month);
                Files.move(aside, month);
            }
        });
        session.then(r -> {
            assertNull(WindowStateFixtures.active(ids.get("W")), "LIMITATIONS 11: the end written to the grant file survives the restart");
            assertFalse(can("u1", r.jenkins.getItemByFullName("rw"), Item.CONFIGURE), "W confers nothing after the restart");
        });
    }

    /**
     * T-GAP-378 (L3-21; ARCHITECTURE 4 "if they cannot all be read, every open window ends ('its state could
     * not be confirmed at startup')", LIMITATIONS 11): session 1: u1's window V on {@code rv}. Between the
     * sessions the current change month file gets no permission bits at all (neither readable nor writable).
     * Session 2 starts, V is not active, and the stored V is revoked by SYSTEM with the reason "its state
     * could not be confirmed at startup". Skipped where this process can still read the file.
     */
    @Test
    public void t_gap_378_unreadableAndUnwritableChangeLogEndsOpenWindowsAtStartup() throws Throwable {
        session.then(r -> {
            prepare(r, false);
            r.jenkins.createProject(FreeStyleProject.class, "rv");
            ids.put("V", window("u1", "rv", 120));
        });
        Path month = newestMonth();
        PlatformFixtures.assumeCanMakeUnreadable();
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(month);
        try {
            Files.setPosixFilePermissions(month, PosixFilePermissions.fromString("---------"));
            Assumptions.assumeFalse(Files.isReadable(month), "the file system does not refuse reads for this process");
            session.then(r -> {
                assertNull(WindowStateFixtures.active(ids.get("V")), "ARCHITECTURE 4: V ends at a start whose change log cannot be read");
                assertFalse(can("u1", r.jenkins.getItemByFullName("rv"), Item.CONFIGURE), "V confers nothing");
                Grant stored = stored(ids.get("V"));
                assertEquals(ACL.SYSTEM_USERNAME, stored.getRevokedBy(), "V is revoked by SYSTEM");
                assertTrue(lower(stored.getRevokedReason()).contains(UNCONFIRMED_REASON), "V's stored reason: " + stored.getRevokedReason());
            });
        } finally {
            Files.setPosixFilePermissions(month, original);
        }
    }

    /**
     * T-GAP-387 (L3-04 / L3-21; LIMITATIONS 11 "every window still open ends: it is revoked by SYSTEM with
     * the reason 'its state could not be confirmed at startup' and a GRANT_REVOKE record"; ARCHITECTURE 4
     * "Ending a window marks it ended in memory first, then appends the GRANT_REVOKE record, then rewrites the
     * grant file"): session 1: u1's window U on {@code ru}. Between the sessions the current change month
     * file loses its read permission only (it stays writable, so an append can still be made). Session 2
     * starts: U is not active and is stored as revoked by SYSTEM with that reason. After the read permission
     * is restored, the change log holds a GRANT_REVOKE record by SYSTEM that identifies U (the end was
     * appended, not only written to the grant file). Skipped where this process can still read the file.
     */
    @Test
    public void t_gap_387_startupEndIsRecordedInAChangeLogThatCannotBeReadButCanBeWritten() throws Throwable {
        session.then(r -> {
            prepare(r, false);
            r.jenkins.createProject(FreeStyleProject.class, "ru");
            ids.put("U", window("u1", "ru", 120));
        });
        Path month = newestMonth();
        PlatformFixtures.assumeCanMakeUnreadable();
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(month);
        try {
            Files.setPosixFilePermissions(month, PosixFilePermissions.fromString("-w-------"));
            Assumptions.assumeFalse(Files.isReadable(month), "the file system does not refuse reads for this process");
            assertTrue(Files.isWritable(month), "fixture: the month file stays writable");
            session.then(r -> {
                assertNull(WindowStateFixtures.active(ids.get("U")), "ARCHITECTURE 4: U ends at a start whose change log cannot be read");
                Grant stored = stored(ids.get("U"));
                assertEquals(ACL.SYSTEM_USERNAME, stored.getRevokedBy(), "U is revoked by SYSTEM");
                assertTrue(lower(stored.getRevokedReason()).contains(UNCONFIRMED_REASON), "U's stored reason: " + stored.getRevokedReason());
                Files.setPosixFilePermissions(month, original);
                List<String> revokes = ApproverFormFixtures.records(ChangeType.GRANT_REVOKE).stream()
                        .filter(rec -> WindowStateFixtures.identifies(rec, ids.get("U"), "ru"))
                        .map(rec -> rec.getUser() + " " + rec.getDetail()).toList();
                assertTrue(revokes.stream().anyMatch(s -> s.startsWith(ACL.SYSTEM_USERNAME + " ")),
                        "LIMITATIONS 11: U's end at startup is recorded by a GRANT_REVOKE record by SYSTEM in the writable change log;"
                                + " records naming U: " + revokes + "; last lines: " + tail(month, 3));
            });
        } finally {
            Files.setPosixFilePermissions(month, original);
        }
    }

    // ------------------------------------------------------------------ fixtures and helpers

    /** Session 1 of L3-01: both switches on; u1's window on a; jobs b and c; r's pending R and R2 on J. */
    private void unreadableStoreSession1() throws Throwable {
        session.then(r -> {
            prepare(r, true);
            for (String name : new String[] {"a", "b", "c"}) {
                r.jenkins.createProject(FreeStyleProject.class, name);
            }
            FreeStyleProject job = r.jenkins.createProject(FreeStyleProject.class, "J");
            setBatchControl(job, new BatchControlJobProperty(true));
            ids.put("window", window("u1", "a", 120));
            ids.put("R", as("r", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "first request", "a1")).getId());
            ids.put("R2", as("r", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "second request", "a1")).getId());
            assertTrue(can("u1", r.jenkins.getItemByFullName("a"), Item.CONFIGURE), "premise: the window confers on a");
        });
    }

    /** Between the sessions of L3-01: the window's grant file and R's request file get no permission bits. */
    private void unreadableBetweenSessions() throws IOException {
        PlatformFixtures.assumeCanMakeUnreadable();
        for (Path p : List.of(grantFile(ids.get("window")), home.resolve("batch-control/requests/run/" + ids.get("R") + ".xml"))) {
            assertTrue(Files.isRegularFile(p), "premise (ARCHITECTURE 5): " + p + " is stored");
            restore.add(p);
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("---------"));
            Assumptions.assumeFalse(Files.isReadable(p), "the file system does not refuse reads for this process");
        }
    }

    private void restorePermissions() throws IOException {
        for (Path p : restore) {
            if (Files.exists(p)) {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-r--r--"));
            }
        }
        restore.clear();
    }

    private void prepare(JenkinsRule r, boolean runControl) throws Exception {
        home = r.jenkins.getRootDir().toPath();
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "u2", "r", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u2"));
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("r"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        r.jenkins.setAuthorizationStrategy(strategy);
        r.jenkins.save();
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setRunControlEnabled(runControl);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    private String window(String user, String fullName, int minutes) throws Exception {
        return window(user, fullName, minutes, GrantAction.CONFIGURE);
    }

    private String window(String user, String fullName, int minutes, GrantAction action) throws Exception {
        GrantRequest request = as(user, () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, fullName),
                List.of(action), minutes, "restart work on " + fullName, "a1"));
        Grant grant = as("a1", () -> GrantRequestService.get().approve(request.getId(), "ok"));
        assertNotNull(grant, "fixture: the approval opens a window");
        return grant.getId();
    }

    /** Keeps the stored reason (lower case) and revoker of window {@code w} right after its end. */
    private void remember(String w) throws IOException {
        Grant stored = stored(ids.get(w));
        assertNotNull(stored.getRevokedAt(), "premise: window " + w + " is stored as revoked right after its end");
        reasons.put(w, stored.getRevokedReason() == null ? null : lower(stored.getRevokedReason()));
        revokers.put(w, stored.getRevokedBy());
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private Path grantFile(String id) {
        Path file = home.resolve("batch-control/grants/" + id + ".xml");
        assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): the window is stored at " + file);
        return file;
    }

    private Grant stored(String id) throws IOException {
        return (Grant) Jenkins.XSTREAM2.fromXML(Files.readString(grantFile(id), StandardCharsets.UTF_8));
    }

    private List<Path> monthFiles() throws IOException {
        try (Stream<Path> files = Files.list(home.resolve("batch-control/changes"))) {
            return files.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}\\.jsonl")).sorted().toList();
        }
    }

    private Path newestMonth() throws IOException {
        List<Path> months = monthFiles();
        assertFalse(months.isEmpty(), "premise (ARCHITECTURE 5): change records are stored under batch-control/changes");
        return months.get(months.size() - 1);
    }

    private static String tail(Path file, int count) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        return lines.subList(Math.max(0, lines.size() - count), lines.size()).stream()
                .map(l -> l.length() > 200 ? l.substring(0, 200) + "..." : l).toList().toString();
    }

    /** True if a record of {@code type} names {@code name} as a whole word in its target or detail. */
    private static boolean recordsNaming(ChangeType type, String name) {
        Pattern word = Pattern.compile("(?<![\\w./-])" + Pattern.quote(name) + "(?![\\w./-])");
        return ApproverFormFixtures.records(type).stream()
                .anyMatch(rec -> word.matcher(rec.getTarget() + " " + rec.getDetail()).find());
    }

    private static boolean writesRefused(Path dir) {
        Path probe = dir.resolve("probe-" + System.nanoTime() + ".tmp");
        try {
            Files.createFile(probe);
            Files.delete(probe);
            return false;
        } catch (IOException expected) {
            return true;
        }
    }

    private static void deleteTree(Path root) throws IOException {
        assertTrue(Files.exists(root), "premise: " + root + " exists");
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static void createAs(JenkinsRule r, String user, String folder, String name) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(r, user);
        WebRequest req = new WebRequest(new URL(wc.createCrumbedUrl(((TopLevelItem) r.jenkins.getItemByFullName(folder)).getUrl() + "createItem")
                .toExternalForm() + "&name=" + name), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        req.setRequestBody("<?xml version='1.1' encoding='UTF-8'?><project><builders/><publishers/><buildWrappers/></project>");
        int code = wc.getPage(req).getWebResponse().getStatusCode();
        assertTrue(code < 400, "fixture: " + user + " creates " + folder + "/" + name + ", got " + code);
    }

    private static boolean can(String user, Item item, hudson.security.Permission p) {
        return item != null && item.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }

    private interface Body<T> {
        T run() throws Exception;
    }

    private static <T> T as(String user, Body<T> body) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            return body.run();
        }
    }
}
