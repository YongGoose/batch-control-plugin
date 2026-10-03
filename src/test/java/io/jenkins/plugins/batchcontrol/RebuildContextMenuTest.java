package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import net.sf.json.JSONArray;
import net.sf.json.JSONNull;
import net.sf.json.JSONObject;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertApprovedRunQueuedExactlyOnce;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.requestAndApprove;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.secureWithRunControl;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * e2e-07 DEF-06: with the rebuild plugin installed, the job's {@code contextMenu} on an
 * approval-required job with a completed build carried a "Rebuild Last" entry whose URL was null,
 * which broke the new job page's "More actions" menu. Matrix rows T-06-98/99 (note 204).
 *
 * <p>The new job page is switched on through core's flag default ({@code new-job-page.flag.defaultValue},
 * a system property read when the flag is evaluated) and cleared afterwards. The no-completed-build
 * case is rebuild's own defect (LIMITATIONS 45) and deliberately not asserted.
 *
 * <p>Written from docs/reports/e2e-07.md, docs/SPEC.md item 6 and docs/TEST-MATRIX.md only (no
 * src/main knowledge).
 */
@WithJenkins
public class RebuildContextMenuTest {

    static final String FLAG = "new-job-page.flag.defaultValue";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        secureWithRunControl(j);
    }

    @AfterEach
    public void clearFlag() {
        System.clearProperty(FLAG);
    }

    /**
     * T-06-98: new job page on, approval-required job with an approved, completed run: no item of
     * the job's context menu (for u1 and admin) has a null URL; "Rebuild Last" (if present) points at
     * {@code lastCompletedBuild/rebuild/...}; POSTing it is refused, nothing is queued, and the
     * refusal is recorded.
     */
    @Test
    public void t_06_98_newJobPageContextMenuHasNoNullUrlAndRebuildStaysRefused() throws Exception {
        System.setProperty(FLAG, "true");
        FreeStyleProject job = approvedJobWithCompletedRun("rb-menu-new");
        String rebuild = null;
        for (String user : new String[] {"u1", "admin"}) {
            List<JSONObject> items = contextMenu(user, job);
            assertNoNullUrl(user + " (new job page)", items);
            for (JSONObject item : items) {
                String url = urlOf(item);
                if (url != null && url.contains("rebuild")) {
                    assertTrue(url.contains("lastCompletedBuild/rebuild"), "Rebuild Last must point at lastCompletedBuild/rebuild/: " + url);
                    rebuild = url;
                }
            }
        }
        assertNotNull(rebuild, "premise: the rebuild plugin offers Rebuild Last in the job's context menu");

        int records = rerunRecords(job).size();
        String path = rebuild.startsWith("/") ? rebuild.substring(j.contextPath.length() + 1)
                : rebuild.startsWith("http") ? new URL(rebuild).getPath().substring(j.contextPath.length() + 1)
                : job.getUrl() + rebuild;
        PluginInteractionFixtures.post(j, "u1", path);
        ActivationFixtures.assertBlocked(j, job, 2, 1);
        assertTrue(rerunRecords(job).size() > records, "the refused rebuild must be recorded (TRIGGER_BLOCKED or MARKER_REUSE_BLOCKED)");
    }

    /** T-06-99 (guard): on the classic job page the same context menu has no null URL either. */
    @Test
    public void t_06_99_classicContextMenuHasNoNullUrl() throws Exception {
        System.setProperty(FLAG, "false");
        FreeStyleProject job = approvedJobWithCompletedRun("rb-menu-classic");
        for (String user : new String[] {"u1", "admin"}) {
            assertNoNullUrl(user + " (classic page)", contextMenu(user, job));
        }
    }

    // ---------------------------------------------------------------- helpers

    private FreeStyleProject approvedJobWithCompletedRun(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
        BatchControlFixtures.activate(job);
        requestAndApprove(job);
        FreeStyleBuild approved = (FreeStyleBuild) assertApprovedRunQueuedExactlyOnce(j, job);
        assertNotNull(job.getLastCompletedBuild(), "premise: the job has a completed build");
        assertEquals(approved, job.getLastCompletedBuild(), "premise: the approved run is the last completed build");
        return job;
    }

    private List<JSONObject> contextMenu(String user, FreeStyleProject job) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.login(user);
        WebResponse r = wc.getPage(new URL(j.getURL(), job.getUrl() + "contextMenu")).getWebResponse();
        String body = r.getContentAsString();
        assertEquals(200, r.getStatusCode(), user + ": the job's contextMenu must answer 200: " + excerpt(body));
        JSONArray items = JSONObject.fromObject(body).getJSONArray("items");
        List<JSONObject> out = new ArrayList<>();
        for (Object o : items) {
            out.add((JSONObject) o);
        }
        assertFalse(out.isEmpty(), user + ": the context menu must have items: " + excerpt(body));
        return out;
    }

    /** The item's target: {@code url}, or {@code event.url} on the new menu model. */
    private static String urlOf(JSONObject item) {
        String url = str(item, "url");
        if (url == null && item.optJSONObject("event") != null) {
            url = str(item.getJSONObject("event"), "url");
        }
        return url;
    }

    private static String str(JSONObject o, String key) {
        if (!o.has(key)) {
            return null;
        }
        Object v = o.get(key);
        return v == null || v instanceof JSONNull ? null : v.toString();
    }

    private static void assertNoNullUrl(String who, List<JSONObject> items) {
        for (JSONObject item : items) {
            String type = str(item, "type");
            if ("HEADER".equals(type) || "SEPARATOR".equals(type)) {
                continue;
            }
            JSONObject event = item.optJSONObject("event");
            boolean eventHasNullUrl = event != null && event.has("url") && str(event, "url") == null;
            boolean linkWithoutUrl = event == null && (!item.has("url") || str(item, "url") == null)
                    && !"SUBMENU".equals(type) && item.optJSONObject("subMenu") == null;
            assertFalse(eventHasNullUrl || linkWithoutUrl,
                    who + ": context menu item '" + str(item, "displayName") + "' has a null URL: " + item);
        }
    }

    private static List<ChangeRecord> rerunRecords(FreeStyleProject job) {
        List<ChangeRecord> out = new ArrayList<>();
        out.addAll(ActivationFixtures.recordsFor(ChangeType.MARKER_REUSE_BLOCKED, job.getFullName()));
        out.addAll(ActivationFixtures.recordsFor(ChangeType.TRIGGER_BLOCKED, job.getFullName()));
        return out;
    }
}
