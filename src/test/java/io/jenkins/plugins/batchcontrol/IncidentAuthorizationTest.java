package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.IncidentStatus;
import io.jenkins.plugins.batchcontrol.model.IncidentTransition;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 11: "acknowledging, resolving and commenting on an incident require
 * {@code BatchControl/ViewHistory} (the permission that opens the Incidents screen); each is a
 * POST with a crumb; a user without it gets 403 and nothing changes." With SPEC item 2 (#31): a
 * user who holds none of the Batch Control permissions gets 404 for every URL beneath
 * {@code /batch-control/}. Coverage inventory G-H6; matrix rows T-11-26 .. T-11-29 (note 269).
 *
 * <p>Endpoints, as the incident page's own forms name them: {@code POST
 * batch-control/incidents/<id>/acknowledge} (optional {@code comment}), {@code .../resolve}
 * (optional {@code comment}, on an ACKNOWLEDGED incident) and {@code .../comment} (required
 * {@code comment}). Every refusal is checked on the stored incident: status and the transition
 * list must be exactly as before.
 *
 * <p>Users: {@code u1} (Item/Read, BatchControl/Request only), {@code a1} (Item/Read,
 * BatchControl/Approve only), {@code nobc} (Item/Read, Item/Build, no Batch Control permission),
 * {@code viewer} (Item/Read, BatchControl/ViewHistory), anonymous (Overall/Read and Item/Read
 * granted to anonymous), {@code admin}.
 *
 * <p>Written from docs/SPEC.md items 2 and 11 and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class IncidentAuthorizationTest {

    private JenkinsRule j;
    private Incident incident;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ, Item.BUILD).everywhere().to("nobc")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer")
                .grant(Jenkins.READ, Item.READ).everywhere().to("anonymous"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(List.of("a1"));
        cfg.save();

        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("inc-auth"));
        job.getBuildersList().add(new FailureBuilder());
        BatchControlFixtures.activateAsAdmin(job); // D-46: the cause-less fixture build needs an activation
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        j.waitUntilNoActivity();
        incident = RerunFallbackFixtures.incidentFor("inc-auth#1");
        assertEquals(IncidentStatus.OPEN, incident.getStatus(), "fixture: the incident starts OPEN");
    }

    /**
     * T-11-26 (G-H6): u1 (Request only) and a1 (Approve only) hold a Batch Control permission but
     * not ViewHistory. Their acknowledge and comment POSTs on the OPEN incident, and (after the
     * viewer acknowledged it) their resolve POSTs, each answer 403; the incident's status and
     * transitions are unchanged after every attempt.
     */
    @Test
    public void t_11_26_requestOrApproveOnlyUsersGet403AndNothingChanges() throws Exception {
        for (String user : new String[] {"u1", "a1"}) {
            assertRefusedWith(403, user, "acknowledge", "taking it", IncidentStatus.OPEN);
            assertRefusedWith(403, user, "comment", "a note", IncidentStatus.OPEN);
        }
        acknowledgeAsViewer();
        for (String user : new String[] {"u1", "a1"}) {
            assertRefusedWith(403, user, "resolve", "fixed it", IncidentStatus.ACKNOWLEDGED);
            assertRefusedWith(403, user, "comment", "another note", IncidentStatus.ACKNOWLEDGED);
        }
    }

    /**
     * T-11-27 (G-H6, SPEC item 2 #31): nobc (no Batch Control permission) and anonymous (Overall/Read
     * only) get 404 for the acknowledge, comment and resolve POSTs, whatever the incident's state;
     * nothing changes.
     */
    @Test
    public void t_11_27_usersWithoutAnyBatchControlPermissionGet404AndNothingChanges() throws Exception {
        for (String user : new String[] {"nobc", null}) {
            assertRefusedWith(404, user, "acknowledge", "taking it", IncidentStatus.OPEN);
            assertRefusedWith(404, user, "comment", "a note", IncidentStatus.OPEN);
        }
        acknowledgeAsViewer();
        for (String user : new String[] {"nobc", null}) {
            assertRefusedWith(404, user, "resolve", "fixed it", IncidentStatus.ACKNOWLEDGED);
        }
    }

    /**
     * T-11-28 (G-H6, positive): the viewer (ViewHistory) acknowledges ("on it"), comments ("log
     * attached") and resolves ("root cause fixed") through the POST endpoints with a crumb; each
     * answer is below 400; the incident ends RESOLVED and records each step with the viewer and the
     * comment, in order.
     */
    @Test
    public void t_11_28_viewHistoryHolderAcknowledgesCommentsAndResolves() throws Exception {
        int start = transitions().size();
        assertAccepted(post("viewer", "acknowledge", "on it", true), "the viewer's acknowledge");
        assertEquals(IncidentStatus.ACKNOWLEDGED, reload().getStatus());
        assertAccepted(post("viewer", "comment", "log attached", true), "the viewer's comment");
        assertEquals(IncidentStatus.ACKNOWLEDGED, reload().getStatus(), "a comment does not change the status");
        assertAccepted(post("viewer", "resolve", "root cause fixed", true), "the viewer's resolve");
        assertEquals(IncidentStatus.RESOLVED, reload().getStatus());

        List<IncidentTransition> all = transitions();
        assertEquals(start + 3, all.size(), "each step must be recorded once: " + describe(all));
        IncidentTransition ack = all.get(start);
        assertEquals(IncidentStatus.ACKNOWLEDGED, ack.getStatus());
        assertEquals("viewer", ack.getBy());
        assertEquals("on it", ack.getComment());
        assertNotNull(ack.getAt(), "the transition carries its time");
        IncidentTransition note = all.get(start + 1);
        assertEquals("viewer", note.getBy());
        assertEquals("log attached", note.getComment());
        IncidentTransition resolved = all.get(start + 2);
        assertEquals(IncidentStatus.RESOLVED, resolved.getStatus());
        assertEquals("viewer", resolved.getBy());
        assertEquals("root cause fixed", resolved.getComment());
    }

    /**
     * T-11-29 (G-H6, CSRF): the viewer's acknowledge and comment POSTs without a crumb answer 403
     * and change nothing; the same POSTs with the crumb are accepted (positive twin).
     */
    @Test
    public void t_11_29_postsWithoutACrumbAreRefused() throws Exception {
        List<IncidentTransition> before = transitions();
        WebResponse ack = post("viewer", "acknowledge", "no crumb", false);
        assertEquals(403, ack.getStatusCode(), "an acknowledge without a crumb must be refused");
        WebResponse comment = post("viewer", "comment", "no crumb", false);
        assertEquals(403, comment.getStatusCode(), "a comment without a crumb must be refused");
        assertEquals(IncidentStatus.OPEN, reload().getStatus(), "nothing may change without a crumb");
        assertEquals(describe(before), describe(transitions()), "no transition may be recorded without a crumb");

        assertAccepted(post("viewer", "acknowledge", "with crumb", true), "guard: the same acknowledge with the crumb");
        assertEquals(IncidentStatus.ACKNOWLEDGED, reload().getStatus());
    }

    // ---------------------------------------------------------------- helpers

    private void acknowledgeAsViewer() {
        try (ACLContext ignored = ACL.as2(User.getById("viewer", true).impersonate2())) {
            IncidentService.get().acknowledge(incident.getId(), "fixture: acknowledged by the viewer");
        }
        assertEquals(IncidentStatus.ACKNOWLEDGED, reload().getStatus(), "fixture: the incident is ACKNOWLEDGED");
    }

    private void assertRefusedWith(int expected, String user, String action, String comment, IncidentStatus status) throws Exception {
        List<IncidentTransition> before = transitions();
        WebResponse response = post(user, action, comment, true);
        String who = user == null ? "anonymous" : user;
        assertEquals(expected, response.getStatusCode(), who + "'s " + action + " POST must answer " + expected + ": "
                + UsabilityFixtures.excerpt(response.getContentAsString()));
        Incident after = reload();
        assertEquals(status, after.getStatus(), who + "'s refused " + action + " must not change the status");
        assertEquals(describe(before), describe(transitions()), who + "'s refused " + action + " must record nothing");
    }

    private WebResponse post(String user, String action, String comment, boolean withCrumb) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, user);
        String path = "batch-control/incidents/" + incident.getId() + "/" + action;
        URL url = withCrumb ? wc.createCrumbedUrl(path) : new URL(j.getURL(), path);
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("comment", comment));
        request.setRequestParameters(params);
        return wc.getPage(request).getWebResponse();
    }

    private static void assertAccepted(WebResponse response, String what) {
        assertTrue(response.getStatusCode() < 400, what + " must be accepted, got HTTP " + response.getStatusCode() + ": "
                + UsabilityFixtures.excerpt(response.getContentAsString()));
    }

    private Incident reload() {
        Incident reloaded = IncidentService.get().load(incident.getId());
        assertNotNull(reloaded, "the incident must still load");
        return reloaded;
    }

    private List<IncidentTransition> transitions() {
        List<IncidentTransition> list = reload().getTransitions();
        return list == null ? List.of() : new ArrayList<>(list);
    }

    private static String describe(List<IncidentTransition> transitions) {
        StringBuilder sb = new StringBuilder();
        for (IncidentTransition t : transitions) {
            sb.append('[').append(t.getStatus()).append(' ').append(t.getBy()).append(' ').append(t.getComment()).append(' ')
                    .append(t.getAt()).append(']');
        }
        return sb.toString();
    }
}
