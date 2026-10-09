package io.jenkins.plugins.batchcontrol;

import hudson.matrix.AxisList;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.matrix.MatrixRun;
import hudson.matrix.TextAxis;
import hudson.model.Cause;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.approverPairs;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.get;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.post;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.runRequestIds;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.activate;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug hunt A R2-02 and R2-01 (D-82, TEST-MATRIX note 302): a sub-item of a job follows its parent job's
 * run control. A multi-configuration project's configuration has no activation or approval state of its
 * own; the queue gate applies the parent's approval property and activation to it. An activated matrix
 * project's Build Now, timer and approved runs start and their configuration runs run; a direct
 * submission of a configuration by a user without approval is refused like the parent's; a configuration
 * shows no activation notice or request entry of its own (its request and activation actions answer 404)
 * but a notice pointing to the parent, and a run request on it is refused with a pointer to the parent.
 * Matrix rows T-06a-58 .. T-06a-62.
 *
 * <p>Basis: DECISIONS D-82; SPEC 6a (unattended causes pass only for an activated job; the job page shows
 * the activation state and links to the request form), SPEC 6 (a manual run of an approval-required job does
 * not reach the queue without an approved request; the blocking triple), SPEC 5 (run requests), SPEC 2 (the
 * per-job request action). Activation is brought about only through the real request flow
 * ({@link BatchControlFixtures#activate}). matrix-project is on the test class path (a transitive test
 * dependency, as for {@link ItemScopeSubmissionTest}).
 *
 * <p>Written from docs/SPEC.md items 2, 5, 6 and 6a and docs/DECISIONS.md D-82 only (no src/main knowledge).
 */
@WithJenkins
public class ActivationMatrixConfigurationTest {

    private static final Pattern TO_PARENT = Pattern.compile("(?i)request it on\\W{0,3}mx\\b");
    private static final Pattern MANUAL = Pattern.compile("(?i)manual");
    private static final Pattern APPROVAL = Pattern.compile("(?i)approv");
    private static final Pattern PART_OF = Pattern.compile("(?i)part of");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST, BatchControlPermissions.REQUEST_GRANT).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ, Item.BUILD).everywhere().to("nobc"));
        j.jenkins.setNumExecutors(4);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(List.of("a1"));
        cfg.save();
    }

    /**
     * T-06a-58 (P0, D-82 (a)): the matrix project {@code mx} (axis X = a, b; not approval-required, timer and
     * upstream not blocked) is activated through the request flow. The administrator's Build Now, a timer
     * submission and, once {@code mx} requires approval, u1's run approved by a1 each finish SUCCESS with both
     * configuration runs SUCCESS, and no TRIGGER_BLOCKED record names a configuration. Guard: the timer
     * submission of the matrix project {@code mx-off}, never activated, is refused (blocking triple), so the
     * parent's activation is still what is checked.
     */
    @Test
    public void t_06a_58_activatedMatrixProjectRunsItsConfigurations() throws Exception {
        MatrixProject mx = matrix("mx", false);
        activate(mx, "u1", "a1");

        MatrixBuild manual;
        try (ACLContext ignored = ACL.as2(token("admin"))) {
            manual = assertParentAndConfigurationsSucceed(mx.scheduleBuild2(0, new Cause.UserIdCause()), "the administrator's Build Now");
        }
        assertEquals(1, manual.getNumber());
        assertParentAndConfigurationsSucceed(mx.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "the timer run");

        BatchControlJobProperty approval = new BatchControlJobProperty(true);
        approval.setBlockTimer(false);
        approval.setBlockUpstream(false);
        setBatchControl(mx, approval);
        String id = submitRunOk(j, "u1", mx, "approved matrix run", "a1");
        assertSuccess(decideRun(j, "a1", id, "approve", "ok"), "a1's approval");
        j.waitUntilNoActivity();
        MatrixBuild approved = mx.getLastBuild();
        assertNotNull(approved, "the approved run started");
        assertEquals(3, approved.getNumber(), "the approved run is the third run of mx");
        assertConfigurationsSucceeded(approved, "the approved run");

        for (String configuration : new String[] {"mx/X=a", "mx/X=b"}) {
            List<ChangeRecord> blocked = ActivationFixtures.recordsFor(ChangeType.TRIGGER_BLOCKED, configuration);
            assertTrue(blocked.isEmpty(), "D-82: no TRIGGER_BLOCKED record for the configuration " + configuration + ": "
                    + blocked.stream().map(ChangeRecord::getDetail).collect(Collectors.toList()));
        }

        MatrixProject off = matrix("mx-off", false);
        int next = off.getNextBuildNumber();
        int builds = off.getBuilds().size();
        assertNull(off.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "guard (SPEC 6a): the timer of a never-activated matrix project is refused");
        assertBlocked(j, off, next, builds);
    }

    /**
     * T-06a-59 (P0, D-82 (b)): {@code mx} requires approval and is activated. Guard first, with run control
     * off: nobc's (Item/Build, no Batch Control permission) {@code POST job/mx/X=a/build} is not refused by
     * Batch Control (below 400). With run control on, nobc's {@code POST job/mx/X=a/build} is refused with the
     * same answer as nobc's {@code POST job/mx/build}, not 2xx, and the configuration gets no queue item and
     * no run (blocking triple).
     */
    @Test
    public void t_06a_59_directConfigurationBuildFollowsTheParentsApproval() throws Exception {
        MatrixProject mx = matrix("mx", true);
        activate(mx, "u1", "a1");
        MatrixConfiguration xa = configuration(mx, "X=a");

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(false);
        cfg.save();
        int off = postBuild("nobc", xa.getUrl() + "build?delay=0sec").getStatusCode();
        assertTrue(off < 400, "guard (SPEC 1): with run control off Batch Control does not refuse the direct build, got HTTP " + off);
        j.waitUntilNoActivity();
        cfg.setRunControlEnabled(true);
        cfg.save();

        int next = xa.getNextBuildNumber();
        int builds = xa.getBuilds().size();
        WebResponse parent = postBuild("nobc", mx.getUrl() + "build?delay=0sec");
        WebResponse direct = postBuild("nobc", xa.getUrl() + "build?delay=0sec");
        assertFalse(direct.getStatusCode() >= 200 && direct.getStatusCode() < 300, "R2-01, D-82: nobc's direct build of mx/X=a must be"
                + " refused while mx requires approval, got HTTP " + direct.getStatusCode() + ": " + excerpt(direct.getContentAsString()));
        assertEquals(parent.getStatusCode(), direct.getStatusCode(), "D-82: the configuration is refused like its parent");
        assertBlocked(j, xa, next, builds);
        assertEquals(0, mx.getBuilds().size(), "the parent did not run either");
    }

    /**
     * T-06a-60 (P1, D-82 (c)): {@code mx} is not activated (premise: its page says "not activated" and links to
     * {@code batch-control-activation/}). The page of the configuration {@code mx/X=a} shows no activation
     * state ("not activated", "on hold") and no link to an activation form, and
     * {@code job/mx/job/X=a/batch-control-activation/} answers 404.
     */
    @Test
    public void t_06a_60_configurationShowsNoActivationOfItsOwn() throws Exception {
        MatrixProject mx = matrix("mx", true);
        MatrixConfiguration xa = configuration(mx, "X=a");

        String parentPage = page(mx.getUrl());
        String parentLower = parentPage.toLowerCase(Locale.ROOT);
        assertTrue(parentLower.contains("not activated") || parentLower.contains("on hold"), "premise: the parent page says mx is not activated");
        assertTrue(parentPage.contains("batch-control-activation"), "premise: the parent page links to the activation form");

        String configurationPage = page(xa.getUrl());
        String lower = configurationPage.toLowerCase(Locale.ROOT);
        assertFalse(lower.contains("not activated") || lower.contains("on hold"),
                "D-82: the configuration page shows no activation state of its own: " + excerpt(configurationPage));
        assertFalse(configurationPage.contains("batch-control-activation"),
                "D-82: the configuration page offers no activation request link of its own");
        assertEquals(404, get(j, "u1", xa.getUrl() + "batch-control-activation/").getStatusCode(),
                "D-82: a configuration has no activation request form");
    }

    /**
     * T-06a-61 (P0, D-82): {@code mx} requires approval. u1 (Request, Item/Read) submits a run request for the
     * configuration {@code mx/X=a}: through the service ({@code RunRequestService.create} as u1) it is refused
     * with a message that says to request it on 'mx'; through the web ({@code POST job/mx/X=a/batch-control/submit})
     * it answers 404 (the per-job request action is absent on a configuration, T-06a-62). Neither stores a run
     * request. Guard: the same request on {@code mx} is stored.
     */
    @Test
    public void t_06a_61_runRequestOnAConfigurationPointsToTheParent() throws Exception {
        MatrixProject mx = matrix("mx", true);
        MatrixConfiguration xa = configuration(mx, "X=a");
        Set<String> before = runRequestIds();

        RuntimeException refused;
        try (ACLContext ignored = ACL.as2(token("u1"))) {
            refused = assertThrows(RuntimeException.class,
                    () -> RunRequestService.get().create(xa, new LinkedHashMap<>(), "run one axis", "a1"),
                    "D-82: a run request on a configuration is refused by the service");
        }
        assertTrue(refused.getMessage() != null && TO_PARENT.matcher(refused.getMessage()).find(),
                "D-82: the refusal says to request it on the parent 'mx': " + refused.getMessage());
        assertEquals(before, runRequestIds(), "D-82: the refused run request on a configuration stores nothing");

        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("reason", "run one axis"));
        params.addAll(approverPairs("a1"));
        WebResponse submit = post(j, "u1", xa.getUrl() + "batch-control/submit", params);
        assertEquals(404, submit.getStatusCode(), "D-82 (c): the configuration has no request action to submit to: "
                + excerpt(submit.getContentAsString()));
        assertEquals(before, runRequestIds(), "D-82: the web submission on a configuration stores nothing");

        String id = submitRunOk(j, "u1", mx, "run the matrix", "a1");
        assertNotNull(RunRequestService.get().load(id), "guard: the request on the parent mx is stored");
    }

    /**
     * T-06a-62 (P1, D-82 (c), screen contract of ui-dev): change control and run control on; {@code mx} requires
     * approval and is not activated; u1 holds Request, RequestGrant and Item/Read. Premise and guard on the parent
     * {@code mx}: its request form {@code job/mx/batch-control/} and activation form
     * {@code job/mx/batch-control-activation/} answer 200, and its page shows the "Request Run" and "Request Change
     * Permission" entries and the notice that manual runs need an approved request. On the configuration
     * {@code mx/X=a}: GET {@code batch-control/}, {@code batch-control/dialog} and
     * {@code batch-control-activation/}, and POST (with crumb) {@code batch-control/submit} and
     * {@code batch-control-activation/submit}, all answer 404; its page has no "Request Run" or "Request Change
     * Permission" entry and no manual-run approval notice, and shows the sub-item notice, which contains "part of"
     * and links to {@code mx}.
     */
    @Test
    public void t_06a_62_configurationPageOffersNothingOfItsOwnAndPointsToTheParent() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.save();
        MatrixProject mx = matrix("mx", true);
        MatrixConfiguration xa = configuration(mx, "X=a");

        assertEquals(200, get(j, "u1", mx.getUrl() + "batch-control/").getStatusCode(), "guard: the parent's request form opens");
        assertEquals(200, get(j, "u1", mx.getUrl() + "batch-control-activation/").getStatusCode(), "guard: the parent's activation form opens");
        HtmlPage parent = UsabilityFixtures.htmlPage(j, "u1", mx.getUrl());
        assertFalse(entries(parent, "Request Run").isEmpty(), "premise: the parent page has a Request Run entry");
        assertFalse(entries(parent, "Request Change Permission").isEmpty(), "premise: the parent page has a Request Change Permission entry");
        assertNotNull(approvalNotice(parent), "guard: the parent page shows the notice that manual runs need an approved request");

        for (String sub : new String[] {"batch-control/", "batch-control/dialog", "batch-control-activation/"}) {
            assertEquals(404, get(j, "u1", xa.getUrl() + sub).getStatusCode(), "D-82 (c): GET " + xa.getUrl() + sub + " answers 404");
        }
        List<NameValuePair> run = new ArrayList<>();
        run.add(new NameValuePair("reason", "run one axis"));
        run.addAll(approverPairs("a1"));
        assertEquals(404, post(j, "u1", xa.getUrl() + "batch-control/submit", run).getStatusCode(),
                "D-82 (c): POST batch-control/submit on the configuration answers 404");
        List<NameValuePair> activation = new ArrayList<>();
        activation.add(new NameValuePair("action", "ACTIVATE"));
        activation.add(new NameValuePair("reason", "go live"));
        activation.addAll(approverPairs("a1"));
        assertEquals(404, post(j, "u1", xa.getUrl() + "batch-control-activation/submit", activation).getStatusCode(),
                "D-82 (c): POST batch-control-activation/submit on the configuration answers 404");

        HtmlPage page = UsabilityFixtures.htmlPage(j, "u1", xa.getUrl());
        assertTrue(entries(page, "Request Run").isEmpty(), "D-82 (c): the configuration page has no Request Run entry: "
                + hrefs(entries(page, "Request Run")));
        assertTrue(entries(page, "Request Change Permission").isEmpty(), "D-82 (c): the configuration page has no Request Change"
                + " Permission entry: " + hrefs(entries(page, "Request Change Permission")));
        assertNull(approvalNotice(page), "D-82 (c): the configuration page shows no manual-run approval notice of its own");
        assertTrue(subItemNoticeLinksTo(page, mx), "D-82 (c): the configuration page shows the sub-item notice, containing \"part of\""
                + " and linking to mx: " + excerpt(page.asNormalizedText()));
    }

    // ---------------------------------------------------------------- helpers

    /** A matrix project (axis X = a, b) created while run control is on, with its job settings installed and read back. */
    private MatrixProject matrix(String name, boolean approvalRequired) throws Exception {
        MatrixProject mx = j.jenkins.createProject(MatrixProject.class, name);
        mx.setAxes(new AxisList(new TextAxis("X", "a", "b")));
        BatchControlJobProperty property = new BatchControlJobProperty(approvalRequired);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(mx, property);
        assertEquals(2, mx.getItems().size(), "fixture: mx has the configurations X=a and X=b");
        return mx;
    }

    private static MatrixConfiguration configuration(MatrixProject mx, String name) {
        MatrixConfiguration c = mx.getItem(name);
        assertNotNull(c, "fixture: " + mx.getFullName() + " has the configuration " + name);
        return c;
    }

    private MatrixBuild assertParentAndConfigurationsSucceed(QueueTaskFuture<MatrixBuild> future, String what) throws Exception {
        assertNotNull(future, "D-82: " + what + " of the activated matrix project is scheduled");
        MatrixBuild build = future.get();
        assertEquals(Result.SUCCESS, build.getResult(), "R2-02, D-82: " + what + " of the activated matrix project must finish SUCCESS"
                + " (its configuration runs are not refused by the gate); configuration runs: " + describe(build));
        assertConfigurationsSucceeded(build, what);
        return build;
    }

    private static void assertConfigurationsSucceeded(MatrixBuild build, String what) {
        List<MatrixRun> runs = build.getExactRuns();
        assertEquals(2, runs.size(), "D-82: " + what + " runs both configurations: " + describe(build));
        for (MatrixRun run : runs) {
            assertEquals(Result.SUCCESS, run.getResult(), "D-82: " + what + ": the configuration run " + run.getFullDisplayName() + " succeeds");
        }
    }

    private static String describe(MatrixBuild build) {
        return build.getExactRuns().stream().map(r -> r.getParent().getName() + "#" + r.getNumber() + "=" + r.getResult())
                .collect(Collectors.toList()).toString();
    }

    private String page(String path) throws Exception {
        WebResponse response = get(j, "u1", path);
        assertEquals(200, response.getStatusCode(), path + " opens");
        return response.getContentAsString();
    }

    private static List<HtmlAnchor> entries(HtmlPage page, String caption) {
        return page.getAnchors().stream().filter(a -> a.asNormalizedText().trim().contains(caption)).collect(Collectors.toList());
    }

    private static List<String> hrefs(List<HtmlAnchor> anchors) {
        return anchors.stream().map(HtmlAnchor::getHrefAttribute).collect(Collectors.toList());
    }

    /** The innermost main-panel element whose text names a manual run and approval (the T-06-56 notice), or null. */
    private static DomElement approvalNotice(HtmlPage page) {
        return innermost(page, text -> MANUAL.matcher(text).find() && APPROVAL.matcher(text).find());
    }

    /** Whether a main-panel element saying "part of" has, within it or up to three levels above, a link to {@code parent}. */
    private boolean subItemNoticeLinksTo(HtmlPage page, MatrixProject parent) throws Exception {
        DomElement notice = innermost(page, text -> PART_OF.matcher(text).find());
        if (notice == null) {
            return false;
        }
        String target = stripSlash(new URL(j.getURL(), parent.getUrl()).toExternalForm());
        DomNode current = notice;
        for (int level = 0; level < 4 && current != null; level++) {
            if (current instanceof DomElement) {
                for (DomElement a : ((DomElement) current).getElementsByTagName("a")) {
                    if (a instanceof HtmlAnchor
                            && stripSlash(page.getFullyQualifiedUrl(((HtmlAnchor) a).getHrefAttribute()).toExternalForm()).equals(target)) {
                        return true;
                    }
                }
            }
            current = current.getParentNode();
        }
        return false;
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static DomElement innermost(HtmlPage page, java.util.function.Predicate<String> matches) {
        DomElement main = page.getElementById("main-panel");
        if (main == null) {
            return null;
        }
        DomElement best = null;
        for (DomElement element : main.getHtmlElementDescendants()) {
            String tag = element.getTagName();
            if ("script".equals(tag) || "style".equals(tag)) {
                continue;
            }
            String text = element.getTextContent();
            if (text != null && matches.test(text)) {
                best = element; // descendants come after their ancestors, so the last match is the innermost
            }
        }
        return best;
    }

    private WebResponse postBuild(String user, String path) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, user);
        URL url = new URL(wc.createCrumbedUrl(path.substring(0, path.indexOf('?'))).toExternalForm() + "&" + path.substring(path.indexOf('?') + 1));
        return wc.getPage(new WebRequest(url, HttpMethod.POST)).getWebResponse();
    }
}
