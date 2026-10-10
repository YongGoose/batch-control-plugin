package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * security-39 S-39-03: a window whose end could not be written to its grant file must not come back
 * after a restart, however many change records were appended after its end. SPEC item 8 line 170
 * ("deleting the item ends the window ... re-creating items never makes a window reach an item
 * nobody approved") and SPEC item 4 (restart durability); ARCHITECTURE section 4 (durable ends).
 * Matrix row T-SEC-95 (note 274); the restart half without the record volume and with the store
 * writable again before the restart is T-08-190 ({@link ItemScopeRestartTest}). T-SEC-103 (note
 * 275): when the change records cannot be read at startup, every open window ends (fail closed,
 * D-75 (2), ARCHITECTURE 4). T-SEC-106 (a torn last line of the change log is a record that could
 * not be read: every open window ends), its negative twin T-SEC-107 (a complete line of an unknown
 * type is readable: open windows stay open), T-SEC-108 (the records written after the torn line
 * stay readable) and T-SEC-109 (a window opened after the torn line was handled survives a plain
 * restart) (note 278).
 *
 * <p>The change records are appended while Jenkins is down, directly to the newest
 * {@code changes/YYYY-MM.jsonl} (ARCHITECTURE section 5), as copies of the newest line the store
 * itself wrote with a fresh id each, so every copy is newer than the window's GRANT_REVOKE record
 * and lies within the window's lifetime (StoreDataFixtures' approach for run records).
 *
 * <p>Written from docs/SPEC.md items 4 and 8, docs/DECISIONS.md D-74, docs/ARCHITECTURE.md sections
 * 4 and 5 and the Given/When/Then of docs/reports/security-39.md only (no src/main knowledge).
 */
public class WindowEndRestartTest {

    private static final int APPENDED = 60_000;
    /** The documented reason of a window ended because its state could not be confirmed at startup (ARCHITECTURE 4, D-75). */
    private static final String UNCONFIRMED_REASON = "its state could not be confirmed at startup";
    private static final Pattern ID_FIELD = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]*)\"");

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    private Path home;
    private String onA;
    private String onC;
    private boolean enforced;

    /**
     * T-SEC-95 (S-39-03): u1's CONFIGURE windows on the jobs {@code a} and {@code c}. The grants
     * directory and the {@code a} window's file refuse writes; the administrator deletes {@code a}
     * (premise: the window is not active) and creates a new job {@code a} (premise: it confers
     * nothing). Jenkins stops; 60,000 change records are appended after the window's end; Jenkins
     * starts while the grant store still refuses writes. u1 holds no Configure on the new
     * {@code a}, and the window is not active. Guard: the window on {@code c}, stored in the same
     * read-only directory, is active after the start and confers. Skipped where this process can
     * write despite the read-only bits (root, Windows).
     */
    @Test
    @Tag("core")
    public void t_sec_95_windowEndedWhileTheStoreRefusedWritesStaysEndedAfterManyRecordsAndARestart() throws Throwable {
        session.then(r -> {
            prepare(r);
            onA = approve(request("u1", "a")).getId();
            onC = approve(request("u1", "c")).getId();
            assertTrue(can("u1", r.jenkins.getItemByFullName("a"), Item.CONFIGURE), "premise: the window confers on a");
            home = r.jenkins.getRootDir().toPath();
            Path dir = grantsDir();
            try {
                PlatformFixtures.assumeCanMakeUnwritable();
                assertTrue(grantFile(onA).setWritable(false, false), "fixture: the stored window on a made read-only");
                assertTrue(dir.toFile().setWritable(false, false), "fixture: the grants directory made read-only");
                enforced = writesRefused(dir) && !Files.isWritable(grantFile(onA).toPath());
                Assumptions.assumeTrue(enforced, "the file system does not refuse writes to read-only files for this process");
                try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator deletes a and creates a new a
                    r.jenkins.getItemByFullName("a").delete();
                    assertTrue(GrantService.get().listActive().stream().noneMatch(g -> onA.equals(g.getId())),
                            "premise: the window on a is not active after its job was deleted");
                    r.jenkins.createProject(FreeStyleProject.class, "a");
                }
                assertFalse(can("u1", r.jenkins.getItemByFullName("a"), Item.CONFIGURE), "premise: before the restart the new a gets nothing");
            } catch (Throwable failure) {
                restoreWrites();
                throw failure;
            }
        });

        appendChangeRecords(APPENDED);

        session.then(r -> {
            try {
                assertTrue(writesRefused(grantsDir()), "premise: the grant store still refuses writes after the start");
                Item recreated = r.jenkins.getItemByFullName("a");
                assertNotNull(recreated, "premise: the new a exists after the start");
                assertFalse(can("u1", recreated, Item.CONFIGURE), "S-39-03: after a restart with " + APPENDED
                        + " change records newer than the window's end, the window must not reach the new a");
                assertTrue(GrantService.get().listActive().stream().noneMatch(g -> onA.equals(g.getId())),
                        "S-39-03: the window on the deleted a must not be active after the restart");
                assertTrue(can("u1", r.jenkins.getItemByFullName("c"), Item.CONFIGURE),
                        "guard: the window on c, stored in the same read-only directory, loads and confers after the start");
            } finally {
                restoreWrites();
            }
        });
    }

    /**
     * T-SEC-103 (S-39-03 fix direction "fail closed", D-75 (2), ARCHITECTURE 4 "if they cannot all
     * be read, every open window ends ('its state could not be confirmed at startup')"): u1's open
     * CONFIGURE window on the job {@code c}. Guard: after a plain restart it is still active and
     * confers. Jenkins stops; every change month file ({@code changes/YYYY-MM.jsonl}) is made
     * unreadable; Jenkins starts. u1 holds no Configure on {@code c}, the window is not active, its
     * stored file records it as revoked by SYSTEM with a reason saying its state could not be
     * confirmed at startup, and u1's grants page lists it as ended with that reason. Skipped where
     * this process can read the files despite the cleared read bits (root, Windows).
     */
    @Test
    public void t_sec_103_openWindowEndsAtStartupWhenTheChangeLogCannotBeRead() throws Throwable {
        session.then(r -> {
            prepare(r);
            onC = approve(request("u1", "c")).getId();
            home = r.jenkins.getRootDir().toPath();
            assertTrue(can("u1", r.jenkins.getItemByFullName("c"), Item.CONFIGURE), "premise: the window confers on c");
        });
        session.then(r -> {
            assertTrue(GrantService.get().listActive().stream().anyMatch(g -> onC.equals(g.getId())),
                    "guard: after a plain restart the window on c is still active");
            assertTrue(can("u1", r.jenkins.getItemByFullName("c"), Item.CONFIGURE), "guard: after a plain restart the window confers on c");
        });

        List<Path> months = monthFiles();
        assertFalse(months.isEmpty(), "premise (ARCHITECTURE 5): change records are stored under " + home.resolve("batch-control/changes"));
        try {
            PlatformFixtures.assumeCanMakeUnreadable();
            for (Path month : months) {
                assertTrue(month.toFile().setReadable(false, false), "fixture: " + month + " made unreadable");
            }
            Assumptions.assumeTrue(months.stream().noneMatch(Files::isReadable),
                    "the file system does not refuse reads of unreadable files for this process");
            session.then(r -> {
                try {
                    assertTrue(months.stream().noneMatch(Files::isReadable), "premise: the change log is still unreadable after the start");
                    assertFalse(can("u1", r.jenkins.getItemByFullName("c"), Item.CONFIGURE),
                            "S-39-03: an open window whose state could not be confirmed at startup must not confer");
                    assertTrue(GrantService.get().listActive().stream().noneMatch(g -> onC.equals(g.getId())),
                            "S-39-03: the window on c must not be active after a start with an unreadable change log");
                    Path file = grantsDir().resolve(onC + ".xml");
                    assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): the window is stored at " + file);
                    Grant stored = (Grant) Jenkins.XSTREAM2.fromXML(Files.readString(file, StandardCharsets.UTF_8));
                    assertEquals(ACL.SYSTEM_USERNAME, stored.getRevokedBy(),
                            "S-39-03: the window ended at startup is revoked by SYSTEM (stored revokedBy, SPEC 3)");
                    assertTrue(String.valueOf(stored.getRevokedReason()).toLowerCase(Locale.ROOT).contains(UNCONFIRMED_REASON),
                            "D-63, ARCHITECTURE 4: the stored revocation reason must say '" + UNCONFIRMED_REASON + "', was: "
                                    + stored.getRevokedReason());
                    HtmlPage list = UsabilityFixtures.htmlPage(r, "u1", "batch-control/grants/");
                    List<DomElement> rows = WindowStateFixtures.endedRowsNaming(list, "c");
                    assertTrue(rows.stream().anyMatch(row -> row.asNormalizedText().toLowerCase(Locale.ROOT).contains(UNCONFIRMED_REASON)),
                            "D-63: the Ended list must show the window on c with the reason '" + UNCONFIRMED_REASON + "'; rows naming it: "
                                    + rows.stream().map(row -> ApproverFormFixtures.excerpt(row.asNormalizedText())).toList());
                } finally {
                    restoreReads(months);
                }
            });
        } finally {
            restoreReads(months);
        }
    }

    /**
     * T-SEC-106 (D-75 (2), ARCHITECTURE 4 "if they cannot all be read, every open window ends";
     * note 278): u1's open CONFIGURE windows on the jobs {@code a} and {@code c}. Jenkins stops; a
     * torn line (a GRANT_REVOKE record for {@code a} cut off inside the user field: no closing brace,
     * no line end) is appended to the newest {@code changes/YYYY-MM.jsonl}; Jenkins starts. Neither
     * window is active and u1 holds no Configure on {@code a} or {@code c}; each stored window reads
     * back as revoked by SYSTEM with a reason saying its state could not be confirmed at startup;
     * u1's Ended list shows both with that reason. The twin with a complete line of an unknown type
     * is T-SEC-107; what the records written after the torn line must still be is T-SEC-108 and
     * T-SEC-109.
     */
    @Test
    @Tag("core")
    public void t_sec_106_tornChangeLogLineEndsEveryOpenWindowAtStartup() throws Throwable {
        openWindowsOnAAndC();
        appendTornLine();

        session.then(r -> {
            for (String[] window : new String[][] {{onA, "a"}, {onC, "c"}}) {
                assertFalse(can("u1", r.jenkins.getItemByFullName(window[1]), Item.CONFIGURE),
                        "D-75 (2): after a start whose change log ends in a torn line, the window on " + window[1] + " must not confer");
                assertTrue(GrantService.get().listActive().stream().noneMatch(g -> window[0].equals(g.getId())),
                        "D-75 (2): the window on " + window[1] + " must not be active after a start with a torn change log line");
                Grant stored = storedWindow(window[0]);
                assertEquals(ACL.SYSTEM_USERNAME, stored.getRevokedBy(),
                        "D-75 (2): the window on " + window[1] + " ended at startup is revoked by SYSTEM (stored revokedBy, SPEC 3)");
                assertTrue(String.valueOf(stored.getRevokedReason()).toLowerCase(Locale.ROOT).contains(UNCONFIRMED_REASON),
                        "D-63, ARCHITECTURE 4: the stored revocation reason of the window on " + window[1] + " must say '" + UNCONFIRMED_REASON
                                + "', was: " + stored.getRevokedReason());
            }
            HtmlPage list = UsabilityFixtures.htmlPage(r, "u1", "batch-control/grants/");
            for (String name : new String[] {"a", "c"}) {
                List<DomElement> rows = WindowStateFixtures.endedRowsNaming(list, name);
                assertTrue(rows.stream().anyMatch(row -> row.asNormalizedText().toLowerCase(Locale.ROOT).contains(UNCONFIRMED_REASON)),
                        "D-63: the Ended list must show the window on " + name + " with the reason '" + UNCONFIRMED_REASON + "'; rows naming it: "
                                + rows.stream().map(row -> ApproverFormFixtures.excerpt(row.asNormalizedText())).toList());
            }
        });
    }

    /**
     * T-SEC-108 (D-63, SPEC 4 "records are append-only", ARCHITECTURE 4 "ending a window ... appends
     * the GRANT_REVOKE record" and 5 (JSONL, one record per line); note 278): as T-SEC-106, a torn
     * last line in the newest change month file at startup. Each of the two windows ended at that
     * start is recorded by a readable GRANT_REVOKE record naming SYSTEM and identifying it: a record
     * appended after the torn line is not merged into it and lost.
     */
    @Test
    @Tag("core")
    public void t_sec_108_recordsAppendedAfterATornLineStayReadable() throws Throwable {
        openWindowsOnAAndC();
        appendTornLine();

        session.then(r -> {
            assertTrue(GrantService.get().listActive().stream().noneMatch(g -> onA.equals(g.getId()) || onC.equals(g.getId())),
                    "premise (T-SEC-106): both windows ended at the start");
            List<ChangeRecord> revokes = ApproverFormFixtures.records(ChangeType.GRANT_REVOKE);
            for (String[] window : new String[][] {{onA, "a"}, {onC, "c"}}) {
                assertTrue(revokes.stream().anyMatch(rec -> WindowStateFixtures.identifies(rec, window[0], window[1])
                                && ACL.SYSTEM_USERNAME.equals(rec.getUser())),
                        "D-63, ARCHITECTURE 4: the end of the window on " + window[1] + " must be recorded by a readable GRANT_REVOKE record"
                                + " naming SYSTEM (a record appended after the torn line must not be merged into it); readable GRANT_REVOKE"
                                + " records: " + WindowStateFixtures.describe(revokes) + "; last lines of the month file: " + tail(newestMonthPath(), 3));
            }
        });
    }

    /**
     * T-SEC-109 (SPEC 8 "a restart keeps an unexpired window", ARCHITECTURE 4 "the startup re-end
     * reads the GRANT_REVOKE records since the oldest open window was granted, bounded by time";
     * note 278): as T-SEC-106, a torn last line at startup ends u1's windows on {@code a} and
     * {@code c} (premise). In that session a1 approves a new CONFIGURE window for u1 on {@code c}
     * (premise: it confers). After a plain restart, with nothing appended, the new window is still
     * active and confers: damage older than every open window does not end the windows opened after
     * it at every later start.
     */
    @Test
    public void t_sec_109_windowOpenedAfterATornLineWasHandledSurvivesAPlainRestart() throws Throwable {
        openWindowsOnAAndC();
        appendTornLine();
        String[] renewed = new String[1];

        session.then(r -> {
            assertTrue(GrantService.get().listActive().stream().noneMatch(g -> onA.equals(g.getId()) || onC.equals(g.getId())),
                    "premise (T-SEC-106): both windows ended at the start");
            renewed[0] = approve(request("u1", "c")).getId();
            assertTrue(can("u1", r.jenkins.getItemByFullName("c"), Item.CONFIGURE), "premise: the new window on c confers");
        });
        session.then(r -> {
            assertTrue(GrantService.get().listActive().stream().anyMatch(g -> renewed[0].equals(g.getId())),
                    "SPEC 8, ARCHITECTURE 4: the window approved after the torn line was handled must still be active after a plain restart;"
                            + " last lines of the month file: " + tail(newestMonthPath(), 4));
            assertTrue(can("u1", r.jenkins.getItemByFullName("c"), Item.CONFIGURE),
                    "SPEC 8: the window approved after the torn line was handled still confers on c after a plain restart");
        });
    }

    /**
     * T-SEC-107 (negative twin of T-SEC-106; D-75 (2), ARCHITECTURE 4; note 278): u1's open
     * CONFIGURE windows on {@code a} and {@code c}. Jenkins stops; a complete, well-formed line with
     * an unknown record type ({@code "type":"FUTURE_TYPE"}, target {@code a}, a current {@code at}
     * in the stored lines' epoch-millisecond form) is appended to the newest
     * {@code changes/YYYY-MM.jsonl}; Jenkins starts. Both windows are still active and confer, and
     * neither stored window is revoked: a record that can be read but is of a type this version does
     * not know is not a record that could not be read.
     */
    @Test
    public void t_sec_107_completeLineOfAnUnknownTypeLeavesOpenWindowsOpen() throws Throwable {
        openWindowsOnAAndC();
        Path month = newestMonthFile();
        List<String> lines = Files.readAllLines(month, StandardCharsets.UTF_8);
        String newest = lines.stream().filter(l -> !l.isBlank()).reduce((x, y) -> y).orElse("");
        assertTrue(Pattern.compile("\"at\"\\s*:\\s*\\d+").matcher(newest).find(),
                "premise (ARCHITECTURE 5): a stored change record carries its time as epoch milliseconds: " + ApproverFormFixtures.excerpt(newest));
        String future = "{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"FUTURE_TYPE\",\"target\":\"a\",\"user\":\"admin\",\"at\":"
                + System.currentTimeMillis() + ",\"detail\":\"a record type of a later version\"}";
        appendRaw(month, future + "\n");
        assertEquals(future, Files.readAllLines(month, StandardCharsets.UTF_8).get(lines.size()),
                "premise: the complete FUTURE_TYPE line is the last line of " + month);

        session.then(r -> {
            for (String[] window : new String[][] {{onA, "a"}, {onC, "c"}}) {
                assertTrue(GrantService.get().listActive().stream().anyMatch(g -> window[0].equals(g.getId())),
                        "D-75 (2): a complete line of an unknown type must not end the window on " + window[1] + " at startup");
                assertTrue(can("u1", r.jenkins.getItemByFullName(window[1]), Item.CONFIGURE),
                        "D-75 (2): the window on " + window[1] + " still confers after a start with a complete FUTURE_TYPE line");
                assertNull(storedWindow(window[0]).getRevokedAt(), "the stored window on " + window[1] + " must not be revoked");
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    /** Session 1 of T-SEC-106/107: the fixture, u1's windows on {@code a} and {@code c}, both conferring. */
    private void openWindowsOnAAndC() throws Throwable {
        session.then(r -> {
            prepare(r);
            onA = approve(request("u1", "a")).getId();
            onC = approve(request("u1", "c")).getId();
            home = r.jenkins.getRootDir().toPath();
            assertTrue(can("u1", r.jenkins.getItemByFullName("a"), Item.CONFIGURE), "premise: the window confers on a");
            assertTrue(can("u1", r.jenkins.getItemByFullName("c"), Item.CONFIGURE), "premise: the window confers on c");
        });
    }

    private Path newestMonthFile() throws IOException {
        Path month = newestMonthPath();
        assertTrue(Files.readString(month, StandardCharsets.UTF_8).endsWith("\n"), "premise: the stored month file ends with a complete line: " + month);
        return month;
    }

    private Path newestMonthPath() throws IOException {
        List<Path> months = monthFiles();
        assertFalse(months.isEmpty(), "premise (ARCHITECTURE 5): change records are stored under " + home.resolve("batch-control/changes"));
        return months.get(months.size() - 1);
    }

    /**
     * Appends to the newest change month file, while Jenkins is down, a GRANT_REVOKE record for
     * {@code a} cut off inside its user field: no closing brace and no line end (a write torn by a
     * crash).
     */
    private void appendTornLine() throws IOException {
        Path month = newestMonthFile();
        String torn = "{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"GRANT_REVOKE\",\"target\":\"a\",\"user\":\"adm";
        appendRaw(month, torn);
        assertTrue(Files.readString(month, StandardCharsets.UTF_8).endsWith("\n" + torn),
                "premise: the torn line is the last, unterminated line of " + month);
    }

    /** The last {@code count} lines of {@code file}, each shortened, for failure messages. */
    private static String tail(Path file, int count) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        return lines.subList(Math.max(0, lines.size() - count), lines.size()).stream()
                .map(l -> l.length() > 220 ? l.substring(0, 220) + "..." : l).toList().toString();
    }

    private static void appendRaw(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private Grant storedWindow(String id) throws IOException {
        Path file = grantsDir().resolve(id + ".xml");
        assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): the window is stored at " + file);
        return (Grant) Jenkins.XSTREAM2.fromXML(Files.readString(file, StandardCharsets.UTF_8));
    }

    private List<Path> monthFiles() throws IOException {
        try (Stream<Path> files = Files.list(home.resolve("batch-control/changes"))) {
            return files.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}\\.jsonl")).sorted().toList();
        }
    }

    private static void restoreReads(List<Path> files) {
        for (Path file : files) {
            file.toFile().setReadable(true, false);
        }
    }

    /** Appends {@code count} copies of the newest non-GRANT_REVOKE line of the newest change month file, each with a new id. */
    private void appendChangeRecords(int count) throws IOException {
        Path changes = home.resolve("batch-control/changes");
        Path month;
        try (Stream<Path> files = Files.list(changes)) {
            month = files.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}\\.jsonl")).sorted().reduce((x, y) -> y).orElse(null);
        }
        assertNotNull(month, "premise (ARCHITECTURE 5): change records are stored under " + changes);
        List<String> lines = Files.readAllLines(month, StandardCharsets.UTF_8);
        String template = null;
        for (int i = lines.size() - 1; i >= 0 && template == null; i--) {
            if (!lines.get(i).isBlank() && !lines.get(i).contains("GRANT_REVOKE")) {
                template = lines.get(i);
            }
        }
        assertNotNull(template, "premise: the month file holds a record newer than the window's end: " + month);
        Matcher id = ID_FIELD.matcher(template);
        assertTrue(id.find(), "premise: a stored change record line carries its id: " + ApproverFormFixtures.excerpt(template));
        String before = template.substring(0, id.start(1));
        String after = template.substring(id.end(1));
        try (BufferedWriter out = Files.newBufferedWriter(month, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            for (int i = 0; i < count; i++) {
                out.write(before + UUID.randomUUID() + after);
                out.write('\n');
            }
        }
        assertEquals(lines.size() + count, StoreDataFixtures.lineCount(month), "premise: " + count + " records were appended to " + month);
    }

    private Path grantsDir() {
        return home.resolve("batch-control/grants");
    }

    private File grantFile(String id) {
        File file = grantsDir().resolve(id + ".xml").toFile();
        assertTrue(file.isFile(), "premise (ARCHITECTURE 5): the window is stored at " + file);
        return file;
    }

    private void restoreWrites() {
        if (home != null) {
            grantsDir().toFile().setWritable(true);
            if (onA != null) {
                grantsDir().resolve(onA + ".xml").toFile().setWritable(true);
            }
        }
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

    private static boolean can(String user, Item item, hudson.security.Permission p) {
        return item.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }

    private static GrantRequest request(String user, String fullName) {
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            return GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, fullName),
                    List.of(GrantAction.CONFIGURE), 120, "restart window on " + fullName, "a1");
        }
    }

    private static Grant approve(GrantRequest request) {
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            Grant grant = GrantRequestService.get().approve(request.getId(), "ok");
            assertNotNull(grant, "fixture: the approval must open a window");
            return grant;
        }
    }

    private static void prepare(JenkinsRule r) throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        r.jenkins.setAuthorizationStrategy(strategy);
        r.jenkins.save();

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        r.jenkins.createProject(FreeStyleProject.class, "a");
        r.jenkins.createProject(FreeStyleProject.class, "c");
    }
}
