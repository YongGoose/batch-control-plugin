package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.Page;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #41 (Wave C-UI contract, matrix rows T-UI-140..142, note 325): the breadcrumb context
 * menu of the Batch Control root page ({@code /batch-control/contextMenu}) lists exactly the
 * sections the tab bar shows to the same viewer, also for a requester who holds
 * {@code BatchControl/Request} only on a job or a folder (SPEC 2, D-38b/D-38c: such a user reaches
 * the root page and the run requests section once they have requests of their own, and the
 * activations section whenever they reach the root page). A user who cannot open the page gets no
 * menu entries.
 *
 * <p>Basis: SPEC 2 (D-61: "the breadcrumb of the root action offers the same sections as a context
 * menu"; D-38b), the frozen tab-bar markup of note 191 ({@code nav[data-batch-control-tabs]},
 * {@code a[data-batch-control-tab=<section>]}), and the issue text. The tab bar is the reference:
 * the menu is compared with what the page shows the same viewer, not with a restatement of the
 * permission rules.
 *
 * <p>Written from docs/SPEC.md, docs/TEST-MATRIX.md, issue #41 and the Wave C-UI contract only (no
 * src/main knowledge).
 */
@WithJenkins
public class ContextMenuItemLevelRequesterTest {

    private static final String ROOT = "batch-control/";

    private JenkinsRule j;
    private FreeStyleProject job;
    private FreeStyleProject inFolder;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        job = j.createFreeStyleProject("batch-x");
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
        Folder ops = j.jenkins.createProject(Folder.class, "ops");
        inFolder = ops.createProject(FreeStyleProject.class, "in-x");
        BatchControlFixtures.setBatchControl(inFolder, new BatchControlJobProperty(true));
        FreeStyleProject other = j.createFreeStyleProject("other-x");
        BatchControlFixtures.setBatchControl(other, new BatchControlJobProperty(true));

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ).everywhere().to("jr", "jr0", "fr")
                // jr and jr0: Request on the job batch-x only
                .grant(Item.READ, BatchControlPermissions.REQUEST).onItems(job).to("jr", "jr0")
                // fr: Request on the folder ops, inherited by ops/in-x
                .grant(Item.READ, BatchControlPermissions.REQUEST).onFolders(ops).to("fr")
                // guard: the global requester of T-UI-43
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ).everywhere().to("plain"));

        assertFalse(j.jenkins.getACL().hasPermission2(User.getById("jr", true).impersonate2(), BatchControlPermissions.REQUEST),
                "premise: jr holds no Jenkins-level Request");
        assertFalse(j.jenkins.getACL().hasPermission2(User.getById("fr", true).impersonate2(), BatchControlPermissions.REQUEST),
                "premise: fr holds no Jenkins-level Request");
        try (ACLContext ignored = ACL.as2(User.getById("jr", true).impersonate2())) {
            assertTrue(job.hasPermission(BatchControlPermissions.REQUEST), "premise: jr holds Request on batch-x");
            assertFalse(other.hasPermission(BatchControlPermissions.REQUEST), "premise: jr holds no Request on other-x");
        }
        try (ACLContext ignored = ACL.as2(User.getById("fr", true).impersonate2())) {
            assertTrue(inFolder.hasPermission(BatchControlPermissions.REQUEST), "premise: the folder grant covers ops/in-x");
            assertFalse(job.hasPermission(BatchControlPermissions.REQUEST), "premise: fr holds no Request on batch-x");
        }
    }

    /**
     * T-UI-140 (#41): jr holds Request only on the job {@code batch-x} and has filed a request
     * there. jr's root page shows a tab bar (premise: overview and requests, D-38b), and jr's
     * context menu lists exactly the tab bar's sections, with no other entry. Guard: the global
     * requester u1 still gets a menu equal to their tab bar, and {@code plain} (no Batch Control
     * permission) gets no entry.
     */
    @Test
    public void t_ui_140_contextMenuOfAJobLevelRequesterMatchesTheTabBar() throws Exception {
        assertGuards();
        fileRequest("jr", job);

        Set<String> tabs = tabsOf("jr");
        assertTrue(tabs.contains(SectionTabsTest.OVERVIEW) && tabs.contains("requests"),
                "premise (D-38b): a job-level requester with a request of their own sees the overview and requests tabs, got " + tabs);
        assertMenuMatchesTabs("jr", tabs);
    }

    /**
     * T-UI-141 (#41): as T-UI-140 for fr, who holds Request only through the folder {@code ops}
     * and has filed a request on {@code ops/in-x}.
     */
    @Test
    public void t_ui_141_contextMenuOfAFolderLevelRequesterMatchesTheTabBar() throws Exception {
        assertGuards();
        fileRequest("fr", inFolder);

        Set<String> tabs = tabsOf("fr");
        assertTrue(tabs.contains(SectionTabsTest.OVERVIEW) && tabs.contains("requests"),
                "premise (D-38b): a folder-level requester with a request of their own sees the overview and requests tabs, got " + tabs);
        assertMenuMatchesTabs("fr", tabs);
    }

    /**
     * T-UI-142 (#41, negative path): jr0 holds Request only on {@code batch-x} and has no request
     * of their own. Whatever the root page answers jr0, the menu follows it: when the root page does
     * not open (status other than 200), the menu has no entry (or is not served); when it opens, the
     * menu lists exactly the tab bar's sections. Guard as T-UI-140.
     */
    @Test
    public void t_ui_142_contextMenuOfAnItemLevelRequesterWithoutRequestsFollowsTheRootPage() throws Exception {
        assertGuards();
        int root = status("jr0", ROOT);
        if (root == 200) {
            assertMenuMatchesTabs("jr0", SectionTabsTest.tabs(page("jr0", ROOT)));
        } else {
            assertNoMenuEntries("jr0", root);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The cases that work today and must keep working (T-UI-42, T-UI-43). */
    private void assertGuards() throws Exception {
        Set<String> u1Tabs = tabsOf("u1");
        assertTrue(u1Tabs.contains("requests"), "guard: the global requester u1 has the requests tab, got " + u1Tabs);
        assertMenuMatchesTabs("u1", u1Tabs);
        int plainRoot = status("plain", ROOT);
        assertEquals(404, plainRoot, "guard (SPEC 2, #31): the root page answers 404 to a user without a Batch Control permission");
        assertNoMenuEntries("plain", plainRoot);
    }

    private void fileRequest(String user, FreeStyleProject target) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            RunRequestService.get().create(target, new LinkedHashMap<>(), "month-end run by " + user, "a1");
        }
    }

    private Set<String> tabsOf(String user) throws Exception {
        return SectionTabsTest.tabs(page(user, ROOT));
    }

    private void assertMenuMatchesTabs(String user, Set<String> tabs) throws Exception {
        Page menu = client(user).getPage(new URL(j.getURL(), ROOT + "contextMenu"));
        String body = menu.getWebResponse().getContentAsString();
        assertEquals(200, menu.getWebResponse().getStatusCode(), user + ": the context menu must answer 200 to a user who opens the root page: "
                + excerpt(body));
        Set<String> sections = new TreeSet<>();
        List<String> others = new ArrayList<>();
        for (String target : contextMenuTargets(body)) {
            String s = sectionOf(target);
            if (s != null) {
                sections.add(s);
            } else {
                others.add(target);
            }
        }
        assertEquals(new TreeSet<>(tabs), sections, user + ": the breadcrumb context menu must list exactly the sections the tab bar"
                + " shows the same viewer (#41, D-61); menu body: " + excerpt(body));
        assertTrue(others.isEmpty(), user + ": a requester's context menu may hold only section entries, got " + others);
    }

    private void assertNoMenuEntries(String user, int rootStatus) throws Exception {
        Page menu = client(user).getPage(new URL(j.getURL(), ROOT + "contextMenu"));
        int status = menu.getWebResponse().getStatusCode();
        List<String> targets = status == 200 ? contextMenuTargets(menu.getWebResponse().getContentAsString()) : List.of();
        assertTrue(targets.isEmpty(), user + ": the root page answers " + rootStatus
                + ", so the context menu must offer no entry, got " + targets);
    }

    private JenkinsRule.WebClient client(String user) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        return wc.login(user);
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

    private static List<String> contextMenuTargets(String body) {
        List<String> out = new ArrayList<>();
        String trimmed = body.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
            return out;
        }
        JSONArray items = JSONObject.fromObject(trimmed).optJSONArray("items");
        if (items == null) {
            return out;
        }
        for (Object o : items) {
            String url = ((JSONObject) o).optString("url", "");
            if (!url.isEmpty()) {
                out.add(url);
            }
        }
        return out;
    }

    /** The section a context-menu URL points at, or null when it is not a section. */
    private static String sectionOf(String url) {
        String u = url.replaceAll("[?#].*$", "").replaceAll("/+$", "");
        String last = u.substring(u.lastIndexOf('/') + 1);
        if ("batch-control".equals(last)) {
            return SectionTabsTest.OVERVIEW;
        }
        return SectionTabsTest.SECTIONS.contains(last) ? last : null;
    }
}
