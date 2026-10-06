package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.relocate.RelocationAction;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.records;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8, the D-59 move line with its D-73 clause: "A refused move changes nothing, answers
 * with a plain message naming what is missing and is recorded as GRANT_VIOLATION (the same refused
 * move by the same user -- same item and destination -- is recorded once per minute; repeats inside
 * that minute go to the Jenkins log only, D-73)". DECISIONS D-73: "a one-minute cooldown per
 * attempt key ... a different item, destination or user always writes its own record". Matrix row
 * T-08-191 (spec-review-r6 M-1, note 273). The rename twins of this clause are T-08-147, T-08-156
 * and T-08-184 ({@link ItemIdentityBindingTest}); this row exercises the move path and its key.
 *
 * <p>Fixture as T-SEC-53 ({@link MoveChangeControlTest}): change control on, Batch Control matrix
 * strategy; folders {@code prod}, {@code team}, {@code team2}; jobs {@code prod/x}, {@code prod/y}.
 * u1 and u2 hold Overall/Read, Item/Read, RequestGrant and native Item/Move, no Delete anywhere and
 * no Create of their own; u1 holds approved CREATE windows on {@code team} and {@code team2}, u2 one
 * on {@code team}. Every move below is therefore refused for the same reason (Delete on the item is
 * missing), so the user, the item and the destination are the only things that differ. Moves go
 * through the folders plugin's endpoint {@code POST <item>/move/move} with
 * {@code destination=/<folder>}. Time is the plugin clock ({@code BatchClock}, fixed per step),
 * never a sleep.
 *
 * <p>Written from docs/SPEC.md item 8, docs/DECISIONS.md D-59 and D-73 and docs/TEST-MATRIX.md
 * only (no src/main knowledge).
 */
@WithJenkins
public class MoveRefusalCoalescingTest {

    /** Not on a minute boundary, so a calendar-minute bucket and a per-key cooldown give different answers at T0+61 s. */
    private static final Instant T0 = Instant.parse("2025-06-10T10:00:20Z");

    private JenkinsRule j;
    private Folder prod;
    private Folder team;
    private Folder team2;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        clockAt(T0); // before the windows are approved, so they are active at every step below
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "u2", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        for (String userId : new String[] {"u1", "u2"}) {
            strategy.add(RelocationAction.RELOCATE, PermissionEntry.user(userId));
            strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        prod = j.jenkins.createProject(Folder.class, "prod");
        team = j.jenkins.createProject(Folder.class, "team");
        team2 = j.jenkins.createProject(Folder.class, "team2");
        prod.createProject(FreeStyleProject.class, "x");
        prod.createProject(FreeStyleProject.class, "y");

        openCreateWindow("u1", "team");
        openCreateWindow("u1", "team2");
        openCreateWindow("u2", "team");

        for (String userId : new String[] {"u1", "u2"}) {
            for (String job : new String[] {"x", "y"}) {
                assertFalse(has(prod.getItem(job), userId, Item.DELETE), "premise: " + userId + " has no Delete on prod/" + job);
                assertTrue(has(prod.getItem(job), userId, RelocationAction.RELOCATE), "premise: " + userId + " holds native Item/Move");
            }
            assertTrue(has(team, userId, Item.CREATE), "premise: " + userId + " holds Create on team (window)");
        }
        assertTrue(has(team2, "u1", Item.CREATE), "premise: u1 holds Create on team2 (window)");
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-08-191 (SPEC 8 D-59 move line, D-73; spec-review-r6 M-1): u1 moves {@code prod/x} to
     * {@code /team} at T0 (refused, one GRANT_VIOLATION naming u1 and {@code prod/x}), repeats it at
     * once and at T0+30 s: each repeat is refused, nothing moves, no further record, and the Jenkins
     * log names {@code prod/x} for the repeat. At T0+30 s the same user and item to another
     * destination ({@code /team2}), another item of the same user ({@code prod/y} to {@code /team})
     * and another user (u2, {@code prod/x} to {@code /team}) each write their own record naming that
     * user and item. At T0+61 s (past the minute of the T0 record) u1's {@code prod/x} to
     * {@code /team} writes again, while {@code prod/x} to {@code /team2}, first recorded at T0+30 s,
     * is still inside its own minute and writes nothing (D-73: a cooldown per attempt key); a repeat
     * right after the new record writes nothing either. Nothing is ever moved.
     */
    @Test
    public void t_08_191_repeatedRefusedMoveIsRecordedOncePerMinutePerUserItemAndDestination() throws Exception {
        int expected = records(ChangeType.GRANT_VIOLATION).size();

        // T0: the first refusal is recorded
        expected = assertRecordedRefusal(move("u1", "x", team), expected, "u1", "prod/x", "u1 moving prod/x to /team at T0");

        // T0: the immediate repeat is refused, not recorded, and logged
        try (LogRecorder log = new LogRecorder().record("io.jenkins.plugins.batchcontrol", Level.ALL).capture(2000)) {
            assertUnrecordedRefusal(move("u1", "x", team), expected, "u1's immediate repeat of prod/x to /team");
            List<String> mentioning = log.getRecords().stream().map(MoveRefusalCoalescingTest::format)
                    .filter(m -> m.contains("prod/x")).collect(Collectors.toList());
            assertFalse(mentioning.isEmpty(), "D-73: the merged repeat must go to the Jenkins log naming prod/x; plugin log records were: "
                    + log.getRecords().stream().map(MoveRefusalCoalescingTest::format).collect(Collectors.toList()));
        }

        // T0+30 s: still inside the minute for the same key; every other key writes its own record
        clockAt(T0.plusSeconds(30));
        assertUnrecordedRefusal(move("u1", "x", team), expected, "u1's repeat of prod/x to /team at T0+30 s");
        expected = assertRecordedRefusal(move("u1", "x", team2), expected, "u1", "prod/x", "u1 moving prod/x to another destination /team2");
        expected = assertRecordedRefusal(move("u1", "y", team), expected, "u1", "prod/y", "u1 moving another item prod/y to /team");
        expected = assertRecordedRefusal(move("u2", "x", team), expected, "u2", "prod/x", "another user u2 moving prod/x to /team");

        // T0+61 s: past the minute of the T0 record, inside the minute of the T0+30 s record
        clockAt(T0.plusSeconds(61));
        expected = assertRecordedRefusal(move("u1", "x", team), expected, "u1", "prod/x",
                "u1 moving prod/x to /team again after the minute of its first record");
        assertUnrecordedRefusal(move("u1", "x", team2), expected,
                "u1's repeat of prod/x to /team2, 31 s after its own record (D-73: a cooldown per attempt key)");
        assertUnrecordedRefusal(move("u1", "x", team), expected, "u1's repeat of prod/x to /team right after the new record");

        assertNotNull(prod.getItem("x"), "prod/x never moved");
        assertNotNull(prod.getItem("y"), "prod/y never moved");
        assertTrue(team.getItems().isEmpty() && team2.getItems().isEmpty(), "nothing arrived in team or team2");
    }

    // ---------------------------------------------------------------- helpers

    /** The move was refused (4xx, nothing moved) and added exactly one GRANT_VIOLATION naming {@code user} and {@code item}. */
    private int assertRecordedRefusal(WebResponse response, int expected, String user, String item, String what) {
        assertRefusedAndNothingMoved(response, what);
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(expected + 1, violations.size(), what + " must add exactly one GRANT_VIOLATION, got " + (violations.size() - expected)
                + ": " + violations.stream().map(MoveRefusalCoalescingTest::describe).collect(Collectors.toList()));
        ChangeRecord rec = violations.get(violations.size() - 1);
        assertEquals(user, rec.getUser(), what + ": the record names " + user + ": " + describe(rec));
        assertTrue(mentions(rec, item), what + ": the record names " + item + ": " + describe(rec));
        return expected + 1;
    }

    /** The move was refused (4xx, nothing moved) and added no GRANT_VIOLATION. */
    private void assertUnrecordedRefusal(WebResponse response, int expected, String what) {
        assertRefusedAndNothingMoved(response, what);
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(expected, violations.size(), "D-73: " + what + " must add no GRANT_VIOLATION, got " + (violations.size() - expected)
                + ": " + violations.stream().map(MoveRefusalCoalescingTest::describe).collect(Collectors.toList()));
    }

    private void assertRefusedAndNothingMoved(WebResponse response, String what) {
        assertClientError(response, what);
        String body = response.getContentAsString();
        assertFalse(body.contains("\tat ") || body.contains("Caused by:"), what + ": the refusal must be a plain message: " + excerpt(body));
        assertNotNull(prod.getItem("x"), what + ": prod/x stays in prod");
        assertNotNull(prod.getItem("y"), what + ": prod/y stays in prod");
        assertNull(team.getItem("x"), what + ": nothing arrives in team");
        assertNull(team2.getItem("x"), what + ": nothing arrives in team2");
        assertNull(team.getItem("y"), what + ": nothing arrives in team");
    }

    /** {@code POST prod/<job>/move/move} with {@code destination=/<folder>} as {@code userId} (folders plugin endpoint). */
    private WebResponse move(String userId, String job, Folder destination) throws Exception {
        Item item = prod.getItem(job);
        assertNotNull(item, "fixture: prod/" + job + " must exist");
        return ApproverFormFixtures.post(j, userId, item.getUrl() + "move/move",
                Collections.singletonList(new NameValuePair("destination", "/" + destination.getFullName())));
    }

    /** Files a CREATE window on the folder {@code scope} as {@code userId} through the form; a1 approves it. */
    private void openCreateWindow(String userId, String scope) throws Exception {
        long before = GrantService.get().listActive().stream().filter(g -> userId.equals(g.getUser())).count();
        String id = submitGrantOk(j, userId, scope, Arrays.asList("CREATE"), 30, "new jobs in " + scope, null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        long after = GrantService.get().listActive().stream().filter(g -> userId.equals(g.getUser())).count();
        assertEquals(before + 1, after, "fixture: " + userId + " must hold one more active window");
    }

    private static void clockAt(Instant instant) {
        BatchClock.setForTest(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static boolean has(hudson.security.AccessControlled target, String userId, Permission permission) {
        return target.getACL().hasPermission2(hudson.model.User.getById(userId, true).impersonate2(), permission);
    }

    private static boolean mentions(ChangeRecord rec, String text) {
        return String.valueOf(rec.getTarget()).contains(text) || String.valueOf(rec.getDetail()).contains(text);
    }

    private static String describe(ChangeRecord rec) {
        return "user=" + rec.getUser() + " target=" + rec.getTarget() + " detail=" + rec.getDetail();
    }

    private static String format(LogRecord record) {
        String message = new SimpleFormatter().formatMessage(record);
        return record.getLevel() + " " + record.getLoggerName() + ": " + message;
    }
}
