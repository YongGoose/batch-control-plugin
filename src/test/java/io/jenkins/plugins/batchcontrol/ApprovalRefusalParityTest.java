package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8 (D-71: "approval is refused when no item exists at that name any more") together
 * with T-SEC-18 (P-10: approval re-validates the scope as the approver, so an approver who cannot
 * see the item cannot approve a window on it) and SPEC item 2's rule that a hidden item is not
 * disclosed (the same "No such item" answer for a missing and an invisible item, T-08-120): the
 * two refusals of an approval look the same to the approver on the web page. Matrix row T-08-129
 * (note 261).
 *
 * <p>Fixture: two Freestyle jobs of the same kind, {@code hidden-x} and {@code gone-x}; u1
 * requests the same CONFIGURE window on each (same reason, duration and approver); the designated
 * approver a1 holds Approve and Item/Read on {@code gone-x} only. The administrator then deletes
 * {@code gone-x}, so at decision time one item exists but is invisible to a1, the other no longer
 * exists. a1 approves each through the web endpoint {@code POST batch-control/grants/<id>/approve}
 * as a browser does. The two answers must have the same HTTP status and, once the item name, the
 * request id and digits (times) are masked, the same message: the page text minus the text of the
 * request's own detail page fetched by a1 just before (so the message is isolated whether it is
 * shown on the detail page or on a page of its own), and the main panel as a whole. Both requests
 * stay PENDING and no window opens. The service still tells the two apart by type, as T-SEC-18
 * (IllegalArgumentException) and T-08-117 (IllegalStateException) pin.
 *
 * <p>Written from docs/SPEC.md items 2 and 8, docs/DECISIONS.md D-71 and P-10 and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ApprovalRefusalParityTest {

    private static final String HIDDEN = "hidden-x";
    private static final String GONE = "gone-x";
    private static final String REASON = "parity check maintenance";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        FreeStyleProject hidden = j.createFreeStyleProject(HIDDEN);
        FreeStyleProject gone = j.createFreeStyleProject(GONE);
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST_GRANT).everywhere().to("u1")
                .grant(Jenkins.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                // a1 could see gone-x before it was deleted, never hidden-x
                .grant(Item.READ).onItems(gone).to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        assertNotNull(hidden, "fixture: hidden-x exists");
    }

    /**
     * T-08-129 (D-71, T-SEC-18, note 261): approving a request whose item exists but is invisible
     * to the approver and approving a request whose item was deleted answer the same status and
     * the same message on the web page; both stay PENDING without a window. Guard: the service
     * refuses the first with IllegalArgumentException and the second with IllegalStateException.
     */
    @Test
    public void t_08_129_approvalRefusalSaysTheSameForAnInvisibleAndADeletedItem() throws Exception {
        GrantRequest onHidden = request(HIDDEN);
        GrantRequest onGone = request(GONE);
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator deletes gone-x
            j.jenkins.getItemByFullName(GONE).delete();
        }
        assertNull(j.jenkins.getItemByFullName(GONE), "fixture: gone-x no longer exists");
        assertNotNull(j.jenkins.getItemByFullName(HIDDEN), "fixture: hidden-x still exists");
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            assertNull(Jenkins.get().getItemByFullName(HIDDEN), "premise: a1 cannot see hidden-x");
        }

        Answer hidden = approveOnTheWeb(onHidden, HIDDEN);
        Answer gone = approveOnTheWeb(onGone, GONE);

        for (Answer a : List.of(hidden, gone)) {
            assertTrue(a.status >= 400 && a.status < 500,
                    "the refused approval on " + a.item + " must answer 4xx, got HTTP " + a.status + ": " + excerpt(a.page));
            assertFalse(a.message.isEmpty(), "the refused approval on " + a.item + " must show a message: " + excerpt(a.page));
        }
        assertEquals(hidden.status, gone.status, "the invisible and the deleted item must answer the same HTTP status");
        assertEquals(hidden.message, gone.message, "the refusal message must not tell an invisible item from a deleted one");
        assertEquals(hidden.main, gone.main, "the refusal page must not tell an invisible item from a deleted one");

        assertStillPendingWithoutWindow(onHidden, HIDDEN);
        assertStillPendingWithoutWindow(onGone, GONE);

        // guard: the service keeps the two refusals apart by type (T-SEC-18, T-08-117)
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            assertThrows(IllegalArgumentException.class, () -> GrantRequestService.get().approve(onHidden.getId(), "ok"),
                    "T-SEC-18: an item the approver cannot see is refused as an invalid scope");
            assertThrows(IllegalStateException.class, () -> GrantRequestService.get().approve(onGone.getId(), "ok"),
                    "T-08-117: a deleted item is refused as a changed world");
        }
        assertStillPendingWithoutWindow(onHidden, HIDDEN);
        assertStillPendingWithoutWindow(onGone, GONE);
    }

    // ---------------------------------------------------------------- helpers

    /** What a1 sees after approving one request, masked for comparison. */
    private record Answer(String item, int status, String message, String main, String page) {
    }

    private Answer approveOnTheWeb(GrantRequest request, String item) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.login("a1");
        wc.getOptions().setJavaScriptEnabled(false);
        Page detail = wc.getPage(new java.net.URL(j.getURL(), "batch-control/grants/" + request.getId() + "/"));
        assertEquals(200, detail.getWebResponse().getStatusCode(),
                "premise: the designated approver opens the request on " + item + ": " + excerpt(detail.getWebResponse().getContentAsString()));
        Set<String> before = lines(text(detail), request, item);

        WebRequest post = new WebRequest(wc.createCrumbedUrl("batch-control/grants/" + request.getId() + "/approve"), HttpMethod.POST);
        post.setAdditionalHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        post.setRequestParameters(new ArrayList<>(List.of(new NameValuePair("comment", "ok"))));
        Page answer = wc.getPage(post);
        String raw = answer.getWebResponse().getContentAsString();
        Set<String> after = lines(text(answer), request, item);
        after.removeAll(before);
        String main = answer instanceof HtmlPage html && html.querySelector("#main-panel") != null
                ? mask(((DomNode) html.querySelector("#main-panel")).asNormalizedText(), request, item)
                : mask(text(answer), request, item);
        return new Answer(item, answer.getWebResponse().getStatusCode(), String.join("\n", after), main, raw);
    }

    private static String text(Page page) {
        if (page instanceof HtmlPage html) {
            return html.asNormalizedText();
        }
        return page.getWebResponse().getContentAsString().replaceAll("<[^>]+>", " ");
    }

    private static Set<String> lines(String text, GrantRequest request, String item) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : mask(text, request, item).split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** Masks the request id, both fixture item names (the only intended differences) and digits (times). */
    private static String mask(String text, GrantRequest request, String item) {
        return text.replace(request.getId(), "<id>").replace(item, "<item>").replace(HIDDEN, "<item>").replace(GONE, "<item>")
                .replaceAll("\\d+", "#").replaceAll("[ \\t]+", " ").trim();
    }

    private static void assertStillPendingWithoutWindow(GrantRequest request, String item) {
        assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(request.getId()).getStatus(),
                "the refused approval must leave the request on " + item + " PENDING");
        assertTrue(GrantService.get().listActive().stream().noneMatch(g -> item.equals(g.getScope().getFullName())),
                "no window may be opened on " + item);
        assertFalse(GrantService.get().hasActiveGrant("u1", item, Item.CONFIGURE), "no Configure on " + item);
    }

    private static GrantRequest request(String fullName) {
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            return GrantRequestService.get().create(GrantScope.item(fullName),
                    Arrays.asList(GrantAction.CONFIGURE), 30, REASON, "a1");
        }
    }
}
