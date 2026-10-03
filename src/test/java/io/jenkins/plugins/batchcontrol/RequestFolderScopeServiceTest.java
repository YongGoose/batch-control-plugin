package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.Permission;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-38b at service level (SPEC item 2 D-38b line): BatchControl/Request is checked on the job for
 * cancelling and re-designating a run request and an activation request. A requester whose
 * Request comes only from a folder can do both; once they have lost Request on the job they are
 * refused, while a BatchControl/Manage holder can still cancel. {@code hasOwnRequests} is what
 * lets a folder-level requester reach the root without scanning all items: true for the requester
 * after a submission, false for another user and for anonymous. Matrix rows T-05-33 .. T-05-35
 * (note 199).
 *
 * <p>Service entries used (public names given by the coordinator, signatures from the compiled
 * API): {@code RunRequestService} / {@code ActivationService} {@code create}, {@code cancel(id)},
 * {@code changeApprovers(id, List)}, {@code canCancel(request)}, {@code canChangeApprovers(request)},
 * {@code hasOwnRequests(Authentication)}; the {@code can*} checks answer for the current
 * authentication.
 *
 * Written from docs/SPEC.md, docs/DECISIONS.md D-38b and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class RequestFolderScopeServiceTest {

    private JenkinsRule j;
    private Folder ops;
    private FreeStyleProject inside;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        ProjectMatrixAuthorizationStrategy strategy = new ProjectMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        strategy.add(Jenkins.READ, PermissionEntry.user("fr"));
        for (String userId : new String[] {"u9", "m1", "a1", "a2"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.MANAGE, PermissionEntry.user("m1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a2"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.save();

        ops = j.jenkins.createProject(Folder.class, "ops");
        inside = ops.createProject(FreeStyleProject.class, "in-x");
        setBatchControl(inside, new BatchControlJobProperty(true));
        folderGrants(true);
        assertTrue(can("fr", BatchControlPermissions.REQUEST), "premise: fr holds Request on ops/in-x through the folder");
        assertFalse(ActivationFixtures.isActivated(inside), "premise: ops/in-x is not activated");
    }

    /**
     * T-05-33: fr (Request only through the folder) files a run request and an ACTIVATE request
     * for {@code ops/in-x}; for each, canChangeApprovers and canCancel are true, changeApprovers
     * to [a2] is stored, and cancel ends it CANCELLED.
     */
    @Test
    public void t_05_33_folderRequesterCancelsAndRedesignatesRunAndActivationRequests() throws Exception {
        RunRequest run = asUser("fr", () -> RunRequestService.get().create(inside, new LinkedHashMap<>(), "month-end", "a1"));
        ActivationRequest act = asUser("fr", () -> ActivationService.get().create((Item) inside,
                ActivationRequest.Action.ACTIVATE, "into service", List.of("a1")));

        try (ACLContext ignored = as("fr")) {
            assertTrue(RunRequestService.get().canChangeApprovers(RunRequestService.get().load(run.getId())));
            assertTrue(RunRequestService.get().canCancel(RunRequestService.get().load(run.getId())));
            assertTrue(ActivationService.get().canChangeApprovers(ActivationService.get().load(act.getId())));
            assertTrue(ActivationService.get().canCancel(ActivationService.get().load(act.getId())));

            RunRequestService.get().changeApprovers(run.getId(), List.of("a2"));
            ActivationService.get().changeApprovers(act.getId(), List.of("a2"));
        }
        assertEquals(List.of("a2"), RunRequestService.get().load(run.getId()).getApprovers(), "run request re-designated");
        assertEquals(List.of("a2"), ActivationService.get().load(act.getId()).getApprovers(), "activation request re-designated");

        try (ACLContext ignored = as("fr")) {
            RunRequestService.get().cancel(run.getId());
            ActivationService.get().cancel(act.getId());
        }
        assertEquals(RequestStatus.CANCELLED, RunRequestService.get().load(run.getId()).getStatus());
        assertEquals(RequestStatus.CANCELLED, ActivationService.get().load(act.getId()).getStatus());
    }

    /**
     * T-05-34: after fr files both requests the folder grant of Request is withdrawn (Item/Read
     * stays). fr's canCancel and canChangeApprovers are false and the calls are refused with
     * AccessDeniedException, the requests stay PENDING with their approvers; m1 (BatchControl/
     * Manage) can still cancel both.
     */
    @Test
    public void t_05_34_requesterWhoLostRequestIsRefusedAndManageCanStillCancel() throws Exception {
        RunRequest run = asUser("fr", () -> RunRequestService.get().create(inside, new LinkedHashMap<>(), "month-end", "a1"));
        ActivationRequest act = asUser("fr", () -> ActivationService.get().create((Item) inside,
                ActivationRequest.Action.ACTIVATE, "into service", List.of("a1")));

        folderGrants(false);
        assertFalse(can("fr", BatchControlPermissions.REQUEST), "premise: fr has lost Request on ops/in-x");
        assertTrue(can("fr", Item.READ), "premise: fr still reads ops/in-x");

        try (ACLContext ignored = as("fr")) {
            assertFalse(RunRequestService.get().canCancel(RunRequestService.get().load(run.getId())));
            assertFalse(RunRequestService.get().canChangeApprovers(RunRequestService.get().load(run.getId())));
            assertFalse(ActivationService.get().canCancel(ActivationService.get().load(act.getId())));
            assertFalse(ActivationService.get().canChangeApprovers(ActivationService.get().load(act.getId())));
            assertThrows(AccessDeniedException.class, () -> RunRequestService.get().changeApprovers(run.getId(), List.of("a2")));
            assertThrows(AccessDeniedException.class, () -> RunRequestService.get().cancel(run.getId()));
            assertThrows(AccessDeniedException.class, () -> ActivationService.get().changeApprovers(act.getId(), List.of("a2")));
            assertThrows(AccessDeniedException.class, () -> ActivationService.get().cancel(act.getId()));
        }
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(run.getId()).getStatus());
        assertEquals(List.of("a1"), RunRequestService.get().load(run.getId()).getApprovers());
        assertEquals(RequestStatus.PENDING, ActivationService.get().load(act.getId()).getStatus());
        assertEquals(List.of("a1"), ActivationService.get().load(act.getId()).getApprovers());

        try (ACLContext ignored = as("m1")) {
            assertTrue(RunRequestService.get().canCancel(RunRequestService.get().load(run.getId())), "a Manage holder may cancel");
            assertTrue(ActivationService.get().canCancel(ActivationService.get().load(act.getId())), "a Manage holder may cancel");
            RunRequestService.get().cancel(run.getId());
            ActivationService.get().cancel(act.getId());
        }
        assertEquals(RequestStatus.CANCELLED, RunRequestService.get().load(run.getId()).getStatus());
        assertEquals(RequestStatus.CANCELLED, ActivationService.get().load(act.getId()).getStatus());
    }

    /**
     * T-05-35: hasOwnRequests is false for fr before any submission, true after fr files a run
     * request (RunRequestService) and an activation request (ActivationService), and false for
     * u9 (no requests) and for anonymous.
     */
    @Test
    public void t_05_35_hasOwnRequestsIsTrueOnlyForTheRequester() throws Exception {
        Authentication fr = User.getById("fr", true).impersonate2();
        Authentication u9 = User.getById("u9", true).impersonate2();
        assertFalse(RunRequestService.get().hasOwnRequests(fr), "twin: no run request filed yet");
        assertFalse(ActivationService.get().hasOwnRequests(fr), "twin: no activation request filed yet");

        asUser("fr", () -> RunRequestService.get().create(inside, new LinkedHashMap<>(), "month-end", "a1"));
        asUser("fr", () -> ActivationService.get().create((Item) inside, ActivationRequest.Action.ACTIVATE,
                "into service", List.of("a1")));

        assertTrue(RunRequestService.get().hasOwnRequests(fr), "fr has a run request of their own");
        assertTrue(ActivationService.get().hasOwnRequests(fr), "fr has an activation request of their own");
        assertFalse(RunRequestService.get().hasOwnRequests(u9), "u9 has no run request");
        assertFalse(ActivationService.get().hasOwnRequests(u9), "u9 has no activation request");
        assertFalse(RunRequestService.get().hasOwnRequests(Jenkins.ANONYMOUS2), "anonymous has no run request");
        assertFalse(ActivationService.get().hasOwnRequests(Jenkins.ANONYMOUS2), "anonymous has no activation request");
    }

    // ---------------------------------------------------------------- helpers

    /** Replaces the folder property of ops: Item/Read for fr always, Request only if {@code withRequest}. */
    private void folderGrants(boolean withRequest) throws Exception {
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty old =
                ops.getProperties().get(com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class);
        if (old != null) {
            ops.getProperties().remove(old);
        }
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty property =
                new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(
                        new HashMap<Permission, Set<String>>());
        property.add(Item.READ, PermissionEntry.user("fr"));
        if (withRequest) {
            property.add(BatchControlPermissions.REQUEST, PermissionEntry.user("fr"));
        }
        ops.addProperty(property);
    }

    private boolean can(String userId, Permission permission) {
        return inside.getACL().hasPermission2(User.getById(userId, true).impersonate2(), permission);
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private interface Call<T> {
        T call() throws Exception;
    }

    private static <T> T asUser(String userId, Call<T> call) throws Exception {
        try (ACLContext ignored = as(userId)) {
            return call.call();
        }
    }
}
