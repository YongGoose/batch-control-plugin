package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.relocate.RelocationAction;
import hudson.model.AdministrativeMonitor;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Items;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.ops.ConfigureWithoutGrantMonitor;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
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
 * D-59 (SPEC item 8): while change control is on, moving an item (folders plugin Item/Move) by a
 * user without Overall/Administer is allowed only if the user holds Item/Delete on the item and
 * Item/Create on the destination, each native or from an active grant; a CREATE grant's name
 * restriction is matched against the moved item's name. A refused move changes nothing, answers
 * with a plain message (4xx) and is recorded as GRANT_VIOLATION. With change control off moves
 * behave as in Jenkins. The change-permission monitor counts Item/Move. Matrix rows
 * T-SEC-53 .. T-SEC-62.
 *
 * <p>Moves are driven through the folders plugin's real endpoint: {@code POST <item url>move/move}
 * with the form parameter {@code destination}. The folders plugin identifies a destination as
 * {@code "/" + fullName} (e.g. {@code /team}); {@link Items#move} is used only for SYSTEM fixtures.
 * Every refusal row is paired with a twin in which the same move goes through, so a row cannot
 * pass merely because moves are broken.
 *
 * <p>Written from docs/SPEC.md item 8, docs/DECISIONS.md D-59 (D-40, D-40a, D-21, D-48) and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class MoveChangeControlTest {

    /** Internal design references that must not reach an operator (plain message, D-48 style). */
    private static final Pattern INTERNAL_REFERENCE = Pattern.compile("\\(D-|SPEC|\\bD-\\d+");

    private JenkinsRule j;
    private BatchControlMatrixAuthorizationStrategy strategy;
    private Folder prod;
    private Folder team;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "u2", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        // u1 and u2 hold native Item/Move everywhere and nothing else of the change permissions
        strategy.add(RelocationAction.RELOCATE, PermissionEntry.user("u1"));
        strategy.add(RelocationAction.RELOCATE, PermissionEntry.user("u2"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        prod = j.jenkins.createProject(Folder.class, "prod");
        team = j.jenkins.createProject(Folder.class, "team");
        prod.createProject(FreeStyleProject.class, "x");

        assertFalse(has(prod.getItem("x"), "u1", Item.DELETE), "premise: u1 has no Delete on prod/x");
        assertFalse(has(team, "u1", Item.CREATE), "premise: u1 has no Create on team");
        assertTrue(has(prod.getItem("x"), "u1", RelocationAction.RELOCATE), "premise: u1 holds native Item/Move");
    }

    /**
     * T-SEC-53 (MV-1, CREATE+CONFIGURE): u1 holds only native Move (+Read) and an approved
     * FOLDER {@code team} [CREATE, CONFIGURE] grant; moving {@code prod/x} into {@code team} is
     * refused: nothing moves, 4xx with a plain message, one GRANT_VIOLATION naming u1 and
     * {@code prod/x}. Twin: once u1 also holds native Delete on {@code prod}, the same move goes
     * through and records no violation.
     */
    @Test
    public void t_sec_53_moveOnlyUserWithCreateConfigureGrantCannotMoveIn() throws Exception {
        grant("u1", "FOLDER", "team", null, "CREATE", "CONFIGURE");

        WebResponse refused = move("u1", prod.getItem("x"), team);
        assertRefusedMove(refused, "x", "u1", "prod/x");

        folderPermission(prod, "u1", Item.DELETE);
        assertSuccess(move("u1", prod.getItem("x"), team), "twin: the same move with Delete on the item");
        assertMoved("x");
        assertEquals(1, records(ChangeType.GRANT_VIOLATION).size(), "the permitted move adds no violation");
    }

    /**
     * T-SEC-54 (MV-1, CREATE+DELETE): as T-SEC-53 with a FOLDER {@code team} [CREATE, DELETE]
     * grant: the grant's Delete covers the destination, not the item's source, so the move is
     * refused and recorded. Twin: with native Delete on {@code prod} it goes through.
     */
    @Test
    public void t_sec_54_moveOnlyUserWithCreateDeleteGrantCannotMoveIn() throws Exception {
        grant("u1", "FOLDER", "team", null, "CREATE", "DELETE");

        WebResponse refused = move("u1", prod.getItem("x"), team);
        assertRefusedMove(refused, "x", "u1", "prod/x");

        folderPermission(prod, "u1", Item.DELETE);
        assertSuccess(move("u1", prod.getItem("x"), team), "twin: the same move with Delete on the item");
        assertMoved("x");
        assertEquals(1, records(ChangeType.GRANT_VIOLATION).size(), "the permitted move adds no violation");
    }

    /**
     * T-SEC-55 (MV-2): u1 holds native Move, native Item/Create on {@code team} and a FOLDER
     * {@code team} [CONFIGURE] grant, no Delete on {@code prod/x}: the move into {@code team}
     * (which would let u1 edit the job and move it back) is refused and recorded. Twin: with
     * native Delete on {@code prod} it goes through.
     */
    @Test
    public void t_sec_55_nativeCreatePlusConfigureGrantCannotPullAJobIn() throws Exception {
        folderPermission(team, "u1", Item.CREATE);
        assertTrue(has(team, "u1", Item.CREATE), "premise: u1 holds native Create on team");
        grant("u1", "FOLDER", "team", null, "CONFIGURE");

        WebResponse refused = move("u1", prod.getItem("x"), team);
        assertRefusedMove(refused, "x", "u1", "prod/x");

        folderPermission(prod, "u1", Item.DELETE);
        assertSuccess(move("u1", prod.getItem("x"), team), "twin: the same move with Delete on the item");
        assertMoved("x");
        assertEquals(1, records(ChangeType.GRANT_VIOLATION).size(), "the permitted move adds no violation");
    }

    /**
     * T-SEC-56 (MV-3): u1 holds a JOB-scope [CONFIGURE] grant on {@code team/a}, native Move and
     * native Create on {@code team} and {@code parking}, no Delete on either job. Moving
     * {@code team/a} away is refused and recorded; with {@code team/a} moved away by the system,
     * moving {@code prod/a} into its name is refused and recorded too. Twin: u2, holding native
     * Delete on both jobs and the same Create, performs the swap (D-59: still possible for a user
     * who could delete and recreate the jobs).
     */
    @Test
    public void t_sec_56_jobScopeGrantSwapByMovesIsRefusedWithoutDelete() throws Exception {
        Folder parking = j.jenkins.createProject(Folder.class, "parking");
        team.createProject(FreeStyleProject.class, "a");
        prod.createProject(FreeStyleProject.class, "a");
        for (String userId : new String[] {"u1", "u2"}) {
            folderPermission(team, userId, Item.CREATE);
            folderPermission(parking, userId, Item.CREATE);
        }
        grant("u1", "JOB", "team/a", null, "CONFIGURE");

        // step 1: move the granted job away
        WebResponse away = move("u1", team.getItem("a"), parking);
        assertClientError(away, "u1 moving team/a away without Delete on it");
        assertNotNull(team.getItem("a"), "the refused move must leave team/a in place");
        assertNull(parking.getItem("a"), "nothing may arrive in parking");
        assertEquals(1, records(ChangeType.GRANT_VIOLATION).size(), "the refused move-away is recorded once");
        assertRecordNames(records(ChangeType.GRANT_VIOLATION).get(0), "u1", "team/a");

        // step 2: with the name free (system fixture), move another job into it
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            Items.move((FreeStyleProject) team.getItem("a"), parking);
        }
        WebResponse in = move("u1", prod.getItem("a"), team);
        assertClientError(in, "u1 moving prod/a into the granted job's name without Delete on it");
        assertNotNull(prod.getItem("a"), "the refused move must leave prod/a in place");
        assertNull(team.getItem("a"), "no job may take over the granted job's name");
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(2, violations.size(), "the refused move-in is recorded once, got " + violations);
        assertRecordNames(violations.get(1), "u1", "prod/a");

        // twin: u2 with Delete on both jobs can swap them back and forth
        folderPermission(parking, "u2", Item.DELETE);
        folderPermission(prod, "u2", Item.DELETE);
        assertSuccess(move("u2", prod.getItem("a"), team), "twin: u2 moves prod/a into team");
        assertNotNull(team.getItem("a"));
        assertNull(prod.getItem("a"));
        assertEquals(2, records(ChangeType.GRANT_VIOLATION).size(), "u2's permitted move adds no violation");
    }

    /**
     * T-SEC-57 (allowed, native): u2 holds native Move, native Delete on {@code prod} and native
     * Create on {@code team}: the move goes through and records no violation. Guard twin: before
     * the Delete is added the same move is refused.
     */
    @Test
    public void t_sec_57_nativeDeleteAndCreateAllowTheMove() throws Exception {
        folderPermission(team, "u2", Item.CREATE);
        assertClientError(move("u2", prod.getItem("x"), team), "guard: without Delete on the item the move is refused");
        assertNotNull(prod.getItem("x"));
        assertNull(team.getItem("x"));

        folderPermission(prod, "u2", Item.DELETE);
        assertSuccess(move("u2", prod.getItem("x"), team), "a move with native Delete at the source and Create at the destination");
        assertMoved("x");
        assertEquals(1, records(ChangeType.GRANT_VIOLATION).size(), "only the guard attempt is recorded, got "
                + records(ChangeType.GRANT_VIOLATION));
    }

    /**
     * T-SEC-58 (allowed, from grants): u1 holds native Move, a FOLDER {@code prod} [DELETE] grant
     * and a FOLDER {@code team} [CREATE] grant: the move goes through and records no violation.
     * Guard twin: with only the CREATE grant the move is refused.
     */
    @Test
    public void t_sec_58_grantedDeleteAndCreateAllowTheMove() throws Exception {
        grant("u1", "FOLDER", "team", null, "CREATE");
        assertClientError(move("u1", prod.getItem("x"), team), "guard: the CREATE grant alone does not admit the move");
        assertNotNull(prod.getItem("x"));
        assertNull(team.getItem("x"));
        assertEquals(1, records(ChangeType.GRANT_VIOLATION).size(), "guard: the refused move is recorded");

        grant("u1", "FOLDER", "prod", null, "DELETE");
        assertSuccess(move("u1", prod.getItem("x"), team), "a move with granted Delete at the source and granted Create at the destination");
        assertMoved("x");
        assertEquals(1, records(ChangeType.GRANT_VIOLATION).size(), "the permitted move adds no violation");
    }

    /** T-SEC-59 (allowed, administrator): an administrator always moves; no violation is recorded. */
    @Test
    public void t_sec_59_administratorCanAlwaysMove() throws Exception {
        assertSuccess(move("admin", prod.getItem("x"), team), "an administrator's move");
        assertMoved("x");
        assertTrue(records(ChangeType.GRANT_VIOLATION).isEmpty(), "an administrator's move records no violation");
    }

    /**
     * T-SEC-60 (change control off): u1 with native Move and native Create on {@code team} but no
     * Delete moves {@code prod/x} into {@code team} exactly as in Jenkins, with no violation.
     * Falsifiability twin (first): with change control on, the same move is refused.
     */
    @Test
    public void t_sec_60_changeControlOffLeavesMovesAsInJenkins() throws Exception {
        folderPermission(team, "u1", Item.CREATE);
        assertClientError(move("u1", prod.getItem("x"), team), "twin: with change control on the move is refused");
        assertNotNull(prod.getItem("x"));
        int before = records(ChangeType.GRANT_VIOLATION).size();
        assertEquals(1, before, "twin: the refused move is recorded");

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(false);
        cfg.save();
        assertSuccess(move("u1", prod.getItem("x"), team), "with change control off native Move+Create moves");
        assertMoved("x");
        assertEquals(before, records(ChangeType.GRANT_VIOLATION).size(), "change control off records no violation");
    }

    /**
     * T-SEC-61 (D-40 on moves): u1 holds native Move and native Delete on {@code prod} and a FOLDER
     * {@code team} [CREATE] grant restricted to {@code /nightly-[a-z]+/}. Moving {@code prod/x}
     * (non-matching name) is refused with 4xx and a GRANT_VIOLATION (previously a silent redirect
     * without a record); moving {@code prod/nightly-a} goes through.
     */
    @Test
    public void t_sec_61_moveUnderNameRestrictedCreateGrantMatchesTheName() throws Exception {
        prod.createProject(FreeStyleProject.class, "nightly-a");
        folderPermission(prod, "u1", Item.DELETE);
        grant("u1", "FOLDER", "team", "/nightly-[a-z]+/", "CREATE");

        WebResponse refused = move("u1", prod.getItem("x"), team);
        assertRefusedMove(refused, "x", "u1", "prod/x");

        assertSuccess(move("u1", prod.getItem("nightly-a"), team), "a move whose name matches the restriction");
        assertMoved("nightly-a");
        assertEquals(1, records(ChangeType.GRANT_VIOLATION).size(), "the matching move adds no violation");
    }

    /**
     * T-SEC-62 (monitor, SPEC item 8 "Configure/Create/Delete/Move"): with change control on, a
     * non-administrator holding native Item/Move only activates the change-permission monitor and
     * is named on Manage Jenkins. Guards: with only plain readers the monitor is quiet; with change
     * control off it is quiet.
     */
    @Test
    public void t_sec_62_changePermissionMonitorCountsMove() throws Exception {
        AdministrativeMonitor monitor = AdministrativeMonitor.all().get(ConfigureWithoutGrantMonitor.class);
        assertNotNull(monitor, "the change-permission monitor must be registered");
        StrategyFixtures.configureBuildAuthenticator(); // keep the strategy monitor's warning out of the way

        BatchControlMatrixAuthorizationStrategy readersOnly = new BatchControlMatrixAuthorizationStrategy();
        readersOnly.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        readersOnly.add(Jenkins.READ, PermissionEntry.user("plainreader9"));
        readersOnly.add(Item.READ, PermissionEntry.user("plainreader9"));
        j.jenkins.setAuthorizationStrategy(readersOnly);
        assertFalse(monitor.isActivated(), "guard: only readers and the admin, the monitor must stay quiet");

        BatchControlMatrixAuthorizationStrategy withMove = new BatchControlMatrixAuthorizationStrategy();
        withMove.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        withMove.add(Jenkins.READ, PermissionEntry.user("plainreader9"));
        withMove.add(Item.READ, PermissionEntry.user("plainreader9"));
        withMove.add(Jenkins.READ, PermissionEntry.user("mover9"));
        withMove.add(Item.READ, PermissionEntry.user("mover9"));
        withMove.add(RelocationAction.RELOCATE, PermissionEntry.user("mover9"));
        j.jenkins.setAuthorizationStrategy(withMove);
        assertTrue(monitor.isActivated(), "a non-admin holding native Item/Move while change control is on must"
                + " activate the change-permission monitor");

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        HtmlPage manage = wc.goTo("manage/");
        String text = manage.asNormalizedText();
        assertTrue(text.contains("mover9"), "the warning must name the Move holder: " + excerpt(text));
        assertFalse(text.contains("plainreader9"), "a plain reader must not be named: " + excerpt(text));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(false);
        cfg.save();
        assertFalse(monitor.isActivated(), "guard: with change control off the monitor must stay quiet");
    }

    // ---------------------------------------------------------------- helpers

    /** {@code POST <item url>move/move} with {@code destination=/<folder full name>} (folders plugin). */
    private WebResponse move(String userId, Item item, Folder destination) throws Exception {
        assertNotNull(item, "fixture: the item to move must exist");
        return ApproverFormFixtures.post(j, userId, item.getUrl() + "move/move",
                Collections.singletonList(new NameValuePair("destination", "/" + destination.getFullName())));
    }

    private void assertRefusedMove(WebResponse response, String name, String userId, String fullName) {
        assertClientError(response, userId + " moving " + fullName + " into team");
        String body = response.getContentAsString();
        assertFalse(body.contains("\tat ") || body.contains("Stack trace"),
                "the refusal must be a plain message, not a stack trace: " + excerpt(body));
        assertFalse(INTERNAL_REFERENCE.matcher(body).find(),
                "the refusal must not carry internal design references: " + excerpt(body));
        assertNotNull(prod.getItem(name), "the refused move must leave " + fullName + " in place");
        assertNull(team.getItem(name), "nothing may arrive in team");
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(1, violations.size(), "the refused move is recorded once as GRANT_VIOLATION, got " + violations);
        assertRecordNames(violations.get(0), userId, fullName);
    }

    private static void assertRecordNames(ChangeRecord rec, String userId, String fullName) {
        assertTrue(userId.equals(rec.getUser()) && mentions(rec, fullName),
                "the GRANT_VIOLATION must name " + userId + " and " + fullName + ", was " + describe(rec));
    }

    private void assertMoved(String name) {
        assertNull(prod.getItem(name), "the moved item must have left prod");
        assertNotNull(team.getItem(name), "the moved item must be in team");
    }

    private void grant(String userId, String scopeType, String scope, String pattern, String... actions) throws Exception {
        long before = GrantService.get().listActive().stream().filter(g -> userId.equals(g.getUser())).count();
        String id = submitGrantOk(j, userId, scopeType, scope, Arrays.asList(actions), 30,
                "maintenance in " + scope, pattern, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        long after = GrantService.get().listActive().stream().filter(g -> userId.equals(g.getUser())).count();
        assertEquals(before + 1, after, "fixture: " + userId + " must hold one more active grant");
    }

    /** Adds a native permission on a folder through matrix-auth's folder property (SYSTEM fixture). */
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
        assertTrue(has(folder, userId, permission), "premise: " + userId + " holds " + permission.getId()
                + " on " + folder.getFullName());
    }

    private static boolean has(Item item, String userId, Permission permission) {
        return item.getACL().hasPermission2(hudson.model.User.getById(userId, true).impersonate2(), permission);
    }

    private static boolean mentions(ChangeRecord rec, String name) {
        return (rec.getTarget() != null && rec.getTarget().contains(name))
                || (rec.getDetail() != null && rec.getDetail().contains(name));
    }

    private static String describe(ChangeRecord rec) {
        return rec.getType() + " user=" + rec.getUser() + " target=" + rec.getTarget() + " detail=" + rec.getDetail();
    }
}
