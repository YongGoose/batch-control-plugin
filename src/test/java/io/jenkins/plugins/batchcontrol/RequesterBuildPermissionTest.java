package io.jenkins.plugins.batchcontrol;

import io.jenkins.plugins.batchcontrol.store.BatchClock;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 5, D-38 (#24): submitting a run request — the job request form, the incident
 * rerun and the service API — needs {@code Item/Build} on the job as the requester, on top of
 * {@code BatchControl/Request} and {@code Item/Read}. Without it the submission is refused with
 * 403 and no request is stored. Matrix rows T-05-07 .. T-05-13 (note 70).
 *
 * <p>Actors: {@code nb} holds Overall/Read, Item/Read and Request but <b>no</b> Item/Build;
 * {@code wb} holds the same plus Item/Build (the false-positive guard); {@code ob} holds
 * Item/Build on {@code other-x} only (the check is per job); {@code a1} is the approver.
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class RequesterBuildPermissionTest {

    private JenkinsRule j;
    private FreeStyleProject job;
    private FreeStyleProject otherJob;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());

        job = j.createFreeStyleProject("batch-x");
        otherJob = j.createFreeStyleProject("other-x");

        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.VIEW_HISTORY).everywhere().to("nb", "wb", "ob")
                .grant(Item.BUILD).everywhere().to("wb")
                .grant(Item.BUILD).onItems(otherJob).to("ob")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to("a1"));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        setBatchControl(job, new BatchControlJobProperty(true));
        setBatchControl(otherJob, new BatchControlJobProperty(true));

        // the premise of every row, asserted rather than assumed
        assertFalse(can("nb", job, Item.BUILD), "fixture: nb must NOT hold Item/Build on batch-x");
        assertTrue(can("nb", job, Item.READ), "fixture: nb must hold Item/Read on batch-x");
        assertTrue(can("nb", job, BatchControlPermissions.REQUEST), "fixture: nb must hold BatchControl/Request");
        assertTrue(can("wb", job, Item.BUILD), "fixture: wb must hold Item/Build on batch-x");
        assertFalse(can("ob", job, Item.BUILD), "fixture: ob must NOT hold Item/Build on batch-x");
        assertTrue(can("ob", otherJob, Item.BUILD), "fixture: ob must hold Item/Build on other-x");
    }

    /** T-05-07: POST job/batch-x/batch-control/submit by a requester without Item/Build -> 403, nothing stored, no build. */
    @Test
    public void t_05_07_submitWithoutItemBuildIs403AndStoresNothing() throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        int nextBuildNumber = job.getNextBuildNumber();

        WebResponse response = ApproverFormFixtures.submitRun(j, "nb", job, "month-end batch", "a1");

        assertEquals(403, response.getStatusCode(), "a requester without Item/Build on the job must be refused with 403 (D-38, #24)");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "the refused submission must store no run request");
        assertNotRun(job, nextBuildNumber);
    }

    /** T-05-08 (guard of T-05-07): the same POST by a requester holding Item/Build stores one PENDING request. */
    @Test
    public void t_05_08_submitWithItemBuildStoresPendingRequest() throws Exception {
        String id = ApproverFormFixtures.submitRunOk(j, "wb", job, "month-end batch", "a1");

        RunRequest stored = RunRequestService.get().load(id);
        assertNotNull(stored, "the accepted submission must be stored");
        assertEquals(RequestStatus.PENDING, stored.getStatus());
        assertEquals("wb", stored.getRequester());
        assertEquals("batch-x", stored.getJobFullName());
    }

    /** T-05-09: POST batch-control/incidents/&lt;id&gt;/rerun by a requester without Item/Build -> 403, nothing stored or linked. */
    @Test
    public void t_05_09_incidentRerunWithoutItemBuildIs403() throws Exception {
        Incident incident = failingJobIncident("inc-x");
        Set<String> before = ApproverFormFixtures.runRequestIds();
        FreeStyleProject incJob = j.jenkins.getItemByFullName("inc-x", FreeStyleProject.class);
        int nextBuildNumber = incJob.getNextBuildNumber();

        WebResponse response = postRerun("nb", incident);

        assertEquals(403, response.getStatusCode(), "an incident rerun by a requester without Item/Build must be refused with 403 (D-38, #24)");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "the refused rerun must store no run request");
        Incident reloaded = IncidentService.get().load(incident.getId());
        assertTrue(reloaded.getRerunRequestIds() == null || reloaded.getRerunRequestIds().isEmpty(), "no rerun request id may be linked to the incident");
        assertNotRun(incJob, nextBuildNumber);
    }

    /** T-05-10 (guard of T-05-09): the same rerun POST by a requester holding Item/Build creates one linked request. */
    @Test
    public void t_05_10_incidentRerunWithItemBuildCreatesLinkedRequest() throws Exception {
        Incident incident = failingJobIncident("inc-x");
        Set<String> before = ApproverFormFixtures.runRequestIds();

        WebResponse response = postRerun("wb", incident);

        ApproverFormFixtures.assertSuccess(response, "the rerun POST by a Build holder");
        Set<String> created = ApproverFormFixtures.runRequestIds();
        created.removeAll(before);
        assertEquals(1, created.size(), "exactly one rerun request must have been created, got " + created);
        String id = created.iterator().next();
        RunRequest rerun = RunRequestService.get().load(id);
        assertEquals("wb", rerun.getRequester());
        assertEquals(RequestStatus.PENDING, rerun.getStatus());
        assertEquals(incident.getId(), rerun.getIncidentId(), "the rerun request must link back to the incident");
        assertTrue(IncidentService.get().load(incident.getId()).getRerunRequestIds().contains(id), "the incident must list the rerun request");
    }

    /** T-05-11: RunRequestService.create as a requester without Item/Build is refused (AccessDenied) and stores nothing; with Build it succeeds. */
    @Test
    public void t_05_11_serviceCreateRequiresItemBuild() throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        int nextBuildNumber = job.getNextBuildNumber();

        boolean denied = false;
        try (ACLContext ignored = as("nb")) {
            RunRequestService.get().create(job, new LinkedHashMap<>(), "month-end batch", "a1");
        } catch (AccessDeniedException expected) {
            denied = true;
        }
        assertTrue(denied, "the service API must refuse a requester without Item/Build with an AccessDeniedException (403 family, D-38)");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "the refused creation must store no run request");
        assertNotRun(job, nextBuildNumber);

        // guard: the Build holder goes through the same API
        RunRequest ok;
        try (ACLContext ignored = as("wb")) {
            ok = RunRequestService.get().create(job, new LinkedHashMap<>(), "month-end batch", "a1");
        }
        assertNotNull(ok);
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(ok.getId()).getStatus());
    }

    /** T-05-12: IncidentService.rerun as a requester without Item/Build is refused (AccessDenied); with Build it succeeds. */
    @Test
    public void t_05_12_serviceRerunRequiresItemBuild() throws Exception {
        Incident incident = failingJobIncident("inc-x");
        Set<String> before = ApproverFormFixtures.runRequestIds();

        boolean denied = false;
        try (ACLContext ignored = as("nb")) {
            IncidentService.get().rerun(incident.getId(), "a1");
        } catch (AccessDeniedException expected) {
            denied = true;
        }
        assertTrue(denied, "the incident rerun service must refuse a requester without Item/Build with an AccessDeniedException (D-38)");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "the refused rerun must store no run request");
        Incident reloaded = IncidentService.get().load(incident.getId());
        assertTrue(reloaded.getRerunRequestIds() == null || reloaded.getRerunRequestIds().isEmpty(), "no rerun request id may be linked to the incident");

        RunRequest ok;
        try (ACLContext ignored = as("wb")) {
            ok = IncidentService.get().rerun(incident.getId(), "a1");
        }
        assertNotNull(ok, "guard: a Build holder's rerun must be created");
        assertEquals(incident.getId(), ok.getIncidentId());
    }

    /** T-05-13: Item/Build on another job does not count: submit on batch-x -> 403; the same user on other-x succeeds. */
    @Test
    public void t_05_13_itemBuildIsCheckedOnTheRequestedJob() throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        int nextBuildNumber = job.getNextBuildNumber();

        WebResponse refused = ApproverFormFixtures.submitRun(j, "ob", job, "month-end batch", "a1");
        assertEquals(403, refused.getStatusCode(), "Item/Build on a different job must not satisfy the check on batch-x (D-38)");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "the refused submission must store no run request");
        assertNotRun(job, nextBuildNumber);

        String id = ApproverFormFixtures.submitRunOk(j, "ob", otherJob, "month-end batch", "a1");
        assertEquals("other-x", RunRequestService.get().load(id).getJobFullName());
    }

    // ---------------------------------------------------------------- helpers

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private static boolean can(String userId, Item item, hudson.security.Permission permission) {
        return item.getACL().hasPermission2(User.getById(userId, true).impersonate2(), permission);
    }

    /** Runs a failing build of an uncontrolled job, then puts the job under approval; returns its incident. */
    private Incident failingJobIncident(String name) throws Exception {
        FreeStyleProject failing = uncontrolled(j.createFreeStyleProject(name));
        failing.getBuildersList().add(new FailureBuilder());
        BatchControlFixtures.activateAsAdmin(failing); // D-46: a cause-less submission needs an activation (note 109)
        j.assertBuildStatus(Result.FAILURE, failing.scheduleBuild2(0));
        j.waitUntilNoActivity();
        failing.getBuildersList().clear();
        setBatchControl(failing, new BatchControlJobProperty(true));
        Incident incident = IncidentService.get().list(YearMonth.now(BatchClock.clock())).stream()
                .filter(i -> (name + "#1").equals(i.getRunId()))
                .findFirst().orElse(null);
        assertNotNull(incident, "fixture: the FAILURE must have opened an incident");
        return incident;
    }

    /** The rerun POST; the approver is sent under both the D-37 field name and the older singular one. */
    private WebResponse postRerun(String userId, Incident incident) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("approvers", "a1"));
        params.add(new NameValuePair("approver", "a1"));
        params.add(new NameValuePair("reason", "rerun after the fix"));
        return ApproverFormFixtures.post(j, userId,
                "batch-control/incidents/" + incident.getId() + "/rerun", params);
    }

    /** The standard blocking triple: empty queue, unchanged next build number, no new build. */
    private void assertNotRun(FreeStyleProject target, int nextBuildNumberBefore) throws Exception {
        j.waitUntilNoActivity();
        assertEquals(0, j.jenkins.getQueue().getItems().length, "the queue must be empty");
        assertEquals(nextBuildNumberBefore, target.getNextBuildNumber(), "no build number may have been consumed");
        assertEquals(nextBuildNumberBefore - 1, target.getBuilds().size(), "no new build may exist");
    }
}
