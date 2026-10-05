package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * security-39 S-39-03: a window whose end could not be written to its grant file must not come back
 * after a restart, however many change records were appended after its end. SPEC item 8 line 170
 * ("deleting the item ends the window ... re-creating items never makes a window reach an item
 * nobody approved") and SPEC item 4 (restart durability); ARCHITECTURE section 4 (durable ends).
 * Matrix row T-SEC-95 (note 274); the restart half without the record volume and with the store
 * writable again before the restart is T-08-190 ({@link ItemScopeRestartTest}).
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
    public void t_sec_95_windowEndedWhileTheStoreRefusedWritesStaysEndedAfterManyRecordsAndARestart() throws Throwable {
        session.then(r -> {
            prepare(r);
            onA = approve(request("u1", "a")).getId();
            onC = approve(request("u1", "c")).getId();
            assertTrue(can("u1", r.jenkins.getItemByFullName("a"), Item.CONFIGURE), "premise: the window confers on a");
            home = r.jenkins.getRootDir().toPath();
            Path dir = grantsDir();
            try {
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

    // ---------------------------------------------------------------- helpers

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
