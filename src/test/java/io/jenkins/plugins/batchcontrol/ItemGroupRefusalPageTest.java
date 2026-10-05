package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.relocate.RelocationAction;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomAttr;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.records;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * spec-review-S6 m-2: refusal pages that cannot offer a permission window must not offer one.
 * SPEC item 8 (D-71): "no window lets its holder delete a folder, multibranch project or
 * organization folder" and "moving a folder, multibranch project or organization folder cannot be
 * authorised through permission windows"; SPEC section 6 usability: every refusal "tells the user
 * in plain words why and what to do instead" and "no link leads to a 404 or 403 page". So the
 * delete-veto refusal for an item group and the move refusal for a folder name no grant request
 * form ({@code batch-control/grants/new}, {@code grants/dialog}) and say that an administrator must
 * do it. Each row has a job twin whose refusal does link the form (T-08-124, T-SEC-72), so the
 * scan for links is shown to find one. Matrix rows T-08-143, T-08-144 (note 262); the move into
 * the Jenkins root is T-08-128.
 *
 * <p>Batch Control matrix strategy, change control on. u3 holds RequestGrant and a standing
 * Item/Delete (the delete veto's audience, as T-08-124); u1 holds RequestGrant, native Item/Move
 * and an approved CREATE window on {@code dest} (the move guard's audience, as T-SEC-72); a1
 * approves. The pages are read without JavaScript, from a browser (Accept text/html).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-71/D-59, docs/reports/spec-review-S6.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ItemGroupRefusalPageTest {

    static final String BROWSER_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
    static final Pattern ADMINISTRATOR = Pattern.compile("(?i)\\badministrator");
    static final Pattern DELETE = Pattern.compile("(?i)\\bdelet");
    /**
     * "request a Delete window", "ask for Item/Delete", "request a permission window for Delete"
     * and the like, unless negated: a suggestion to apply for what no window can give on an item
     * group (the T-08-128 pattern, for Delete).
     */
    static final Pattern SUGGESTS_DELETE_WINDOW = Pattern.compile(
            "(?i)(?<!\\b(?:not|cannot|can't|never)\\s{1,3})\\b(?:request|ask\\s+for|apply\\s+for)\\s+(?:a|an|the)?\\s*"
            + "(?:new\\s+|temporary\\s+)?(?:(?:item/)?delete\\b|(?:permission\\s+)?window\\b[^.]{0,40}\\bdelete\\b)");

    private JenkinsRule j;
    private Folder dest;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "u3", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u3"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        strategy.add(Item.DELETE, PermissionEntry.user("u3"));
        strategy.add(RelocationAction.RELOCATE, PermissionEntry.user("u1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        dest = j.jenkins.createProject(Folder.class, "dest");
    }

    /**
     * T-08-143 (m-2, delete veto): u3 (standing Item/Delete, RequestGrant, no window) deletes the
     * folder {@code team} (holding {@code team/j}) and the multibranch project {@code mb} from a
     * browser. Each is vetoed with 4xx and survives (with its child); the refusal says an
     * administrator must delete it, does not suggest requesting a Delete window, and nowhere (no
     * anchor, form action, data attribute or text) names {@code grants/new} or {@code grants/dialog}.
     * Guard: the same user's vetoed delete of the job {@code veto-u3} does reference
     * {@code grants/new?scopeFullName=veto-u3} (T-08-124), so the scan finds such a reference when
     * one is offered.
     */
    @Test
    public void t_08_143_deleteVetoForAnItemGroupOffersNoWindow() throws Exception {
        Folder team = j.jenkins.createProject(Folder.class, "team");
        team.createProject(FreeStyleProject.class, "j");
        WorkflowMultiBranchProject mb = j.jenkins.createProject(WorkflowMultiBranchProject.class, "mb");
        FreeStyleProject veto = j.createFreeStyleProject("veto-u3");

        for (Item group : new Item[] {team, mb}) {
            Page answer = browserPost("u3", group.getUrl() + "doDelete", List.of());
            int code = answer.getWebResponse().getStatusCode();
            assertTrue(code >= 400 && code < 500, "deleting " + group.getFullName() + " must be vetoed with 4xx, got HTTP " + code
                    + ": " + excerpt(UsabilityFixtures.text(answer)));
            assertNotNull(j.jenkins.getItemByFullName(group.getFullName()), group.getFullName() + " must survive the veto");
            String message = message(answer);
            UsabilityFixtures.assertPlainRefusal("the delete veto for " + group.getFullName(), message, ADMINISTRATOR);
            assertFalse(SUGGESTS_DELETE_WINDOW.matcher(message).find(),
                    "the veto for " + group.getFullName() + " must not suggest requesting a Delete window: " + excerpt(message));
            assertNoWindowReference(answer, "the delete veto for " + group.getFullName());
        }
        assertNotNull(j.jenkins.getItemByFullName("team/j"), "the folder's child must survive");

        Page jobAnswer = browserPost("u3", veto.getUrl() + "doDelete", List.of());
        assertTrue(jobAnswer.getWebResponse().getStatusCode() >= 400, "guard: the job's delete is vetoed too");
        assertTrue(references(jobAnswer).stream().anyMatch(r -> r.contains("grants/new?scopeFullName=veto-u3")),
                "guard: the job's veto references the prefilled grant request form; references: " + references(jobAnswer));
    }

    /**
     * T-08-144 (m-2, move guard): u1 (native Move, CREATE window on {@code dest}) moves the folder
     * {@code box} (holding {@code box/j}) into {@code dest} from a browser. The move is refused with
     * 403, nothing moves, and one GRANT_VIOLATION names u1 and {@code box}; the refusal names the
     * missing Delete, says an administrator must make the move, does not suggest requesting a
     * Delete window, and nowhere names {@code grants/new} or {@code grants/dialog}. Guard: u1's
     * refused move of the job {@code lone} (no Delete) into {@code dest} references
     * {@code grants/new?scopeFullName=lone} (T-SEC-72).
     */
    @Test
    public void t_08_144_folderMoveRefusalOffersNoWindow() throws Exception {
        String id = submitGrantOk(j, "u1", "dest", List.of("CREATE"), 30, "moves into dest", null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval of the CREATE window on dest");
        Folder box = j.jenkins.createProject(Folder.class, "box");
        box.createProject(FreeStyleProject.class, "j");
        FreeStyleProject lone = j.createFreeStyleProject("lone");
        int violationsBefore = records(ChangeType.GRANT_VIOLATION).size();

        Page answer = browserPost("u1", box.getUrl() + "move/move", List.of(new NameValuePair("destination", "/dest")));
        assertEquals(403, answer.getWebResponse().getStatusCode(), "the folder move must be refused with 403: "
                + excerpt(UsabilityFixtures.text(answer)));
        assertNotNull(j.jenkins.getItemByFullName("box/j"), "the folder and its child stay in place");
        assertNull(j.jenkins.getItemByFullName("dest/box"), "nothing arrives in dest");
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(violationsBefore + 1, violations.size(), "the refused move is recorded once as GRANT_VIOLATION, got " + violations);
        ChangeRecord rec = violations.get(violations.size() - 1);
        assertEquals("u1", rec.getUser(), "the GRANT_VIOLATION names u1");
        assertTrue((rec.getTarget() + " " + rec.getDetail()).contains("box"), "the GRANT_VIOLATION names box: "
                + rec.getTarget() + " / " + rec.getDetail());

        String message = message(answer);
        UsabilityFixtures.assertPlainRefusal("the folder move refusal", message, ADMINISTRATOR);
        assertTrue(DELETE.matcher(message).find(), "the refusal must name what is missing (Delete): " + excerpt(message));
        assertFalse(SUGGESTS_DELETE_WINDOW.matcher(message).find(), "the refusal must not suggest requesting a Delete window: "
                + excerpt(message));
        assertNoWindowReference(answer, "the folder move refusal");

        Page jobAnswer = browserPost("u1", lone.getUrl() + "move/move", List.of(new NameValuePair("destination", "/dest")));
        assertEquals(403, jobAnswer.getWebResponse().getStatusCode(), "guard: the job move without Delete is refused too");
        assertTrue(references(jobAnswer).stream().anyMatch(r -> r.contains("grants/new?scopeFullName=lone")),
                "guard: the job's move refusal references the prefilled grant request form; references: " + references(jobAnswer));
    }

    // ---------------------------------------------------------------- helpers

    private Page browserPost(String user, String path, List<NameValuePair> params) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, user);
        wc.getOptions().setJavaScriptEnabled(false);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(path), HttpMethod.POST);
        request.setAdditionalHeader("Accept", BROWSER_ACCEPT);
        request.setRequestParameters(new ArrayList<>(params));
        return wc.getPage(request);
    }

    /** The refusal's message: the main panel of an HTML page, otherwise the body without tags. */
    private static String message(Page answer) {
        if (answer instanceof HtmlPage html) {
            org.htmlunit.html.DomNode main = html.querySelector("#main-panel");
            return main != null ? main.asNormalizedText() : html.asNormalizedText();
        }
        return answer.getWebResponse().getContentAsString().replaceAll("<[^>]+>", " ");
    }

    /** No href, action, formaction or data-* attribute and no text of the refusal names the grant request form. */
    private static void assertNoWindowReference(Page answer, String what) {
        for (String reference : references(answer)) {
            assertFalse(reference.contains("grants/new") || reference.contains("grants/dialog"),
                    what + " must not link or name a grant request form, found " + excerpt(reference));
        }
    }

    /**
     * Every href, action, formaction and data-* value of the refusal, plus its visible text. The
     * refusal is the page's main panel when it has one (the standard layout's side panel and
     * breadcrumbs belong to the item, as in T-SEC-64/72), otherwise the whole answer (entities
     * decoded).
     */
    private static List<String> references(Page answer) {
        List<String> out = new ArrayList<>();
        if (answer instanceof HtmlPage html) {
            DomElement scope = html.getElementById("main-panel");
            if (scope == null) {
                scope = html.getDocumentElement();
            }
            List<DomElement> elements = new ArrayList<>();
            elements.add(scope);
            scope.getHtmlElementDescendants().forEach(elements::add);
            for (DomElement e : elements) {
                for (DomAttr attr : e.getAttributesMap().values()) {
                    String name = attr.getName();
                    if ("href".equals(name) || "action".equals(name) || "formaction".equals(name) || name.startsWith("data-")) {
                        out.add(attr.getValue());
                    }
                }
            }
            out.add(scope.asNormalizedText());
        } else {
            out.add(answer.getWebResponse().getContentAsString().replace("&amp;", "&"));
        }
        return out;
    }
}
