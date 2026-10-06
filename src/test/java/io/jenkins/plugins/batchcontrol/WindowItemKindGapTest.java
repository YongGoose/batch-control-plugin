package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.matrix.MatrixProject;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.TopLevelItem;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import jenkins.branch.OrganizationFolder;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.client;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.grantRequestIds;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8 (D-71, D-71c) for the window rules not yet exercised on every item kind: "a request
 * for CREATE or DELETE on an item kind where it cannot apply is refused at submission. The request
 * records the item's kind (descriptor id and display name, for example "Pipeline", "Freestyle
 * project", "Folder", "Multibranch Pipeline", "Organization Folder"); approval is refused when no
 * item exists at that name any more or its kind has changed"; DELETE applies to a job; no window
 * allows renaming. Coverage inventory G-L1, G-L2, G-L3, G-L4; matrix rows T-08-174 .. T-08-176
 * (note 269). The Freestyle/Pipeline kind rows are T-08-115..117.
 *
 * <p>Batch Control matrix strategy, change control on; u1 holds Overall/Read, Item/Read and
 * RequestGrant, a1 approves.
 *
 * <p>Written from docs/SPEC.md item 8, docs/DECISIONS.md D-71 and D-71c and docs/TEST-MATRIX.md
 * only (no src/main knowledge).
 */
@WithJenkins
public class WindowItemKindGapTest {

    private JenkinsRule j;

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
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-08-174 (G-L1): on Pipelines. CREATE on the Pipeline {@code pipe} is refused at submission
     * (service: IllegalArgumentException; form: 4xx; nothing stored). A DELETE window on
     * {@code pipe} deletes it, and does not delete the Pipeline {@code pipe2}. A CONFIGURE window on
     * {@code pipe2} does not allow renaming it: 400 with the D-71c refusal ("Pipeline"), the name is
     * kept, one GRANT_VIOLATION naming u1 and {@code pipe2}.
     */
    @Test
    public void t_08_174_pipelineWindowActions() throws Exception {
        WorkflowJob pipe = j.createProject(WorkflowJob.class, "pipe");
        WorkflowJob pipe2 = j.createProject(WorkflowJob.class, "pipe2");

        Set<String> before = grantRequestIds();
        assertThrows(IllegalArgumentException.class, () -> request("pipe", GrantAction.CREATE),
                "CREATE on a Pipeline must be refused at submission (it is not a folder)");
        assertClientError(submitGrant(j, "u1", "pipe", List.of("CREATE"), 30, "create inside a pipeline", null, "a1"),
                "the form request for CREATE on a Pipeline");
        assertEquals(before, grantRequestIds(), "a refused request must store nothing");

        openWindow("pipe", List.of("DELETE"));
        int other = postDelete("u1", pipe2);
        assertTrue(other >= 400 && other < 500, "the window on pipe must not delete pipe2, got HTTP " + other);
        assertNotNull(j.jenkins.getItemByFullName("pipe2"));
        int deleted = postDelete("u1", pipe);
        assertTrue(deleted < 400, "the DELETE window must delete the Pipeline pipe, got HTTP " + deleted);
        assertNull(j.jenkins.getItemByFullName("pipe"));

        openWindow("pipe2", List.of("CONFIGURE"));
        int violations = violations("pipe2");
        WebResponse rename = ApproverFormFixtures.post(j, "u1", pipe2.getUrl() + "confirmRename",
                List.of(new NameValuePair("newName", "pipe2-renamed")));
        RenameRefusalFixtures.assertWindowRenameRefused(rename, "pipe2", "Pipeline", "u1 renaming the Pipeline pipe2");
        assertNotNull(j.jenkins.getItemByFullName("pipe2"), "pipe2 must keep its name");
        assertNull(j.jenkins.getItemByFullName("pipe2-renamed"));
        assertEquals(violations + 1, violations("pipe2"), "the refused rename must be recorded once as GRANT_VIOLATION");
    }

    /**
     * T-08-175 (G-L4): a pending CONFIGURE request on the folder {@code kinds}; the administrator
     * replaces the folder by a Freestyle job of the same name; a1's approval is refused with
     * IllegalStateException, the request stays PENDING and no window is opened. Guard: a request on
     * the unchanged folder {@code kinds2} is approved.
     */
    @Test
    public void t_08_175_folderApprovalIsRefusedWhenTheFolderWasReplacedByAJob() throws Exception {
        Folder kinds = j.jenkins.createProject(Folder.class, "kinds");
        j.jenkins.createProject(Folder.class, "kinds2");
        GrantRequest onFolder = request("kinds", GrantAction.CONFIGURE);
        assertEquals("Folder", onFolder.getItemKind().getDisplayName(), "premise: the request records the folder's kind");

        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator replaces the folder
            kinds.delete();
            j.jenkins.createProject(FreeStyleProject.class, "kinds");
        }
        assertEquals("hudson.model.FreeStyleProject",
                ((TopLevelItem) j.jenkins.getItemByFullName("kinds")).getDescriptor().getId(), "fixture: kinds is now a Freestyle job");

        assertApprovalRefused(onFolder, "kinds");
        Grant guard = approve(request("kinds2", GrantAction.CONFIGURE));
        assertNotNull(guard, "guard: a request on an unchanged folder must be approved");
        assertTrue(GrantService.get().hasActiveGrant("u1", "kinds2", Item.CONFIGURE), "guard: the window on kinds2 is active");
    }

    /**
     * T-08-176 (G-L2, G-L3): requests on a multibranch project, an organization folder and a
     * multi-configuration project record the item's kind: descriptor id and display name, which
     * for the first two are the names SPEC gives ("Multibranch Pipeline", "Organization Folder");
     * the window opened by an approval carries the same kind. CREATE on the multi-configuration
     * project is refused at submission (it is not a regular folder; nothing stored). After the
     * administrator replaces the multibranch project by a Folder, approving its pending request is
     * refused (IllegalStateException, PENDING).
     */
    @Test
    public void t_08_176_computedFolderAndMatrixKindsAreRecordedAndChecked() throws Exception {
        WorkflowMultiBranchProject mb = j.jenkins.createProject(WorkflowMultiBranchProject.class, "mb");
        OrganizationFolder org = j.jenkins.createProject(OrganizationFolder.class, "org");
        MatrixProject mx = j.jenkins.createProject(MatrixProject.class, "mx");

        assertKind(request("org", GrantAction.CONFIGURE), org, "Organization Folder", true);
        assertKind(request("mx", GrantAction.CONFIGURE), mx, mx.getDescriptor().getDisplayName(), true);
        GrantRequest onMb = request("mb", GrantAction.CONFIGURE);
        assertKind(onMb, mb, "Multibranch Pipeline", false);

        Set<String> before = grantRequestIds();
        assertThrows(IllegalArgumentException.class, () -> request("mx", GrantAction.CREATE),
                "CREATE on a multi-configuration project must be refused at submission");
        assertEquals(before, grantRequestIds(), "the refused request must store nothing");

        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator replaces the multibranch project
            mb.delete();
            j.jenkins.createProject(Folder.class, "mb");
        }
        assertApprovalRefused(onMb, "mb");
    }

    // ---------------------------------------------------------------- helpers

    private void assertKind(GrantRequest request, TopLevelItem item, String displayName, boolean approveIt) {
        assertNotNull(request.getItemKind(), "the request on " + item.getFullName() + " must record the item's kind");
        assertEquals(item.getDescriptor().getId(), request.getItemKind().getDescriptorId(), "descriptor id of " + item.getFullName());
        assertEquals(item.getDescriptor().getDisplayName(), request.getItemKind().getDisplayName(), "the descriptor's display name");
        assertEquals(displayName, request.getItemKind().getDisplayName(), "the display name of " + item.getFullName());
        assertEquals(displayName, GrantRequestService.get().load(request.getId()).getItemKind().getDisplayName(),
                "the stored request keeps the kind");
        if (approveIt) {
            Grant grant = approve(request);
            assertNotNull(grant, "fixture: the approval must open a window");
            assertEquals(displayName, grant.getItemKind().getDisplayName(), "the window carries the kind of " + item.getFullName());
        }
    }

    private void assertApprovalRefused(GrantRequest request, String fullName) {
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            assertThrows(IllegalStateException.class, () -> GrantRequestService.get().approve(request.getId(), "ok"),
                    "approving the request on " + fullName + " after its kind changed must be refused");
        }
        assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(request.getId()).getStatus(),
                "the refused approval must leave the request PENDING");
        assertTrue(GrantService.get().listActive().stream()
                        .noneMatch(g -> "u1".equals(g.getUser()) && fullName.equals(g.getScope().getFullName())),
                "no window may be opened on " + fullName);
        assertFalse(GrantService.get().hasActiveGrant("u1", fullName, Item.CONFIGURE), "no Configure on " + fullName);
    }

    private void openWindow(String fullName, List<String> actions) throws Exception {
        String id = submitGrantOk(j, "u1", fullName, actions, 30, "maintenance of " + fullName, null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
    }

    private int postDelete(String userId, Item item) throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(item.getUrl() + "doDelete"), HttpMethod.POST);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private static int violations(String fullName) {
        return (int) ApproverFormFixtures.records(ChangeType.GRANT_VIOLATION).stream()
                .filter(r -> "u1".equals(r.getUser()))
                .filter(r -> String.valueOf(r.getTarget()).contains(fullName) || String.valueOf(r.getDetail()).contains(fullName))
                .count();
    }

    private static GrantRequest request(String fullName, GrantAction... actions) {
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            return GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, fullName),
                    Arrays.asList(actions), 30, "work on " + fullName, "a1");
        }
    }

    private static Grant approve(GrantRequest request) {
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            return GrantRequestService.get().approve(request.getId(), "ok");
        }
    }
}
