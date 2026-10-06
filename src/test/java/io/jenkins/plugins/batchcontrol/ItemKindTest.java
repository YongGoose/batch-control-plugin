package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.TopLevelItem;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.ItemKind;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8 and section 3 (D-71): a permission request records its item's kind (descriptor id
 * and display name, for example "Pipeline", "Freestyle project", "Folder"), the window copies it
 * from the request, and approval is refused when no item exists at that name any more or its
 * kind has changed. Matrix rows T-08-115 .. T-08-117 (note 260).
 *
 * <p>Requests are made through {@code GrantRequestService} as the requester and decided as the
 * approver, the frozen service contract. The kind is compared both with the live item's
 * descriptor and with the display names SPEC names, so a kind that is merely "some string" fails.
 * The refused approval is {@code IllegalStateException} (the request is valid, the world changed),
 * and leaves the request PENDING without a window (SPEC 4: PENDING -> APPROVED creates the
 * window, nothing else does).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-71, docs/ARCHITECTURE.md sections 2 and 5 and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ItemKindTest {

    private JenkinsRule j;
    private Folder kinds;
    private WorkflowJob pipe;
    private FreeStyleProject fs;

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

        kinds = j.jenkins.createProject(Folder.class, "kinds");
        pipe = kinds.createProject(WorkflowJob.class, "pipe");
        fs = kinds.createProject(FreeStyleProject.class, "fs");
    }

    /**
     * T-08-115 (D-71): a request on a Pipeline, a Freestyle job and a Folder records the item's
     * kind (descriptor id and display name), the stored request keeps it, and the window opened by
     * the approval carries the same kind.
     */
    @Test
    public void t_08_115_requestAndWindowRecordTheItemKind() throws Exception {
        assertKindRecorded(pipe, "org.jenkinsci.plugins.workflow.job.WorkflowJob", "Pipeline", GrantAction.CONFIGURE);
        assertKindRecorded(fs, "hudson.model.FreeStyleProject", "Freestyle project", GrantAction.CONFIGURE);
        assertKindRecorded(kinds, "com.cloudbees.hudson.plugins.folder.Folder", "Folder", GrantAction.CREATE);
    }

    /**
     * T-08-116 (D-71): approval is refused with IllegalStateException when the item at the
     * request's name was replaced by an item of another kind: a Freestyle job by a Folder, and a
     * Pipeline by a Freestyle job (both jobs, so a check of "job or folder" alone does not pass).
     * Each request stays PENDING and no window is opened. Guard: a request on the unchanged folder
     * {@code kinds} is approved.
     */
    @Test
    public void t_08_116_approvalIsRefusedWhenTheItemWasReplacedByAnotherKind() throws Exception {
        GrantRequest onFreestyle = request("kinds/fs", GrantAction.CONFIGURE);
        GrantRequest onPipeline = request("kinds/pipe", GrantAction.CONFIGURE);

        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator replaces the items
            fs.delete();
            kinds.createProject(Folder.class, "fs");
            pipe.delete();
            kinds.createProject(FreeStyleProject.class, "pipe");
        }
        assertEquals("com.cloudbees.hudson.plugins.folder.Folder",
                ((TopLevelItem) j.jenkins.getItemByFullName("kinds/fs")).getDescriptor().getId(), "fixture: kinds/fs is now a folder");
        assertEquals("hudson.model.FreeStyleProject",
                ((TopLevelItem) j.jenkins.getItemByFullName("kinds/pipe")).getDescriptor().getId(), "fixture: kinds/pipe is now a Freestyle job");

        assertApprovalRefused(onFreestyle, "kinds/fs");
        assertApprovalRefused(onPipeline, "kinds/pipe");

        GrantRequest guard = request("kinds", GrantAction.CREATE);
        Grant grant = approve(guard);
        assertNotNull(grant, "guard: a request on an unchanged item must be approved");
        assertTrue(GrantService.get().hasActiveGrant("u1", "kinds", Item.CREATE), "guard: the window must be active");
    }

    /**
     * T-08-117 (D-71): approval is refused with IllegalStateException when no item exists at the
     * request's name any more; the request stays PENDING and no window is opened. Guard: a request
     * on an item that still exists is approved.
     */
    @Test
    public void t_08_117_approvalIsRefusedWhenTheItemWasDeleted() throws Exception {
        GrantRequest onDeleted = request("kinds/fs", GrantAction.CONFIGURE);
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator deletes the job
            fs.delete();
        }
        assertTrue(j.jenkins.getItemByFullName("kinds/fs") == null, "fixture: kinds/fs is gone");

        assertApprovalRefused(onDeleted, "kinds/fs");

        GrantRequest guard = request("kinds/pipe", GrantAction.CONFIGURE);
        assertNotNull(approve(guard), "guard: a request on an existing item must be approved");
        assertTrue(GrantService.get().hasActiveGrant("u1", "kinds/pipe", Item.CONFIGURE), "guard: the window must be active");
    }

    // ---------------------------------------------------------------- helpers

    private void assertKindRecorded(TopLevelItem item, String descriptorId, String displayName, GrantAction action) {
        assertEquals(descriptorId, item.getDescriptor().getId(), "fixture: the item's descriptor id");
        GrantRequest request = request(item.getFullName(), action);
        assertKind(request.getItemKind(), item, descriptorId, displayName, "the request on " + item.getFullName());
        assertKind(GrantRequestService.get().load(request.getId()).getItemKind(), item, descriptorId, displayName,
                "the stored request on " + item.getFullName());
        Grant grant = approve(request);
        assertNotNull(grant, "fixture: the approval must open a window");
        assertKind(grant.getItemKind(), item, descriptorId, displayName, "the window on " + item.getFullName());
    }

    private static void assertKind(ItemKind kind, TopLevelItem item, String descriptorId, String displayName, String what) {
        assertNotNull(kind, what + " must record the item's kind");
        assertEquals(descriptorId, kind.getDescriptorId(), what + ": descriptor id");
        assertEquals(item.getDescriptor().getDisplayName(), kind.getDisplayName(), what + ": the descriptor's display name");
        assertEquals(displayName, kind.getDisplayName(), what + ": the display name SPEC names");
    }

    private void assertApprovalRefused(GrantRequest request, String fullName) {
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            assertThrows(IllegalStateException.class, () -> GrantRequestService.get().approve(request.getId(), "ok"),
                    "D-71: approving the request on " + fullName + " must be refused");
        }
        assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(request.getId()).getStatus(),
                "the refused approval must leave the request on " + fullName + " PENDING");
        List<Grant> mine = GrantService.get().listActive().stream().filter(g -> "u1".equals(g.getUser())).toList();
        assertTrue(mine.stream().noneMatch(g -> fullName.equals(g.getScope().getFullName())),
                "no window may be opened on " + fullName + ", active: " + mine.stream().map(g -> g.getScope().getFullName()).toList());
        for (hudson.security.Permission p : new hudson.security.Permission[] {Item.CREATE, Item.CONFIGURE, Item.DELETE}) {
            assertFalse(GrantService.get().hasActiveGrant("u1", fullName, p), "no " + p.getId() + " on " + fullName);
        }
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
