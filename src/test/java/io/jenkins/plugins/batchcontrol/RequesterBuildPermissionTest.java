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
import org.junit.jupiter.api.Tag;
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
 * SPEC item 5, D-38a (amends D-38, #24) and D-57: submitting a run request — the job request
 * form, the incident rerun and the service API — needs {@code BatchControl/Request} and
 * {@code Item/Read} on the job; {@code Item/Build} is not required. An incident rerun also needs
 * {@code BatchControl/ViewHistory}. Without Request or Item/Read (or ViewHistory for a rerun) the
 * submission is refused and nothing is stored. Matrix rows T-05-07 .. T-05-13 (rewritten for
 * D-38a, note 184).
 *
 * <p>Actors: {@code nb} holds Overall/Read, Item/Read, Request and ViewHistory but <b>no</b>
 * Item/Build; {@code wb} holds the same plus Item/Build; {@code nr} holds Item/Read, Item/Build
 * and ViewHistory but no Request; {@code nrd} holds Request, Item/Build and ViewHistory but no
 * Item/Read; {@code nv} holds Item/Read, Item/Build and Request but no ViewHistory; {@code ob}
 * holds Request on {@code other-x} only (the check is per job); {@code a1} is the approver.
 *
 * Written from docs/SPEC.md, docs/DECISIONS.md D-38a/D-57 and docs/TEST-MATRIX.md only (no
 * src/main knowledge).
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
                        BatchControlPermissions.VIEW_HISTORY).everywhere().to("nb", "wb")
                .grant(Item.BUILD).everywhere().to("wb")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.VIEW_HISTORY).everywhere().to("nr")
                .grant(Jenkins.READ, Item.BUILD, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.VIEW_HISTORY).everywhere().to("nrd")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("nv")
                .grant(Jenkins.READ, Item.READ).everywhere().to("ob")
                .grant(BatchControlPermissions.REQUEST).onItems(otherJob).to("ob")
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
        assertFalse(can("nr", job, BatchControlPermissions.REQUEST), "fixture: nr must NOT hold BatchControl/Request");
        assertTrue(can("nr", job, Item.BUILD), "fixture: nr holds Item/Build (it must not stand in for Request)");
        assertFalse(can("nrd", job, Item.READ), "fixture: nrd must NOT hold Item/Read on batch-x");
        assertTrue(can("nrd", job, BatchControlPermissions.REQUEST), "fixture: nrd holds BatchControl/Request");
        assertFalse(can("nv", j.jenkins, BatchControlPermissions.VIEW_HISTORY), "fixture: nv must NOT hold ViewHistory");
        assertFalse(can("ob", job, BatchControlPermissions.REQUEST), "fixture: ob must NOT hold Request on batch-x");
        assertTrue(can("ob", otherJob, BatchControlPermissions.REQUEST), "fixture: ob must hold Request on other-x");
    }

    /**
     * T-05-07 (D-38a): POST job/batch-x/batch-control/submit by a requester holding Request and
     * Item/Read but no Item/Build stores exactly one PENDING request by nb; nothing is built
     * (the request awaits approval).
     */
    @Test
    public void t_05_07_submitWithoutItemBuildStoresPendingRequest() throws Exception {
        int nextBuildNumber = job.getNextBuildNumber();

        String id = ApproverFormFixtures.submitRunOk(j, "nb", job, "month-end batch", "a1");

        RunRequest stored = RunRequestService.get().load(id);
        assertNotNull(stored, "the accepted submission must be stored");
        assertEquals(RequestStatus.PENDING, stored.getStatus());
        assertEquals("nb", stored.getRequester());
        assertEquals("batch-x", stored.getJobFullName());
        assertNotRun(job, nextBuildNumber);
    }

    /**
     * T-05-08 (D-38a, guard of T-05-07): the same POST by a user holding Item/Build but no
     * Request, and by a user holding Request but no Item/Read, is refused (4xx: without Request
     * the per-job action is absent, T-02-09) and stores nothing; no build.
     */
    @Test
    @Tag("core")
    public void t_05_08_submitWithoutRequestOrItemReadIsRefused() throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        int nextBuildNumber = job.getNextBuildNumber();

        for (String userId : new String[] {"nr", "nrd"}) {
            WebResponse response = ApproverFormFixtures.submitRun(j, userId, job, "month-end batch", "a1");
            ApproverFormFixtures.assertClientError(response, "the run request submission by " + userId);
            assertEquals(before, ApproverFormFixtures.runRequestIds(), "the refused submission by " + userId + " must store nothing");
        }
        assertNotRun(job, nextBuildNumber);
    }

    /** T-05-09 (D-38a, D-57): POST batch-control/incidents/&lt;id&gt;/rerun by nb (Request, Item/Read, ViewHistory, no Build) creates one linked request. */
    @Test
    public void t_05_09_incidentRerunWithoutItemBuildCreatesLinkedRequest() throws Exception {
        Incident incident = failingJobIncident("inc-x");
        Set<String> before = ApproverFormFixtures.runRequestIds();
        FreeStyleProject incJob = j.jenkins.getItemByFullName("inc-x", FreeStyleProject.class);
        assertFalse(can("nb", incJob, Item.BUILD), "premise: nb holds no Item/Build on inc-x");
        int nextBuildNumber = incJob.getNextBuildNumber();

        WebResponse response = postRerun("nb", incident);

        ApproverFormFixtures.assertSuccess(response, "the rerun POST by a requester without Item/Build");
        Set<String> created = ApproverFormFixtures.runRequestIds();
        created.removeAll(before);
        assertEquals(1, created.size(), "exactly one rerun request must have been created, got " + created);
        String id = created.iterator().next();
        RunRequest rerun = RunRequestService.get().load(id);
        assertEquals("nb", rerun.getRequester());
        assertEquals(RequestStatus.PENDING, rerun.getStatus());
        assertEquals(incident.getId(), rerun.getIncidentId(), "the rerun request must link back to the incident");
        assertTrue(IncidentService.get().load(incident.getId()).getRerunRequestIds().contains(id), "the incident must list the rerun request");
        assertNotRun(incJob, nextBuildNumber);
    }

    /**
     * T-05-10 (D-38a, D-57, guard of T-05-09): the same rerun POST by nv (no ViewHistory), nr (no
     * Request) and nrd (no Item/Read) is refused with 403; nothing is stored or linked.
     */
    @Test
    @Tag("core")
    public void t_05_10_incidentRerunWithoutViewHistoryRequestOrReadIsRefused() throws Exception {
        Incident incident = failingJobIncident("inc-x");
        Set<String> before = ApproverFormFixtures.runRequestIds();
        FreeStyleProject incJob = j.jenkins.getItemByFullName("inc-x", FreeStyleProject.class);
        int nextBuildNumber = incJob.getNextBuildNumber();

        for (String userId : new String[] {"nv", "nr", "nrd"}) {
            WebResponse response = postRerun(userId, incident);
            assertEquals(403, response.getStatusCode(), "an incident rerun by " + userId + " must be refused with 403");
            assertEquals(before, ApproverFormFixtures.runRequestIds(), "the refused rerun by " + userId + " must store nothing");
        }
        Incident reloaded = IncidentService.get().load(incident.getId());
        assertTrue(reloaded.getRerunRequestIds() == null || reloaded.getRerunRequestIds().isEmpty(), "no rerun request id may be linked to the incident");
        assertNotRun(incJob, nextBuildNumber);
    }

    /**
     * T-05-11 (D-38a): RunRequestService.create as nb (no Item/Build) creates a PENDING request;
     * as nr (no Request) and as nrd (no Item/Read) it is refused with an AccessDeniedException
     * and stores nothing.
     */
    @Test
    @Tag("core")
    public void t_05_11_serviceCreateNeedsRequestAndReadNotBuild() throws Exception {
        int nextBuildNumber = job.getNextBuildNumber();

        RunRequest ok;
        try (ACLContext ignored = as("nb")) {
            ok = RunRequestService.get().create(job, new LinkedHashMap<>(), "month-end batch", "a1");
        }
        assertNotNull(ok, "the service API must accept a requester without Item/Build");
        RunRequest stored = RunRequestService.get().load(ok.getId());
        assertEquals(RequestStatus.PENDING, stored.getStatus());
        assertEquals("nb", stored.getRequester());

        Set<String> before = ApproverFormFixtures.runRequestIds();
        for (String userId : new String[] {"nr", "nrd"}) {
            boolean denied = false;
            try (ACLContext ignored = as(userId)) {
                RunRequestService.get().create(job, new LinkedHashMap<>(), "month-end batch", "a1");
            } catch (AccessDeniedException expected) {
                denied = true;
            }
            assertTrue(denied, "the service API must refuse " + userId + " with an AccessDeniedException");
            assertEquals(before, ApproverFormFixtures.runRequestIds(), "the refused creation by " + userId + " must store nothing");
        }
        assertNotRun(job, nextBuildNumber);
    }

    /**
     * T-05-12 (D-38a): IncidentService.rerun as nb (no Item/Build) creates a linked request; as
     * nr (no Request) and nrd (no Item/Read) it is refused (AccessDenied), nothing stored. The
     * D-57 ViewHistory requirement is pinned on the web rerun (T-05-10), where the Incidents
     * screen enforces it (note 184).
     */
    @Test
    public void t_05_12_serviceRerunNeedsRequestAndReadNotBuild() throws Exception {
        Incident incident = failingJobIncident("inc-x");

        Set<String> before = ApproverFormFixtures.runRequestIds();
        boolean denied = false;
        try (ACLContext ignored = as("nr")) {
            IncidentService.get().rerun(incident.getId(), "a1");
        } catch (AccessDeniedException expected) {
            denied = true;
        }
        assertTrue(denied, "the incident rerun service must refuse nr (no Request) with an AccessDeniedException");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "the refused rerun by nr must store nothing");

        // nrd cannot read the incident's job: to nrd the job does not exist, so the refusal may be
        // an AccessDeniedException or the service's "job no longer exists" refusal; either way
        // nothing is stored (note 184)
        boolean refused = false;
        try (ACLContext ignored = as("nrd")) {
            IncidentService.get().rerun(incident.getId(), "a1");
        } catch (AccessDeniedException | IllegalArgumentException expected) {
            refused = true;
        }
        assertTrue(refused, "the incident rerun service must refuse nrd (no Item/Read)");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "the refused rerun by nrd must store nothing");
        Incident reloaded = IncidentService.get().load(incident.getId());
        assertTrue(reloaded.getRerunRequestIds() == null || reloaded.getRerunRequestIds().isEmpty(), "no rerun request id may be linked to the incident");

        RunRequest ok;
        try (ACLContext ignored = as("nb")) {
            ok = IncidentService.get().rerun(incident.getId(), "a1");
        }
        assertNotNull(ok, "a rerun by a requester without Item/Build must be created");
        assertEquals("nb", ok.getRequester());
        assertEquals(incident.getId(), ok.getIncidentId());
    }

    /** T-05-13 (D-38a): Request on another job does not count: ob submits on batch-x -> refused, nothing stored; ob on other-x succeeds. */
    @Test
    @Tag("core")
    public void t_05_13_requestIsCheckedOnTheRequestedJob() throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        int nextBuildNumber = job.getNextBuildNumber();

        WebResponse refused = ApproverFormFixtures.submitRun(j, "ob", job, "month-end batch", "a1");
        ApproverFormFixtures.assertClientError(refused, "Request on a different job must not satisfy the check on batch-x");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "the refused submission must store no run request");
        assertNotRun(job, nextBuildNumber);

        String id = ApproverFormFixtures.submitRunOk(j, "ob", otherJob, "month-end batch", "a1");
        assertEquals("other-x", RunRequestService.get().load(id).getJobFullName());
        assertEquals("ob", RunRequestService.get().load(id).getRequester());
    }

    // ---------------------------------------------------------------- helpers

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private static boolean can(String userId, hudson.security.AccessControlled item, hudson.security.Permission permission) {
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
