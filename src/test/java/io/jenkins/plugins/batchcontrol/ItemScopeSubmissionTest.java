package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.matrix.MatrixProject;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import jenkins.branch.OrganizationFolder;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.client;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.grantRequestIds;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8 (D-71): "a request for CREATE or DELETE on an item kind where it cannot apply is
 * refused at submission". CREATE applies only to a modifiable item group that is not computed (a
 * regular folder: not a job, not a multibranch project, not an organization folder); DELETE
 * applies only to a job (an item that is a {@code Job}, including a multi-configuration project),
 * never to an item group that is not a job. The form has no scope type; a submitted
 * {@code scopeType} is ignored; a sub-item that is part of a job (a matrix configuration) is not
 * an item a window can name. Matrix rows T-08-104, T-08-105, T-08-108, T-08-110, T-08-113,
 * T-08-114, T-08-127 (note 260).
 *
 * <p>Each refusal is asserted twice: through the service ({@code GrantRequestService#create} throws
 * {@code IllegalArgumentException}, the existing failure family for invalid input) and through
 * the browser form ({@code POST batch-control/grants/create}: 4xx, the grant request form shown
 * again with the typed reason kept, no crash page). Nothing may be stored either way. Each row has
 * a guard showing that a request on the same item that does apply is accepted, so the refusal is
 * about the action and the kind, not about the item.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-71 and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class ItemScopeSubmissionTest {

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

        Folder ops = j.jenkins.createProject(Folder.class, "ops");
        ops.createProject(FreeStyleProject.class, "a");
        ops.createProject(Folder.class, "sub");
        ops.createProject(WorkflowMultiBranchProject.class, "mb");
        j.jenkins.createProject(OrganizationFolder.class, "org");
        j.jenkins.createProject(MatrixProject.class, "mx");
        j.createFreeStyleProject("other-job");
    }

    /**
     * T-08-104 (rewritten for D-71; was the D-65 "FOLDER_ONLY on a job" row): CREATE on the job
     * {@code ops/a} is refused at submission, alone and together with CONFIGURE, through the
     * service and the form; nothing is stored. Guard: CREATE on the folder {@code ops} is accepted.
     */
    @Test
    public void t_08_104_createOnAJobIsRefusedAtSubmission() throws Exception {
        assertServiceRefuses("ops/a", GrantAction.CREATE);
        assertServiceRefuses("ops/a", GrantAction.CREATE, GrantAction.CONFIGURE);
        assertFormRefuses("ops/a", "CREATE");

        String id = submitGrantOk(j, "u1", "ops", List.of("CREATE"), 30, "a new job in ops", null, "a1");
        assertNotNull(GrantRequestService.get().load(id), "guard: CREATE on the folder ops must be stored");
    }

    /**
     * T-08-105 (rewritten for D-71; was the D-65 FOLDER_ONLY form-binding row): the form binds the
     * item without a scope type. The stored request and the window its approval opens have scope
     * type ITEM, the folder's full name and the Folder kind; a {@code scopeType=FOLDER} submitted
     * anyway is ignored and the request is stored as ITEM all the same.
     */
    @Test
    public void t_08_105_formBindsTheItemWithoutAScopeType() throws Exception {
        String id = submitGrantOk(j, "u1", "ops", List.of("CONFIGURE"), 30, "form binding", null, "a1");
        GrantRequest request = GrantRequestService.get().load(id);
        assertEquals(GrantScope.Type.ITEM, request.getScope().getType());
        assertEquals("ops", request.getScope().getFullName());
        assertNotNull(request.getItemKind(), "the request must record the item's kind");
        assertEquals("com.cloudbees.hudson.plugins.folder.Folder", request.getItemKind().getDescriptorId());

        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "approval by a1");
        Grant grant = GrantService.get().listActive().stream()
                .filter(g -> "u1".equals(g.getUser())).findFirst().orElse(null);
        assertNotNull(grant, "the approval must open a window");
        assertEquals(GrantScope.Type.ITEM, grant.getScope().getType());
        assertEquals("ops", grant.getScope().getFullName());
        assertNotNull(grant.getItemKind(), "the window must carry the request's kind");
        assertEquals("com.cloudbees.hudson.plugins.folder.Folder", grant.getItemKind().getDescriptorId());

        Set<String> before = grantRequestIds();
        List<NameValuePair> params = formParams("ops", "a stale scope type", "CONFIGURE");
        params.add(new NameValuePair("scopeType", "FOLDER"));
        assertSuccess(ApproverFormFixtures.post(j, "u1", "batch-control/grants/create", params),
                "a submission that still carries scopeType must be accepted (the field is ignored)");
        Set<String> after = grantRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "exactly one request must be stored, got " + after);
        GrantRequest stale = GrantRequestService.get().load(after.iterator().next());
        assertEquals(GrantScope.Type.ITEM, stale.getScope().getType(), "a submitted scopeType must be ignored");
        assertEquals("ops", stale.getScope().getFullName());
    }

    /**
     * T-08-108 (rewritten for D-71; was the D-65 guard "a FOLDER DELETE window deletes a nested
     * folder", whose intent D-71 withdraws): DELETE on a folder, the top-level {@code ops} and the
     * nested {@code ops/sub}, alone and together with CONFIGURE, is refused at submission through
     * the service and the form; nothing is stored, so no window can delete a folder (core deletes a
     * folder's children as SYSTEM). Guard: DELETE on the job {@code ops/a} is accepted.
     */
    @Test
    public void t_08_108_deleteOnAFolderIsRefusedAtSubmission() throws Exception {
        assertServiceRefuses("ops", GrantAction.DELETE);
        assertServiceRefuses("ops/sub", GrantAction.DELETE);
        assertServiceRefuses("ops", GrantAction.CONFIGURE, GrantAction.DELETE);
        assertFormRefuses("ops/sub", "DELETE");

        String id = submitGrantOk(j, "u1", "ops/a", List.of("DELETE"), 30, "retire ops/a", null, "a1");
        assertNotNull(GrantRequestService.get().load(id), "guard: DELETE on the job ops/a must be stored");
    }

    /**
     * T-08-110 (rewritten for D-71; was the D-65 multibranch DELETE row): DELETE on the
     * multibranch project {@code ops/mb} and on the organization folder {@code org} is refused at
     * submission (both are item groups that are not jobs); nothing is stored. Guard: CONFIGURE on
     * the same multibranch project is accepted.
     */
    @Test
    public void t_08_110_deleteOnComputedFoldersIsRefusedAtSubmission() throws Exception {
        assertServiceRefuses("ops/mb", GrantAction.DELETE);
        assertServiceRefuses("org", GrantAction.DELETE);
        assertFormRefuses("ops/mb", "DELETE");

        String id = submitGrantOk(j, "u1", "ops/mb", List.of("CONFIGURE"), 30, "tune the branch sources", null, "a1");
        assertNotNull(GrantRequestService.get().load(id), "guard: CONFIGURE on the multibranch project must be stored");
    }

    /**
     * T-08-113 (D-71): CREATE on the multibranch project {@code ops/mb} and on the organization
     * folder {@code org} is refused at submission (computed folders create their children
     * themselves); nothing is stored. Guard: CONFIGURE on the organization folder is accepted.
     */
    @Test
    public void t_08_113_createOnComputedFoldersIsRefusedAtSubmission() throws Exception {
        assertServiceRefuses("ops/mb", GrantAction.CREATE);
        assertServiceRefuses("org", GrantAction.CREATE);
        assertFormRefuses("ops/mb", "CREATE");

        String id = submitGrantOk(j, "u1", "org", List.of("CONFIGURE"), 30, "tune the navigator", null, "a1");
        assertNotNull(GrantRequestService.get().load(id), "guard: CONFIGURE on the organization folder must be stored");
    }

    /**
     * T-08-114 (D-71): a multi-configuration project is a job, so DELETE on {@code mx} is accepted
     * and, once approved, u1 deletes it. Guards: before the approval u1's delete is refused, and
     * after it the window does not delete another job.
     */
    @Test
    public void t_08_114_deleteOnAMultiConfigurationProjectIsAllowed() throws Exception {
        MatrixProject mx = j.jenkins.getItemByFullName("mx", MatrixProject.class);
        assertNotNull(mx);
        String id = submitGrantOk(j, "u1", "mx", List.of("DELETE"), 30, "retire the matrix job", null, "a1");
        assertNotNull(GrantRequestService.get().load(id), "DELETE on a multi-configuration project must be stored");

        int early = postDelete("u1", mx);
        assertTrue(early >= 400 && early < 500, "guard: before the approval the delete must be refused, got HTTP " + early);
        assertNotNull(j.jenkins.getItemByFullName("mx"));

        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "approval by a1");
        int other = postDelete("u1", j.jenkins.getItemByFullName("other-job"));
        assertTrue(other >= 400 && other < 500, "guard: the window must not delete another job, got HTTP " + other);
        assertNotNull(j.jenkins.getItemByFullName("other-job"));

        int allowed = postDelete("u1", mx);
        assertTrue(allowed < 400, "the DELETE window must delete the multi-configuration project, got HTTP " + allowed);
        assertNull(j.jenkins.getItemByFullName("mx"), "mx must be deleted");
    }

    /**
     * T-08-127 (D-71): a window names a job or a folder, not a sub-item that is part of a job. A
     * request for CONFIGURE or DELETE on a configuration of the multi-configuration project
     * {@code mx} ({@code mx/X=a}, an item that is not top-level) is refused at submission through
     * the service and the form; nothing is stored. Guard: CONFIGURE on {@code mx} itself is
     * accepted. (Maven modules are the other such case; the Maven plugin is not a test
     * dependency, note 260.)
     */
    @Test
    public void t_08_127_subItemOfAJobIsRefusedAtSubmission() throws Exception {
        MatrixProject mx = j.jenkins.getItemByFullName("mx", MatrixProject.class);
        mx.setAxes(new hudson.matrix.AxisList(new hudson.matrix.TextAxis("X", "a", "b")));
        Item configuration = j.jenkins.getItemByFullName("mx/X=a");
        assertNotNull(configuration, "fixture: mx must have the configuration mx/X=a");
        assertFalse(configuration instanceof hudson.model.TopLevelItem, "fixture: a configuration is not a top-level item");

        assertServiceRefuses("mx/X=a", GrantAction.CONFIGURE);
        assertServiceRefuses("mx/X=a", GrantAction.DELETE);
        assertFormRefuses("mx/X=a", "CONFIGURE");

        String id = submitGrantOk(j, "u1", "mx", List.of("CONFIGURE"), 30, "tune the matrix job", null, "a1");
        assertNotNull(GrantRequestService.get().load(id), "guard: CONFIGURE on the project mx itself must be stored");
    }

    // ---------------------------------------------------------------- helpers

    /** The service refuses the request with IllegalArgumentException and stores nothing. */
    private void assertServiceRefuses(String fullName, GrantAction... actions) {
        int before = GrantRequestService.get().list().size();
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            assertThrows(IllegalArgumentException.class, () -> GrantRequestService.get().create(
                    new GrantScope(GrantScope.Type.ITEM, fullName), Arrays.asList(actions), 30, "kind check", "a1"),
                    "D-71: the service must refuse " + Arrays.toString(actions) + " on " + fullName);
        }
        assertEquals(before, GrantRequestService.get().list().size(), "nothing may be stored for " + fullName);
    }

    /**
     * The browser form refuses the request: 4xx, an HTML page that shows the grant request form
     * again with the typed reason kept, no crash page or stack trace; nothing is stored. The
     * wording of the message is not pinned (note 260).
     */
    private void assertFormRefuses(String fullName, String action) throws Exception {
        String typed = "typed reason for " + action + " on " + fullName;
        Set<String> before = grantRequestIds();
        JenkinsRule.WebClient wc = UsabilityFixtures.clientNoJs(j, "u1");
        WebRequest request = new WebRequest(wc.createCrumbedUrl("batch-control/grants/create"), HttpMethod.POST);
        request.setRequestParameters(formParams(fullName, typed, action));
        Page answer = wc.getPage(request);
        int code = answer.getWebResponse().getStatusCode();
        assertTrue(code >= 400 && code < 500, "D-71: the form must refuse " + action + " on " + fullName + " with 4xx, got HTTP "
                + code + ": " + excerpt(answer.getWebResponse().getContentAsString()));
        assertEquals(before, grantRequestIds(), "the refused form submission must store nothing");
        assertTrue(answer instanceof HtmlPage, "the refusal must be an HTML page, got " + answer.getWebResponse().getContentType());
        HtmlPage page = (HtmlPage) answer;
        UsabilityFixtures.assertNotBareErrorPage(action + " on " + fullName, page);
        UsabilityFixtures.assertPlainRefusal(action + " on " + fullName, page.asNormalizedText(), null);
        assertFalse(UsabilityFixtures.formsEndingWith(page, "batch-control/grants/create").isEmpty(),
                "the refusal must show the grant request form again; forms: " + UsabilityFixtures.formActions(page));
        assertTrue(UsabilityFixtures.pageKeepsValue(page, typed), "the reason the user typed must be kept: "
                + excerpt(page.asNormalizedText()));
    }

    private static List<NameValuePair> formParams(String fullName, String reason, String... actions) {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("scopeFullName", fullName));
        for (String action : actions) {
            params.add(new NameValuePair("actions", action));
        }
        params.add(new NameValuePair("durationMinutes", "30"));
        params.add(new NameValuePair("reason", reason));
        params.addAll(ApproverFormFixtures.approverPairs("a1"));
        return params;
    }

    private int postDelete(String userId, Item item) throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(item.getUrl() + "doDelete"), HttpMethod.POST);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }
}
