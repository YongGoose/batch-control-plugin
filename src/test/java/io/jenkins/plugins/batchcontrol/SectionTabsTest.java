package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.Page;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hosting review 2026-10-02 (PR6): the sections of {@code /batch-control/} are a tab bar with
 * open-item badges, and the breadcrumb offers a context menu of the sections. Matrix rows
 * T-UI-40..45 (note 191). SPEC visibility rules apply (SPEC 2 #31: a link is shown only to a user
 * who may open its target; SPEC 12: without ViewHistory every view screen is 403).
 *
 * <p>Frozen markup (ui-dev): {@code nav[data-batch-control-tabs]} containing
 * {@code a[data-batch-control-tab=<section>]}; the badge is a descendant of the tab whose class
 * contains {@code badge} (design-library {@code l:badge}). Sections: overview (the root page),
 * requests, activations, grants, history, changes, dashboard, incidents.
 *
 * <p>"May access" is measured, not assumed: a section is accessible to a user when its URL answers
 * 200 to that user. The tab bar must list exactly those sections (grants only while change control
 * is on), so a hidden reachable section and a shown unreachable one both fail.
 *
 * <p>Written from docs/SPEC.md, docs/TEST-MATRIX.md and the coordinator's brief only (no src/main
 * knowledge).
 */
@WithJenkins
public class SectionTabsTest {

    static final String ROOT = "batch-control/";
    static final List<String> SECTIONS = Arrays.asList(
            "requests", "activations", "grants", "history", "changes", "dashboard", "incidents");
    /** The tab of /batch-control/ itself. */
    static final String OVERVIEW = "overview";
    /** Users with some Batch Control permission. */
    private static final String[] USERS = {"admin", "u1", "a1", "a2", "viewer", "g1", "m1"};

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ).everywhere().to("plain")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST_GRANT).everywhere().to("g1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.MANAGE).everywhere().to("m1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.save();
    }

    /**
     * T-UI-40: for every permission set the tab bar lists exactly the sections the user can open
     * (URL answers 200), with change control on. Anchors: the requester has the requests tab and no
     * history tab (SPEC 12), the ViewHistory holder has history, the RequestGrant holder has grants.
     */
    @Test
    public void t_ui_40_tabsListExactlyTheAccessibleSections() throws Exception {
        Map<String, Set<String>> tabsByUser = new TreeMap<>();
        for (String user : USERS) {
            Set<String> accessible = accessibleSections(user);
            Set<String> tabs = tabs(page(user, ROOT));
            tabsByUser.put(user, tabs);
            assertEquals(accessible, tabs, user + ": the tab bar must list exactly the sections that open for the user"
                    + " (accessible " + accessible + ", tabs " + tabs + ")");
        }
        assertTrue(tabsByUser.get("u1").contains("requests"), "a Request holder must have the requests tab: " + tabsByUser);
        assertFalse(tabsByUser.get("u1").contains("history"), "without ViewHistory there is no history tab (SPEC 12)");
        assertTrue(tabsByUser.get("viewer").contains("history"), "a ViewHistory holder must have the history tab");
        assertTrue(tabsByUser.get("g1").contains("grants"), "with change control on a RequestGrant holder must have the grants tab");
        assertTrue(tabsByUser.get("admin").containsAll(SECTIONS), "the administrator must see every section: " + tabsByUser.get("admin"));
    }

    /**
     * T-UI-41: with change control off no user has a grants tab, while the other tabs stay
     * (guard: the administrator still has requests and history).
     */
    @Test
    public void t_ui_41_grantsTabOnlyWhileChangeControlIsOn() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(false);
        cfg.save();
        for (String user : USERS) {
            HtmlPage p = page(user, ROOT);
            Set<String> tabs = tabs(p);
            assertFalse(tabs.contains("grants"), user + ": with change control off there must be no grants tab, got " + tabs);
        }
        Set<String> admin = tabs(page("admin", ROOT));
        assertTrue(admin.contains("requests") && admin.contains("history"),
                "guard: the other tabs stay with change control off, got " + admin);
    }

    /**
     * T-UI-42: a user without any Batch Control permission gets no tab bar (the root action is
     * absent, 404, SPEC 2 #31) and an empty or absent context menu.
     */
    @Test
    public void t_ui_42_noTabsAndNoContextMenuWithoutBatchControlPermission() throws Exception {
        assertEquals(404, status("plain", ROOT), "premise: /batch-control/ is absent for a user with no Batch Control permission");
        Page menu = client("plain").getPage(new URL(j.getURL(), ROOT + "contextMenu"));
        int code = menu.getWebResponse().getStatusCode();
        if (code != 404) {
            assertEquals(200, code, "the context menu must answer 404 or an empty menu, got " + code);
            assertTrue(contextMenuTargets(menu).isEmpty(), "the context menu must be empty without a Batch Control permission: "
                    + excerpt(menu.getWebResponse().getContentAsString()));
        }
        Page home = client("plain").getPage(new URL(j.getURL(), ""));
        assertFalse(home.getWebResponse().getContentAsString().contains("data-batch-control-tabs"),
                "no Batch Control tab bar may appear for a user with no Batch Control permission");
    }

    /**
     * T-UI-43: {@code /batch-control/contextMenu} lists only sections the user can open and every
     * one of them (the same set as the tab bar); an entry that is not a section is allowed only for a
     * Manage holder or administrator (Configuration).
     */
    @Test
    public void t_ui_43_contextMenuListsOnlyAccessibleSections() throws Exception {
        for (String user : USERS) {
            Set<String> accessible = accessibleSections(user);
            Page menu = client(user).getPage(new URL(j.getURL(), ROOT + "contextMenu"));
            assertEquals(200, menu.getWebResponse().getStatusCode(), user + ": the context menu must answer 200");
            Set<String> sections = new TreeSet<>();
            List<String> others = new ArrayList<>();
            for (String target : contextMenuTargets(menu)) {
                String s = sectionOf(target);
                if (s != null) {
                    sections.add(s);
                } else {
                    others.add(target);
                }
            }
            assertEquals(accessible, sections, user + ": the context menu must list exactly the accessible sections");
            if (!"admin".equals(user) && !"m1".equals(user)) {
                assertTrue(others.isEmpty(), user + ": only a Manage holder may get a non-section entry, got " + others);
            }
        }
    }

    /**
     * T-UI-44: the badge on the requests tab counts PENDING requests awaiting the viewer's decision
     * for a designated approver, the viewer's own PENDING requests for a requester, and is absent for
     * a user who is neither (an approver who is not designated, a ViewHistory holder). Guard: after
     * the decision the badges are gone.
     */
    @Test
    public void t_ui_44_requestsBadgeCountsWhatAwaitsTheViewer() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("batch-x");
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
        for (String user : new String[] {"a1", "u1", "a2", "viewer"}) {
            assertNull(badge(page(user, ROOT), "requests"), user + ": premise: no badge before any request");
        }
        assertTrue(tabs(page("a1", ROOT)).contains("requests"), "premise: the designated approver has the requests tab");
        assertTrue(tabs(page("u1", ROOT)).contains("requests"), "premise: the requester has the requests tab");
        RunRequest first;
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            first = RunRequestService.get().create(job, new LinkedHashMap<>(), "month-end run", "a1");
            RunRequestService.get().create(job, new LinkedHashMap<>(), "second run", "a1");
        }
        assertEquals("2", badge(page("a1", ROOT), "requests"), "the designated approver's badge must count the 2 awaiting requests");
        assertEquals("2", badge(page("u1", ROOT), "requests"), "the requester's badge must count the 2 own pending requests");
        assertNull(badge(page("a2", ROOT), "requests"), "an approver who is not designated must get no badge");
        assertNull(badge(page("viewer", ROOT), "requests"), "a user who neither requested nor may decide must get no badge");

        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            RunRequestService.get().reject(first.getId(), "not this month");
        }
        assertEquals("1", badge(page("a1", ROOT), "requests"), "a decided request must leave the approver's count");
        assertEquals("1", badge(page("u1", ROOT), "requests"), "a decided request must leave the requester's count");
    }

    /**
     * T-UI-45: every section URL and a request detail page still answer 200 to a user allowed to
     * open them (the administrator) and carry the tab bar.
     */
    @Test
    public void t_ui_45_everySectionUrlStillAnswers200() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("batch-x");
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
        RunRequest request;
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            request = RunRequestService.get().create(job, new LinkedHashMap<>(), "month-end run", "a1");
        }
        List<String> paths = new ArrayList<>();
        paths.add(ROOT);
        for (String s : SECTIONS) {
            paths.add(ROOT + s + "/");
        }
        paths.add(ROOT + "requests/" + request.getId() + "/");
        for (String path : paths) {
            Page p = client("admin").getPage(new URL(j.getURL(), path));
            String body = p.getWebResponse().getContentAsString();
            assertEquals(200, p.getWebResponse().getStatusCode(), "/" + path + " must answer 200 to the administrator: " + excerpt(body));
            assertTrue(body.contains("data-batch-control-tabs"), "/" + path + " must render the Batch Control tab bar");
        }
    }

    // ---------------------------------------------------------------- helpers

    private JenkinsRule.WebClient client(String user) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        return wc.login(user);
    }

    /** Sections with requests awaiting a decision; their counts are shown only as tab badges (D-67). */
    static final List<String> COUNTED = Arrays.asList("requests", "activations", "grants");

    /**
     * T-UI-90 (D-67, replaces T-UI-56 of backlog #88, which D-67 withdrew): the overview shows
     * pending counts only as tab badges. With 7 run requests, 5 grant requests and 4 activation
     * requests (filed by u1 and g1) awaiting a1, a1's tabs carry the badges 7, 5 and 4 (guard: the counts exist), while
     * the page body outside the tab bar shows no count: no body link to a counted section has a
     * number next to it (the #88 table) and no body text puts one of those numbers next to a word
     * about pending requests (the banner). The administrator, to whom #88 showed the totals, sees no
     * count in the body either (distinct counts, and their total 16, keep the check from matching
     * unrelated text).
     */
    @Test
    public void t_ui_90_overviewShowsCountsOnlyAsBadges() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("batch-x");
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
        List<FreeStyleProject> others = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            FreeStyleProject other = j.createFreeStyleProject("batch-y" + i);
            BatchControlFixtures.setBatchControl(other, new BatchControlJobProperty(true));
            others.add(other);
        }
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            for (int i = 0; i < 7; i++) {
                RunRequestService.get().create(job, new LinkedHashMap<>(), "run " + i, "a1");
            }
            for (FreeStyleProject other : others) {
                io.jenkins.plugins.batchcontrol.policy.ActivationService.get().create(other,
                        io.jenkins.plugins.batchcontrol.model.ActivationRequest.Action.ACTIVATE, "go live", List.of("a1"));
            }
        }
        try (ACLContext ignored = ACL.as2(User.getById("g1", true).impersonate2())) {
            for (int i = 0; i < 5; i++) {
                io.jenkins.plugins.batchcontrol.policy.GrantRequestService.get().create(
                        new io.jenkins.plugins.batchcontrol.model.GrantScope(
                                io.jenkins.plugins.batchcontrol.model.GrantScope.Type.JOB, "batch-x"),
                        Arrays.asList(io.jenkins.plugins.batchcontrol.model.GrantAction.CONFIGURE), 30, "fix " + i, "a1");
            }
        }
        HtmlPage approver = page("a1", ROOT);
        assertEquals("7", badge(approver, "requests"), "guard: the requests tab badge stays (D-67)");
        assertEquals("5", badge(approver, "grants"), "guard: the grants tab badge stays (D-67)");
        assertEquals("4", badge(approver, "activations"), "guard: the activations tab badge stays (D-67)");
        assertNoCountsInBody("a1", approver);
        assertNoCountsInBody("admin", page("admin", ROOT));
    }

    private static void assertNoCountsInBody(String user, HtmlPage page) {
        DomElement main = (DomElement) page.querySelector("#main-panel");
        assertNotNull(main, "the overview must have a main panel");
        for (String section : COUNTED) {
            String count = overviewCount(page, main, section);
            assertNull(count, user + ": the overview body must not show a count next to its link to " + section
                    + " (the #88 count table was removed by D-67), found " + count + " in: " + excerpt(bodyText(main)));
        }
        String body = bodyText(main);
        java.util.regex.Pattern banner = java.util.regex.Pattern.compile(
                "(?i)(?<![\\w-])(7|5|4|16)(?![\\w-])\\W{0,3}(\\w+\\W+){0,4}(pending|await\\w*|request\\w*|decision\\w*|approv\\w*|grant\\w*|activation\\w*)");
        java.util.regex.Matcher m = banner.matcher(body);
        assertFalse(m.find(), user + ": the overview body must not show a pending-count banner (D-67), found '"
                + (m.find(0) ? m.group() : "") + "' in: " + excerpt(body));
    }

    /** Text of the main panel outside the tab bar, without form controls and scripts. */
    private static String bodyText(DomElement main) {
        StringBuilder out = new StringBuilder();
        for (org.htmlunit.html.DomNode n : main.getDescendants()) {
            if (n instanceof org.htmlunit.html.DomText t && !insideTabBar(t) && !insideControl(t)) {
                out.append(t.getWholeText()).append(' ');
            }
        }
        return out.toString().replaceAll("\\s+", " ");
    }

    private static boolean insideControl(org.htmlunit.html.DomNode node) {
        for (org.htmlunit.html.DomNode n = node.getParentNode(); n != null; n = n.getParentNode()) {
            String name = n.getNodeName();
            if ("script".equals(name) || "style".equals(name) || "select".equals(name) || "template".equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The count shown with an overview body link to {@code section}: the first stand-alone number
     * in the link's text or in one of its three nearest ancestors. Null when no body link to the
     * section has a number near it (or there is no such link).
     */
    private static String overviewCount(HtmlPage page, DomElement main, String section) {
        java.util.regex.Pattern number = java.util.regex.Pattern.compile("(?<![\\w-])(\\d+)(?![\\w-])");
        for (Object o : main.querySelectorAll("a[href]")) {
            DomElement a = (DomElement) o;
            if (insideTabBar(a)) {
                continue;
            }
            String path;
            try {
                path = page.getFullyQualifiedUrl(a.getAttribute("href")).getPath();
            } catch (java.net.MalformedURLException e) {
                continue;
            }
            if (!(path.endsWith("batch-control/" + section + "/") || path.endsWith("batch-control/" + section))) {
                continue;
            }
            org.htmlunit.html.DomNode node = a;
            for (int level = 0; level < 4 && node != null && node != main; level++) {
                java.util.regex.Matcher m = number.matcher(node.asNormalizedText());
                if (m.find()) {
                    return m.group(1);
                }
                node = node.getParentNode();
            }
        }
        return null;
    }

    private static boolean insideTabBar(org.htmlunit.html.DomNode node) {
        for (org.htmlunit.html.DomNode n = node; n != null; n = n.getParentNode()) {
            if (n instanceof DomElement e && e.hasAttribute("data-batch-control-tabs")) {
                return true;
            }
        }
        return false;
    }

    private HtmlPage page(String user, String path) throws Exception {
        Page p = client(user).getPage(new URL(j.getURL(), path));
        assertEquals(200, p.getWebResponse().getStatusCode(), user + " GET /" + path + " must answer 200: "
                + excerpt(p.getWebResponse().getContentAsString()));
        assertTrue(p instanceof HtmlPage, user + " GET /" + path + " must render HTML");
        return (HtmlPage) p;
    }

    private int status(String user, String path) throws Exception {
        return client(user).getPage(new URL(j.getURL(), path)).getWebResponse().getStatusCode();
    }

    private Set<String> accessibleSections(String user) throws Exception {
        boolean changeControl = BatchControlGlobalConfiguration.get().isChangeControlEnabled();
        Set<String> out = new TreeSet<>();
        if (status(user, ROOT) == 200) {
            out.add(OVERVIEW); // the root page itself is the overview tab
        }
        for (String s : SECTIONS) {
            if ("grants".equals(s) && !changeControl) {
                continue;
            }
            if (status(user, ROOT + s + "/") == 200) {
                out.add(s);
            }
        }
        return out;
    }

    /** The sections named by the tab bar; asserts that there is exactly one tab bar. */
    static Set<String> tabs(HtmlPage page) {
        List<DomElement> navs = new ArrayList<>(page.querySelectorAll("nav[data-batch-control-tabs]").stream()
                .map(n -> (DomElement) n).toList());
        assertEquals(1, navs.size(), "the page must render exactly one nav[data-batch-control-tabs]: "
                + excerpt(page.getWebResponse().getContentAsString()));
        Set<String> out = new TreeSet<>();
        navs.get(0).querySelectorAll("a[data-batch-control-tab]")
                .forEach(a -> out.add(((DomElement) a).getAttribute("data-batch-control-tab")));
        return out;
    }

    /** The badge text of a tab, or null when the tab has no (non-empty, non-zero) badge. */
    private static String badge(HtmlPage page, String section) {
        DomElement tab = null;
        for (Object o : page.querySelectorAll("nav[data-batch-control-tabs] a[data-batch-control-tab]")) {
            if (section.equals(((DomElement) o).getAttribute("data-batch-control-tab"))) {
                tab = (DomElement) o;
            }
        }
        if (tab == null) {
            return null; // no tab, no badge
        }
        for (HtmlElement e : tab.getHtmlElementDescendants()) {
            if (e.getAttribute("class").contains("badge")) {
                String text = e.asNormalizedText().trim();
                return text.isEmpty() || "0".equals(text) ? null : text;
            }
        }
        return null;
    }

    private static List<String> contextMenuTargets(Page menu) {
        List<String> out = new ArrayList<>();
        String body = menu.getWebResponse().getContentAsString().trim();
        if (body.isEmpty() || !body.startsWith("{")) {
            return out;
        }
        JSONArray items = JSONObject.fromObject(body).optJSONArray("items");
        if (items == null) {
            return out;
        }
        for (Object o : items) {
            JSONObject item = (JSONObject) o;
            String url = item.optString("url", "");
            if (!url.isEmpty()) {
                out.add(url);
            }
        }
        return out;
    }

    /** The section a context-menu URL points at, or null when it is not a section. */
    private static String sectionOf(String url) {
        String u = url.replaceAll("/+$", "");
        String last = u.substring(u.lastIndexOf('/') + 1);
        if ("batch-control".equals(last)) {
            return OVERVIEW;
        }
        return SECTIONS.contains(last) ? last : null;
    }
}
