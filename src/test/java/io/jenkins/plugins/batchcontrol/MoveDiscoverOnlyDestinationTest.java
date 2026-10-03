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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.records;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backlog #74 (security-33 S-33-05), D-59 (SPEC item 8): Batch Control's move check must not
 * answer a move into a destination the user can only Discover (not Read) with a 403 of its own.
 * Such a destination is, for that user, an unreadable destination, so the folders plugin's normal
 * answer applies: the same answer as for a destination that does not exist; nothing moves and no
 * GRANT_VIOLATION is recorded (the user never reached a destination Batch Control could judge).
 * Guard: a destination the user can read but lacks Create on is still refused per D-59 and
 * recorded. Matrix rows T-SEC-70/71 (note 210).
 *
 * <p>Actors: {@code u1} holds Overall/Read, Item/Discover and native Item/Move globally, Item/Read
 * and Item/Delete on {@code prod} (folder property), Item/Read on {@code team} (no Create) and
 * nothing on {@code secret} beyond the global Discover.
 *
 * <p>Written from docs/SPEC.md item 8, docs/DECISIONS.md D-59, issue #74 and docs/TEST-MATRIX.md
 * only (no src/main knowledge).
 */
@WithJenkins
public class MoveDiscoverOnlyDestinationTest {

    private JenkinsRule j;
    private Folder prod;
    private Folder team;
    private Folder secret;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        strategy.add(Jenkins.READ, PermissionEntry.user("u1"));
        strategy.add(Item.DISCOVER, PermissionEntry.user("u1"));
        strategy.add(RelocationAction.RELOCATE, PermissionEntry.user("u1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.save();

        prod = j.jenkins.createProject(Folder.class, "prod");
        team = j.jenkins.createProject(Folder.class, "team");
        secret = j.jenkins.createProject(Folder.class, "secret");
        prod.createProject(FreeStyleProject.class, "x");
        folderPermission(prod, "u1", Item.READ, Item.DELETE);
        folderPermission(team, "u1", Item.READ);

        Item x = prod.getItem("x");
        assertTrue(has(x, "u1", Item.READ) && has(x, "u1", Item.DELETE), "premise: u1 reads and may delete prod/x");
        assertTrue(has(x, "u1", RelocationAction.RELOCATE), "premise: u1 holds native Item/Move");
        assertTrue(has(secret, "u1", Item.DISCOVER), "premise: u1 can Discover secret");
        assertFalse(has(secret, "u1", Item.READ), "premise: u1 cannot Read secret");
        assertTrue(has(team, "u1", Item.READ), "premise: u1 reads team");
        assertFalse(has(team, "u1", Item.CREATE), "premise: u1 holds no Create on team");
    }

    /**
     * T-SEC-70 (#74): u1 moves {@code prod/x} to {@code /secret} (Discover only). The answer is the
     * folders plugin's answer for an unknown destination (same status as {@code /no-such-folder}),
     * not core's access-denied page raised from inside a permission check; nothing moves and no
     * GRANT_VIOLATION is recorded. The unknown destination itself also moves and records nothing.
     */
    @Test
    public void t_sec_70_discoverOnlyDestinationGetsTheFoldersPluginAnswer() throws Exception {
        WebResponse unknown = move("u1", prod.getItem("x"), "/no-such-folder");
        int baseline = unknown.getStatusCode();
        assertNotNull(prod.getItem("x"), "premise: a move to an unknown destination moves nothing");
        assertTrue(records(ChangeType.GRANT_VIOLATION).isEmpty(), "premise: an unknown destination records no violation");

        WebResponse discoverOnly = move("u1", prod.getItem("x"), "/secret");
        String body = discoverOnly.getContentAsString();
        assertEquals(baseline, discoverOnly.getStatusCode(), "a Discover-only destination must get the same answer as an"
                + " unknown destination (" + baseline + "), got " + discoverOnly.getStatusCode() + ": " + excerpt(body));
        assertFalse(body.contains("Please login to access"),
                "core's access-denied message for a Discover-only item must not escape the move check: " + excerpt(body));
        assertFalse(body.contains("AccessDeniedException"), "no access-denied exception may reach the answer: " + excerpt(body));
        assertNotNull(prod.getItem("x"), "nothing may move");
        assertNull(secret.getItem("x"), "nothing may arrive in secret");
        assertTrue(records(ChangeType.GRANT_VIOLATION).isEmpty(),
                "a Discover-only destination must not be recorded as GRANT_VIOLATION, got " + records(ChangeType.GRANT_VIOLATION));
    }

    /**
     * T-SEC-71 (guard of T-SEC-70, D-59): u1 moves {@code prod/x} to {@code /team}, which u1 reads
     * but holds no Create on: refused with 4xx, nothing moves, one GRANT_VIOLATION naming u1 and
     * {@code prod/x}. Twin: with native Create on {@code team} the same move goes through.
     */
    @Test
    public void t_sec_71_readableDestinationWithoutCreateIsStillRefusedAndRecorded() throws Exception {
        WebResponse refused = move("u1", prod.getItem("x"), "/team");
        assertClientError(refused, "u1 moving prod/x into team without Create");
        assertNotNull(prod.getItem("x"), "the refused move must leave prod/x in place");
        assertNull(team.getItem("x"), "nothing may arrive in team");
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(1, violations.size(), "the refused move is recorded once as GRANT_VIOLATION, got " + violations);
        ChangeRecord rec = violations.get(0);
        assertEquals("u1", rec.getUser(), "the GRANT_VIOLATION names u1");
        assertTrue((rec.getTarget() != null && rec.getTarget().contains("prod/x"))
                        || (rec.getDetail() != null && rec.getDetail().contains("prod/x")),
                "the GRANT_VIOLATION names prod/x: target=" + rec.getTarget() + " detail=" + rec.getDetail());

        folderPermission(team, "u1", Item.CREATE);
        assertSuccess(move("u1", prod.getItem("x"), "/team"), "twin: the same move with native Create on team");
        assertNull(prod.getItem("x"), "twin: prod/x must have left prod");
        assertNotNull(team.getItem("x"), "twin: x must be in team");
        assertEquals(1, records(ChangeType.GRANT_VIOLATION).size(), "twin: the permitted move adds no violation");
    }

    // ---------------------------------------------------------------- helpers

    /** {@code POST <item url>move/move} with {@code destination} (folders plugin endpoint), redirects off. */
    private WebResponse move(String userId, Item item, String destination) throws Exception {
        assertNotNull(item, "fixture: the item to move must exist");
        return ApproverFormFixtures.post(j, userId, item.getUrl() + "move/move",
                Collections.singletonList(new NameValuePair("destination", destination)));
    }

    private void folderPermission(Folder folder, String userId, Permission... permissions) throws Exception {
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty property =
                folder.getProperties().get(com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class);
        boolean fresh = property == null;
        if (fresh) {
            property = new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(
                    new HashMap<Permission, Set<String>>());
        }
        for (Permission permission : Arrays.asList(permissions)) {
            property.add(permission, PermissionEntry.user(userId));
        }
        if (fresh) {
            folder.addProperty(property);
        } else {
            folder.save();
        }
    }

    private static boolean has(Item item, String userId, Permission permission) {
        return item.getACL().hasPermission2(hudson.model.User.getById(userId, true).impersonate2(), permission);
    }
}
