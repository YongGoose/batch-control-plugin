package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.relocate.RelocationAction;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
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
 * D-59 with D-48 (a browser form save shows the refusal "on a page with the standard layout"):
 * a move refused by change control, made from a browser, answers 403 with an HTML page in
 * Jenkins' standard layout, the message names the item as text (an item name with HTML-special
 * characters cannot inject markup), and nothing moves. Matrix row T-SEC-63 (note 192). The plain
 * answer for non-browser clients stays covered by T-SEC-53..56 (MoveChangeControlTest).
 *
 * <p>Fixture as T-SEC-53: u1 holds native Item/Move and an approved FOLDER {@code team}
 * [CREATE, CONFIGURE] grant, no Delete on the item. A link to the item on the refusal page is
 * allowed and not asserted either way.
 *
 * <p>Written from docs/SPEC.md item 8, docs/DECISIONS.md D-59/D-48 and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class MoveRefusalPageTest {

    /**
     * Allowed by Jenkins' item name check (which forbids {@code < > & ...}) but able to break out of
     * an HTML attribute if written unescaped.
     */
    static final String HOSTILE = "x\"onmouseover='alert(1)' data-injected=\"1";

    private JenkinsRule j;
    private Folder prod;
    private Folder team;

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
        strategy.add(RelocationAction.RELOCATE, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        prod = j.jenkins.createProject(Folder.class, "prod");
        team = j.jenkins.createProject(Folder.class, "team");
        prod.createProject(FreeStyleProject.class, HOSTILE);
        assertNotNull(prod.getItem(HOSTILE), "fixture: the item with the hostile name must exist");

        String id = submitGrantOk(j, "u1", "FOLDER", "team", Arrays.asList("CREATE", "CONFIGURE"), 30,
                "maintenance in team", null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        assertEquals(1, GrantService.get().listActive().stream().filter(g -> "u1".equals(g.getUser())).count(),
                "fixture: u1 holds one active grant");
    }

    /**
     * T-SEC-63: u1's browser POSTs the folders plugin's move form ({@code <item>/move/move},
     * {@code destination=/team}, {@code Accept: text/html}). The answer is 403, an HTML page with
     * core's page header and breadcrumbs (not the bare "Error" page), whose text names the item
     * verbatim and whose DOM gained no attribute from the name; the item stays in {@code prod},
     * nothing arrives in {@code team}, and one GRANT_VIOLATION is recorded.
     */
    @Test
    public void t_sec_63_refusedBrowserMoveRendersStandardLayoutWithEscapedName() throws Exception {
        Item item = prod.getItem(HOSTILE);
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "u1");
        wc.getOptions().setJavaScriptEnabled(false);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(item.getUrl() + "move/move"), HttpMethod.POST);
        request.setAdditionalHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        request.setRequestParameters(List.of(new NameValuePair("destination", "/team")));
        Page page = wc.getPage(request);
        String body = page.getWebResponse().getContentAsString();

        assertEquals(403, page.getWebResponse().getStatusCode(), "a refused browser move must answer 403: " + excerpt(body));
        assertTrue(page.getWebResponse().getContentType().startsWith("text/html"),
                "a browser gets an HTML page, got " + page.getWebResponse().getContentType());
        assertTrue(page instanceof HtmlPage, "the refusal must parse as an HTML page");
        HtmlPage html = (HtmlPage) page;
        UsabilityFixtures.assertNotBareErrorPage("refused move", html);
        assertNotNull(html.querySelector("#page-header"), "the refusal must have core's page header (standard layout): " + excerpt(body));
        assertFalse(html.querySelectorAll("li.jenkins-breadcrumbs__list-item").isEmpty(),
                "the refusal must render breadcrumbs (standard layout): " + excerpt(body));

        assertTrue(html.asNormalizedText().contains(HOSTILE),
                "the message must name the item as text, verbatim: " + excerpt(html.asNormalizedText()));
        for (HtmlElement e : html.getHtmlElementDescendants()) {
            assertFalse(e.hasAttribute("onmouseover") || e.hasAttribute("data-injected"),
                    "the item name must not inject attributes, found on <" + e.getTagName() + ">: " + excerpt(e.asXml()));
        }
        for (DomElement script : html.getElementsByTagName("script")) {
            assertFalse(script.getTextContent().contains("alert(1)"), "the item name must not reach a script");
        }

        assertNotNull(prod.getItem(HOSTILE), "the refused move must leave the item in prod");
        assertNull(team.getItem(HOSTILE), "nothing may arrive in team");
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(1, violations.size(), "the refused move is recorded once as GRANT_VIOLATION, got " + violations);
        assertEquals("u1", violations.get(0).getUser(), "the GRANT_VIOLATION names u1");
    }

    /**
     * T-SEC-64 (SPEC 8 D-59 line: "the refusal page links the item for a user who may read it"):
     * the browser refusal page of T-SEC-63 carries an anchor to the item's own page, which u1 may
     * read. A user who cannot read the item cannot reach its move endpoint at all (core answers
     * 404 before the move), so the negative half has no page to inspect (note 200).
     */
    @Test
    public void t_sec_64_refusalPageLinksTheItemForAReader() throws Exception {
        Item item = prod.getItem(HOSTILE);
        assertTrue(item.getACL().hasPermission2(hudson.model.User.getById("u1", true).impersonate2(), Item.READ),
                "premise: u1 may read the item");
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "u1");
        wc.getOptions().setJavaScriptEnabled(false);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(item.getUrl() + "move/move"), HttpMethod.POST);
        request.setAdditionalHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        request.setRequestParameters(List.of(new NameValuePair("destination", "/team")));
        Page page = wc.getPage(request);
        assertEquals(403, page.getWebResponse().getStatusCode(), "premise: the move is refused");
        assertTrue(page instanceof HtmlPage, "premise: a browser gets an HTML page");
        HtmlPage html = (HtmlPage) page;
        String target = decode(new java.net.URL(j.getURL(), item.getUrl()).getPath());
        List<String> seen = new java.util.ArrayList<>();
        boolean linked = false;
        // only the page body: the breadcrumb bar may link the item for unrelated reasons
        org.htmlunit.html.DomNode main = html.querySelector("#main-panel");
        assertNotNull(main, "premise: the standard layout has a main panel");
        for (org.htmlunit.html.DomNode n : main.querySelectorAll("a[href]")) {
            String href = ((DomElement) n).getAttribute("href");
            String path = decode(html.getFullyQualifiedUrl(href).getPath());
            seen.add(path);
            if (path.equals(target) || path.equals(target.replaceAll("/$", ""))) {
                linked = true;
            }
        }
        assertTrue(linked, "the refusal page body must link the item " + target + " for a reader; links: " + seen);
    }

    /**
     * T-SEC-72 (backlog #83): the browser refusal page of T-SEC-63 (u1 lacks Delete on the item,
     * Create on {@code team} comes from a window) links, in its body, a grant request form
     * ({@code batch-control/grants/}) pre-filled through {@code scopeFullName=} for what is missing:
     * the item or its source folder {@code prod}, never the destination {@code team} (Create is not
     * missing). Following the link as u1 answers 200 with the form's {@code scopeFullName} field
     * holding the linked value.
     */
    @Test
    public void t_sec_72_refusalPageLinksAPrefilledGrantRequestForm() throws Exception {
        Item item = prod.getItem(HOSTILE);
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "u1");
        wc.getOptions().setJavaScriptEnabled(false);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(item.getUrl() + "move/move"), HttpMethod.POST);
        request.setAdditionalHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        request.setRequestParameters(List.of(new NameValuePair("destination", "/team")));
        Page page = wc.getPage(request);
        assertEquals(403, page.getWebResponse().getStatusCode(), "premise: the move is refused");
        HtmlPage html = (HtmlPage) page;
        org.htmlunit.html.DomNode main = html.querySelector("#main-panel");
        assertNotNull(main, "premise: the standard layout has a main panel");

        List<java.net.URL> forms = new java.util.ArrayList<>();
        List<String> all = new java.util.ArrayList<>();
        for (org.htmlunit.html.DomNode n : main.querySelectorAll("a[href]")) {
            java.net.URL url = html.getFullyQualifiedUrl(((DomElement) n).getAttribute("href"));
            all.add(url.toString());
            if (url.getPath().contains("batch-control/grants") && url.getQuery() != null
                    && url.getQuery().contains("scopeFullName=")) {
                forms.add(url);
            }
        }
        assertFalse(forms.isEmpty(), "the refusal page must link a pre-filled grant request form (scopeFullName=); links: " + all);
        String source = prod.getFullName();
        for (java.net.URL url : forms) {
            String scope = queryValue(url, "scopeFullName");
            assertTrue(scope.equals(source) || scope.equals(item.getFullName()),
                    "a pre-filled form must be for what is missing (Delete on " + item.getFullName() + " or " + source
                            + "), got scopeFullName=" + scope + " in " + url);
        }

        java.net.URL first = forms.get(0);
        JenkinsRule.WebClient follow = ApproverFormFixtures.client(j, "u1");
        follow.getOptions().setJavaScriptEnabled(false);
        Page form = follow.getPage(first);
        assertEquals(200, form.getWebResponse().getStatusCode(), "u1 must be able to open the linked form " + first);
        HtmlPage formPage = (HtmlPage) form;
        String expected = queryValue(first, "scopeFullName");
        boolean prefilled = false;
        for (org.htmlunit.html.DomNode n : formPage.querySelectorAll("[name=scopeFullName]")) {
            DomElement e = (DomElement) n;
            if (expected.equals(e.getAttribute("value")) || expected.equals(e.getTextContent().trim())) {
                prefilled = true;
            }
        }
        assertTrue(prefilled, "the linked form must carry scopeFullName=" + expected);
    }

    private static String queryValue(java.net.URL url, String name) {
        for (String pair : url.getQuery().split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                return java.net.URLDecoder.decode(eq < 0 ? "" : pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String decode(String path) {
        return java.net.URLDecoder.decode(path.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
    }
}
