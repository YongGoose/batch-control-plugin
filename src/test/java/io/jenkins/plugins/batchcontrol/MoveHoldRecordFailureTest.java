package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.relocate.RelocationAction;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failed change-record append never breaks the D-59a hold of a moved job (TEST-MATRIX note 305, row
 * T-06a-68). While run control and change control are on, a job moved by a user without
 * Overall/Administer starts over like a newly created job: not activated, with the D-34 lock. With
 * {@code batch-control/changes/} unwritable (so neither the move's record nor the {@code HELD} record can
 * be appended) the move still completes without an error page, the job is held and locked, and the
 * failure is logged at WARNING or SEVERE.
 *
 * <p>Basis: SPEC 6a (Exception D-59a: "a job moved by a user without Overall/Administer starts over like a
 * newly created job — it is no longer activated and gets the D-34 lock ({@code approvalRequired},
 * {@code blockTimer}, {@code blockUpstream} on, {@code allowedUpstreamJobs} emptied), recorded as a
 * {@code HELD} change record naming the move"), SPEC 8 (D-59, the move is allowed with native Delete on the
 * source and Create on the destination), SPEC 9 (moves are recorded), the contract of bug hunt A R3-01 and
 * D-42 (a failure to append the change record never stops, reverses or half-applies the operation it
 * records; it is logged at WARNING or above; the request does not answer 500), ARCHITECTURE 5. The same
 * move with a writable store is the guard (T-SEC-65 is the full positive row).
 *
 * <p>Setup as {@link MoveActivationLockTest}: u1 holds native Item/Move, native Item/Delete on
 * {@code prod} and native Item/Create on {@code team}; moves go through {@code POST <item>/move/move}.
 *
 * <p>Written from docs/SPEC.md items 6a, 8 and 9, docs/DECISIONS.md D-34/D-42/D-59a and
 * docs/ARCHITECTURE.md section 5 only (no src/main knowledge).
 */
@WithJenkins
public class MoveHoldRecordFailureTest {

    private JenkinsRule j;
    private Folder prod;
    private Folder team;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(RelocationAction.RELOCATE, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        prod = j.jenkins.createProject(Folder.class, "prod");
        team = j.jenkins.createProject(Folder.class, "team");
        folderPermission(prod, "u1", Item.DELETE);
        folderPermission(team, "u1", Item.CREATE);
    }

    /**
     * T-06a-68 (P0): guard: u1 moves the activated, unlocked {@code prod/g} into {@code team} with a writable
     * store: success, {@code team/g} is not activated and locked. Then u1 moves the activated, unlocked
     * {@code prod/x} into {@code team} while {@code batch-control/changes/} is unwritable: the answer is below
     * 400 (no error page), {@code team/x} exists and {@code prod/x} does not, {@code team/x} is not activated
     * and carries the D-34 lock, and a WARNING or SEVERE log record is written.
     */
    @Test
    public void t_06a_68_movedJobIsHeldWhenTheRecordCannotBeWritten() throws Exception {
        FreeStyleProject g = liveJob(prod, "g");
        WebResponse guardAnswer = move("u1", g, team);
        assertTrue(guardAnswer.getStatusCode() < 400, "guard: with a writable store u1's move succeeds, got HTTP "
                + guardAnswer.getStatusCode() + ": " + excerpt(guardAnswer.getContentAsString()));
        assertHeldAndLocked("team/g", "guard: a job moved by a non-administrator with a writable store");

        FreeStyleProject x = liveJob(prod, "x");
        List<String> problems;
        try (RecordFaultFixtures.Fault ignored = RecordFaultFixtures.makeUnwritable(
                RecordFaultFixtures.changesDir(j.jenkins.getRootDir().toPath()));
             LogRecorder log = new LogRecorder().record("io.jenkins.plugins.batchcontrol", Level.WARNING).capture(200)) {
            WebResponse answer = move("u1", x, team);
            int code = answer.getStatusCode();
            assertTrue(code < 400, "R3-01, D-59a: the move must complete without an error page although its change records"
                    + " cannot be written (no HTTP 500), got HTTP " + code + ": " + excerpt(answer.getContentAsString()));
            problems = log.getRecords().stream()
                    .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                    .map(r -> r.getLevel() + " " + r.getMessage())
                    .collect(Collectors.toList());
        }
        assertNull(j.jenkins.getItemByFullName("prod/x"), "R3-01: the move must be complete: prod/x is gone");
        assertHeldAndLocked("team/x", "R3-01, D-59a: a job moved by a non-administrator while the record cannot be written");
        assertFalse(problems.isEmpty(), "R3-01: the failed change record must be logged at WARNING or SEVERE");
    }

    // ---------------------------------------------------------------- helpers

    /** An activated job with every switch off and one allowed upstream job: in service, unattended. */
    private FreeStyleProject liveJob(Folder parent, String name) throws Exception {
        FreeStyleProject x = parent.createProject(FreeStyleProject.class, name);
        BatchControlJobProperty p = new BatchControlJobProperty(false);
        p.setBlockTimer(false);
        p.setBlockUpstream(false);
        p.setAllowedUpstreamJobs(Collections.singletonList("feeder"));
        BatchControlFixtures.setBatchControl(x, p);
        BatchControlFixtures.activate(x);
        BatchControlJobProperty saved = x.getProperty(BatchControlJobProperty.class);
        assertFalse(saved.isApprovalRequired() || saved.isBlockTimer() || saved.isBlockUpstream(), "premise: unlocked");
        assertEquals(List.of("feeder"), saved.getAllowedUpstreamJobs(), "premise: one allowed upstream job");
        assertTrue(isActivated(x), "premise: activated");
        return x;
    }

    private WebResponse move(String userId, Item item, Folder destination) throws Exception {
        return ApproverFormFixtures.post(j, userId, item.getUrl() + "move/move",
                Collections.singletonList(new NameValuePair("destination", "/" + destination.getFullName())));
    }

    private void assertHeldAndLocked(String fullName, String what) {
        Item item = j.jenkins.getItemByFullName(fullName);
        assertTrue(item instanceof FreeStyleProject, what + ": the job must have moved to " + fullName + ", found " + item);
        FreeStyleProject job = (FreeStyleProject) item;
        assertFalse(isActivated(job), what + " must be held (no longer activated, D-59a)");
        BatchControlJobProperty p = job.getProperty(BatchControlJobProperty.class);
        assertNotNull(p, what + " must carry the Batch Control property");
        assertTrue(p.isApprovalRequired(), what + " must get approvalRequired=true (D-34 lock)");
        assertTrue(p.isBlockTimer(), what + " must get blockTimer=true (D-34 lock)");
        assertTrue(p.isBlockUpstream(), what + " must get blockUpstream=true (D-34 lock)");
        List<String> allowed = p.getAllowedUpstreamJobs();
        assertTrue(allowed == null || allowed.isEmpty(), what + " must have allowedUpstreamJobs emptied, got " + allowed);
    }

    private void folderPermission(Folder folder, String userId, Permission permission) throws Exception {
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty property =
                folder.getProperties().get(com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class);
        if (property == null) {
            property = new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(
                    new HashMap<Permission, Set<String>>());
            property.add(permission, PermissionEntry.user(userId));
            folder.addProperty(property);
        } else {
            property.add(permission, PermissionEntry.user(userId));
            folder.save();
        }
    }
}
