package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.Permission;
import hudson.security.PermissionScope;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-38a: "The administrator-assigned Request permission already decides who may ask, per job or
 * folder." {@code BatchControl/Request} can therefore be granted on a folder through a project
 * matrix, and that grant lets its holder request runs of the folder's jobs and of no other job.
 * Matrix row T-05-25 (note 187).
 *
 * Written from docs/SPEC.md item 5, docs/DECISIONS.md D-38a and docs/TEST-MATRIX.md only (no
 * src/main knowledge).
 */
@WithJenkins
public class RequestFolderScopeTest {

    private JenkinsRule j;
    private FreeStyleProject inside;
    private FreeStyleProject outside;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        ProjectMatrixAuthorizationStrategy strategy = new ProjectMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"fr", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        Folder ops = j.jenkins.createProject(Folder.class, "ops");
        inside = ops.createProject(FreeStyleProject.class, "in-x");
        outside = j.createFreeStyleProject("out-x");
        setBatchControl(inside, new BatchControlJobProperty(true));
        setBatchControl(outside, new BatchControlJobProperty(true));

        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty property =
                new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(
                        new HashMap<Permission, Set<String>>());
        property.add(BatchControlPermissions.REQUEST, PermissionEntry.user("fr"));
        ops.addProperty(property);
    }

    /**
     * T-05-25: fr holds Overall/Read and Item/Read everywhere and BatchControl/Request only on the
     * folder {@code ops} (project matrix folder property). fr submits a run request for
     * {@code ops/in-x}: stored, requester fr. The same submission for {@code out-x} is refused
     * (4xx) and stores nothing.
     */
    @Test
    public void t_05_25_requestGrantedOnAFolderCoversOnlyItsJobs() throws Exception {
        assertTrue(BatchControlPermissions.REQUEST.isContainedBy(PermissionScope.ITEM),
                "premise (D-38a): BatchControl/Request must be assignable per item");
        assertTrue(can("fr", inside, BatchControlPermissions.REQUEST), "premise: the folder grant covers ops/in-x");
        assertFalse(can("fr", outside, BatchControlPermissions.REQUEST), "premise: fr holds no Request on out-x");
        assertFalse(can("fr", inside, Item.BUILD), "premise: fr holds no Item/Build");

        String id = ApproverFormFixtures.submitRunOk(j, "fr", inside, "month-end batch", "a1");
        assertEquals("ops/in-x", RunRequestService.get().load(id).getJobFullName());
        assertEquals("fr", RunRequestService.get().load(id).getRequester());

        Set<String> before = ApproverFormFixtures.runRequestIds();
        WebResponse refused = ApproverFormFixtures.submitRun(j, "fr", outside, "month-end batch", "a1");
        ApproverFormFixtures.assertClientError(refused, "a run request for a job outside the folder grant");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "the refused request must store nothing");
        j.waitUntilNoActivity();
        assertTrue(outside.getBuilds().isEmpty() && inside.getBuilds().isEmpty(), "nothing may have been built");
    }

    private static boolean can(String userId, Item item, Permission permission) {
        return item.getACL().hasPermission2(User.getById(userId, true).impersonate2(), permission);
    }
}
