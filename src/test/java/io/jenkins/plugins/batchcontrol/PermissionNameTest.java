package io.jenkins.plugins.batchcontrol;

import hudson.security.Permission;
import hudson.security.PermissionGroup;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.integrations.PermissionFinder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * SPEC item 2, acceptance line citing D-41: the permission names used by JCasC and scripts are
 * {@code BatchControl/<Name>}, independent of the display title of the group. Matrix row T-02-06.
 *
 * <p>The oracle is matrix-auth's own name lookup ({@link PermissionFinder}), which is what its
 * JCasC support calls to turn {@code "Group/Name"} into a {@link Permission}: it resolves the
 * part before the slash against {@link PermissionGroup#getId()}, so the row measures the group
 * id and not the title.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-41 and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class PermissionNameTest {

    private static final String GROUP_ID = "BatchControl";

    private JenkinsRule j;

    @BeforeEach
    void setUpJenkins(JenkinsRule rule) {
        this.j = rule;
    }

    /**
     * T-02-06 (D-41): {@code BatchControl/Request|Approve|RequestGrant|ViewHistory|Manage} resolve
     * through matrix-auth's name lookup to the plugin's own permissions, a {@link PermissionGroup}
     * with id {@code BatchControl} exists and is the plugin's group, and the display-title form
     * {@code "Batch Control/Request"} no longer resolves. Stored matrix ids ({@code getId()}) keep
     * round-tripping.
     */
    @Test
    public void t_02_06_permissionNamesUseTheStableGroupIdBatchControl() throws Exception {
        // control: the lookup works at all in this environment, so a null below means "the name
        // is not accepted", not "the finder is broken"
        assertSame(Jenkins.READ, PermissionFinder.findPermission("Overall/Read"),
                "control: matrix-auth's name lookup must resolve a core permission name");

        PermissionGroup group = BatchControlPermissions.GROUP;
        assertEquals(GROUP_ID, group.getId(),
                "D-41: the Batch Control permission group id must be the stable id BatchControl");
        assertEquals("Batch Control", group.title.toString(),
                "the display title stays \"Batch Control\" (T-02-02); only the id is pinned here");
        List<PermissionGroup> withId = PermissionGroup.getAll().stream()
                .filter(g -> GROUP_ID.equals(g.getId()))
                .collect(Collectors.toList());
        assertEquals(1, withId.size(), "exactly one registered PermissionGroup must carry the id " + GROUP_ID
                + ", found " + withId.size());
        assertSame(group, withId.get(0), "the group registered under id " + GROUP_ID
                + " must be the plugin's own group");

        Permission[] expected = {
                BatchControlPermissions.REQUEST,
                BatchControlPermissions.APPROVE,
                BatchControlPermissions.REQUEST_GRANT,
                BatchControlPermissions.VIEW_HISTORY,
                BatchControlPermissions.MANAGE,
        };
        String[] names = {"Request", "Approve", "RequestGrant", "ViewHistory", "Manage"};
        for (int i = 0; i < expected.length; i++) {
            String jcascName = GROUP_ID + "/" + names[i];
            Permission resolved = PermissionFinder.findPermission(jcascName);
            assertNotNull(resolved, "D-41: \"" + jcascName + "\" must be accepted by matrix-auth's permission-name lookup"
                    + " (the name JCasC and scripts use)");
            assertSame(expected[i], resolved, "\"" + jcascName + "\" must resolve to the plugin's " + names[i] + " permission");
            assertEquals(expected[i].getId(), PermissionFinder.findPermissionId(jcascName),
                    "\"" + jcascName + "\" must map to the permission's stored id");
            // stored matrix entries use getId(); D-41 says they are not affected
            assertSame(expected[i], Permission.fromId(expected[i].getId()),
                    names[i] + ": the stored permission id must keep round-tripping");

            // guard: the display-title spelling must not be the accepted name - otherwise the
            // row could pass with the title-derived id D-41 replaces still in place
            String titleName = "Batch Control/" + names[i];
            assertNull(PermissionFinder.findPermission(titleName),
                    "\"" + titleName + "\" (display title with a space) must NOT resolve after D-41");
        }

        // guard: resolution is name-specific, not "anything under the group"
        assertNull(PermissionFinder.findPermission(GROUP_ID + "/NoSuchPermission"),
                "an unknown name under the BatchControl group must not resolve");
    }
}
