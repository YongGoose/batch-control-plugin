package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.relocate.RelocationAction;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import java.util.Arrays;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8 (D-59): "while change control is on, moving an item (folders plugin Item/Move) by a
 * user without Overall/Administer is allowed only if the user holds Item/Delete on the item and
 * Item/Create on the destination, each native or from an active grant ... A refused move changes
 * nothing ... and is recorded as GRANT_VIOLATION". The rule is Batch Control's change control, not
 * a property of its strategies, so it also holds under matrix-auth's own
 * {@code ProjectMatrixAuthorizationStrategy} (where grants do not confer, D-35a). The Batch Control
 * strategy rows are T-SEC-53..61. Coverage inventory G-L14; matrix row T-SEC-78 (note 269).
 *
 * <p>Plain project-matrix strategy (premise: not a Batch Control strategy); u1 and u2 hold native
 * Overall/Read, Item/Read, Item/Move and Item/Create everywhere, u2 also Item/Delete.
 *
 * <p>Written from docs/SPEC.md item 8, docs/DECISIONS.md D-35a and D-59 and docs/TEST-MATRIX.md
 * only (no src/main knowledge).
 */
@WithJenkins
public class PlainMatrixMoveGuardTest {

    private JenkinsRule j;
    private Folder prod;
    private Folder team;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        ProjectMatrixAuthorizationStrategy strategy = new ProjectMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "u2"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
            strategy.add(RelocationAction.RELOCATE, PermissionEntry.user(userId));
            strategy.add(Item.CREATE, PermissionEntry.user(userId));
        }
        strategy.add(Item.DELETE, PermissionEntry.user("u2"));
        j.jenkins.setAuthorizationStrategy(strategy);
        assertFalse(j.jenkins.getAuthorizationStrategy() instanceof BatchControlMatrixAuthorizationStrategy,
                "premise: a plain matrix-auth strategy, not the Batch Control one");
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        prod = j.jenkins.createProject(Folder.class, "prod");
        team = j.jenkins.createProject(Folder.class, "team");
        prod.createProject(FreeStyleProject.class, "x");
        prod.createProject(FreeStyleProject.class, "y");
        assertFalse(has(prod.getItem("x"), "u1", Item.DELETE), "premise: u1 has no Delete on prod/x");
        assertTrue(has(team, "u1", Item.CREATE), "premise: u1 holds native Create on team");
    }

    /**
     * T-SEC-78 (G-L14): under the plain project-matrix strategy with change control on, u1 (native
     * Move and Create, no Delete) moving {@code prod/x} into {@code team} is refused with 4xx:
     * nothing moves and one GRANT_VIOLATION names u1 and {@code prod/x}. Guard: u2, who also holds
     * native Delete, moves {@code prod/y} into {@code team}, and that adds no violation.
     */
    @Test
    public void t_sec_78_moveGuardAppliesUnderAPlainMatrixStrategy() throws Exception {
        int before = violations("prod/x").size();
        assertClientError(move("u1", prod.getItem("x"), team), "u1 moving prod/x into team without Delete");
        assertNotNull(j.jenkins.getItemByFullName("prod/x"), "prod/x must stay in place");
        assertNull(j.jenkins.getItemByFullName("team/x"), "nothing may arrive in team");
        assertEquals(before + 1, violations("prod/x").size(), "the refused move must be recorded once as GRANT_VIOLATION naming u1");

        int all = ApproverFormFixtures.records(ChangeType.GRANT_VIOLATION).size();
        assertSuccess(move("u2", prod.getItem("y"), team), "guard: u2 (native Delete, Create and Move) moving prod/y");
        assertNotNull(j.jenkins.getItemByFullName("team/y"), "prod/y must arrive in team");
        assertEquals(all, ApproverFormFixtures.records(ChangeType.GRANT_VIOLATION).size(), "the permitted move adds no violation");
    }

    private WebResponse move(String userId, Item item, Folder destination) throws Exception {
        return ApproverFormFixtures.post(j, userId, item.getUrl() + "move/move",
                List.of(new NameValuePair("destination", "/" + destination.getFullName())));
    }

    private static boolean has(Item item, String user, hudson.security.Permission p) {
        return item.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }

    private static List<ChangeRecord> violations(String fullName) {
        return ApproverFormFixtures.records(ChangeType.GRANT_VIOLATION).stream()
                .filter(r -> "u1".equals(r.getUser()))
                .filter(r -> String.valueOf(r.getTarget()).contains(fullName) || String.valueOf(r.getDetail()).contains(fullName))
                .toList();
    }
}
