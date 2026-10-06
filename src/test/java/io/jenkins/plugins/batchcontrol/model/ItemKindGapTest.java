package io.jenkins.plugins.batchcontrol.model;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * T-GAP-439 (note 285): value semantics of {@link ItemKind} (ARCHITECTURE 5: the kind is the
 * descriptor id, display name and icon class name recorded at submission).
 */
@WithJenkins
public class ItemKindGapTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);
    }

    @Test
    public void t_gap_439_itemKindValueSemantics() throws Exception {
        FreeStyleProject a = j.createFreeStyleProject("fs-a");
        FreeStyleProject b = j.createFreeStyleProject("fs-b");
        Folder folder = j.jenkins.createProject(Folder.class, "fold");

        ItemKind kindA = ItemKind.of(a);
        ItemKind kindB = ItemKind.of(b);
        ItemKind kindFolder = ItemKind.of(folder);

        assertEquals(kindA, kindB, "two Freestyle projects have the same kind");
        assertEquals(kindB, kindA, "equality is symmetric");
        assertEquals(kindA.hashCode(), kindB.hashCode(), "equal kinds have the same hash");
        assertEquals(kindA, kindA, "a kind equals itself");
        assertNotEquals(kindA, kindFolder, "a Freestyle project and a folder are of different kinds");
        assertNotEquals(kindFolder, kindA, "inequality is symmetric");
        assertNotEquals(null, kindA, "a kind is not equal to null");
        assertNotEquals(kindA, (Object) "Freestyle project", "a kind is not equal to another type, even its display name");
        assertEquals("Freestyle project", kindA.toString(), "the string form is the display name");
        assertEquals("Folder", kindFolder.toString(), "the string form is the display name");

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(List.of("a1"));
        cfg.save();
        GrantRequest request;
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "fs-a"),
                    List.of(GrantAction.CONFIGURE), 30, "work on fs-a", "a1");
        }
        GrantRequest stored = GrantRequestService.get().load(request.getId());
        assertNotNull(stored, "the request is stored");
        assertNotNull(stored.getItemKind(), "the stored request records the item's kind");
        assertEquals(ItemKind.of(a), stored.getItemKind(), "the stored kind equals the live item's kind");
        assertEquals(ItemKind.of(a).hashCode(), stored.getItemKind().hashCode(), "and has the same hash");
        assertNotEquals(ItemKind.of(folder), stored.getItemKind(), "and differs from a folder's kind");
    }
}
