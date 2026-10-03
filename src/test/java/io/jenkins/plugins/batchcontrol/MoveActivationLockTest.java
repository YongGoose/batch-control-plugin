package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.relocate.RelocationAction;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Items;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.records;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-59a and SPEC 6a (activation line, exception): while run control and change control are both
 * on, a job moved by a user without Overall/Administer starts over like a newly created job: it is
 * no longer activated and gets the D-34 lock ({@code approvalRequired}, {@code blockTimer},
 * {@code blockUpstream} on, {@code allowedUpstreamJobs} emptied), recorded as a {@code HELD} change
 * record naming the move. An administrator's move keeps the state; with either control off a move
 * keeps it too. Matrix rows T-SEC-65..69 (note 200).
 *
 * <p>u1 holds native Item/Move, native Item/Delete on {@code prod} and native Item/Create on
 * {@code team}, so every move here is allowed by D-59 and the rows measure only the D-59a lock.
 * Moves go through the folders plugin's endpoint {@code POST <item>/move/move}.
 *
 * <p>Written from docs/SPEC.md (6a, 8), docs/DECISIONS.md D-59a/D-34 and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class MoveActivationLockTest {

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

    /** T-SEC-65: u1 moves an activated, unlocked job: not activated, locked, HELD naming the move. */
    @Test
    public void t_sec_65_nonAdminMoveOfActivatedJobLocksIt() throws Exception {
        FreeStyleProject x = liveJob(prod, "x");
        assertSuccess(move("u1", x, team), "u1's move (allowed by D-59)");
        FreeStyleProject moved = team.getItem("x") instanceof FreeStyleProject f ? f : null;
        assertNotNull(moved, "premise: the job moved to team");
        assertLocked(moved, "a job moved by a non-administrator");
        assertHeldNamingMove(moved);
    }

    /** T-SEC-66: u1 moves a folder containing an activated job: the job inside is locked the same way. */
    @Test
    public void t_sec_66_nonAdminMoveOfFolderLocksTheJobsInside() throws Exception {
        Folder sub = prod.createProject(Folder.class, "sub");
        FreeStyleProject x = liveJob(sub, "x");
        assertTrue(isActivated(x), "premise: activated");
        assertSuccess(move("u1", sub, team), "u1's move of the folder (allowed by D-59)");
        Item moved = j.jenkins.getItemByFullName("team/sub/x");
        assertTrue(moved instanceof FreeStyleProject, "premise: the job moved with its folder");
        assertLocked((FreeStyleProject) moved, "a job inside a folder moved by a non-administrator");
        assertHeldNamingMove((FreeStyleProject) moved);
    }

    /** T-SEC-67 (guard): an administrator's move keeps activation and the unlocked switches. */
    @Test
    public void t_sec_67_administratorMoveKeepsActivation() throws Exception {
        FreeStyleProject x = liveJob(prod, "x");
        assertSuccess(move("admin", x, team), "the administrator's move");
        assertUnchanged((FreeStyleProject) team.getItem("x"), "an administrator's move");
    }

    /** T-SEC-68 (guard): with change control off a non-administrator's move keeps the state. */
    @Test
    public void t_sec_68_changeControlOffKeepsActivation() throws Exception {
        FreeStyleProject x = liveJob(prod, "x");
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(false);
        cfg.save();
        assertSuccess(move("u1", x, team), "u1's move with change control off");
        assertUnchanged((FreeStyleProject) team.getItem("x"), "a move with change control off");
    }

    /** T-SEC-69 (guard): with run control off a non-administrator's move keeps the state. */
    @Test
    public void t_sec_69_runControlOffKeepsActivation() throws Exception {
        FreeStyleProject x = liveJob(prod, "x");
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(false);
        cfg.save();
        assertSuccess(move("u1", x, team), "u1's move with run control off");
        FreeStyleProject moved = (FreeStyleProject) team.getItem("x");
        assertUnchanged(moved, "a move with run control off");
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
        assertTrue(records(ChangeType.HELD).isEmpty(), "premise: no HELD record yet");
        return x;
    }

    private WebResponse move(String userId, Item item, Folder destination) throws Exception {
        return ApproverFormFixtures.post(j, userId, item.getUrl() + "move/move",
                Collections.singletonList(new NameValuePair("destination", "/" + destination.getFullName())));
    }

    private static void assertLocked(FreeStyleProject job, String what) {
        assertFalse(isActivated(job), what + " must no longer be activated (D-59a)");
        BatchControlJobProperty p = job.getProperty(BatchControlJobProperty.class);
        assertNotNull(p, what + " must carry the Batch Control property");
        assertTrue(p.isApprovalRequired(), what + " must get approvalRequired=true (D-34 lock)");
        assertTrue(p.isBlockTimer(), what + " must get blockTimer=true (D-34 lock)");
        assertTrue(p.isBlockUpstream(), what + " must get blockUpstream=true (D-34 lock)");
        List<String> allowed = p.getAllowedUpstreamJobs();
        assertTrue(allowed == null || allowed.isEmpty(), what + " must have allowedUpstreamJobs emptied, got " + allowed);
    }

    private static void assertUnchanged(FreeStyleProject job, String what) {
        assertNotNull(job, "premise: the job moved");
        assertTrue(isActivated(job), what + " must keep the activation");
        BatchControlJobProperty p = job.getProperty(BatchControlJobProperty.class);
        assertFalse(p.isApprovalRequired() || p.isBlockTimer() || p.isBlockUpstream(), what + " must keep the switches off");
        assertEquals(List.of("feeder"), p.getAllowedUpstreamJobs(), what + " must keep allowedUpstreamJobs");
        assertTrue(records(ChangeType.HELD).isEmpty(), what + " must record no HELD, got " + describe(records(ChangeType.HELD)));
    }

    private static void assertHeldNamingMove(FreeStyleProject job) {
        List<ChangeRecord> held = records(ChangeType.HELD);
        assertEquals(1, held.size(), "one HELD record for the move, got " + describe(held));
        ChangeRecord rec = held.get(0);
        String text = (rec.getTarget() + " " + rec.getDetail()).toLowerCase(Locale.ROOT);
        assertTrue(text.contains(job.getName().toLowerCase(Locale.ROOT)), "the HELD record names the job: " + describe(held));
        assertTrue(text.contains("mov"), "the HELD record names the move: " + describe(held));
        assertEquals("u1", rec.getUser(), "the HELD record names the mover: " + describe(held));
    }

    private static String describe(List<ChangeRecord> recs) {
        return recs.stream().map(r -> r.getType() + " user=" + r.getUser() + " target=" + r.getTarget() + " detail=" + r.getDetail())
                .collect(Collectors.joining("; "));
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
