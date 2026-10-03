package io.jenkins.plugins.batchcontrol;

import io.jenkins.plugins.batchcontrol.store.BatchClock;
import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.ui.Visibility;
import java.net.URL;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matrix rows T-SEC-20 .. T-SEC-26 — security-03 finding S-16: a caller holding
 * {@code Item/Discover} without {@code Item/Read} on one job silently lost every row of the
 * request screens, including their own.
 *
 * <p>{@code Jenkins#getItemByFullName} does not report an invisible item by returning null: with
 * {@code Discover} and without {@code Read} core <em>throws</em> {@code AccessDeniedException},
 * and Jelly's JEXL evaluator swallows that into a blank value. The page therefore answered HTTP
 * 200 with zero rows and a footer reading {@code Page 1 ( requests)}.
 *
 * <p><b>Why every row here asserts rows and totals</b> (matrix note 46): "HTTP 200 came back"
 * passes against the broken code too, and so does any assertion that only checks something is
 * <em>refused</em>. Each row therefore names the records that must be on the screen, and pairs
 * them with the record that must not be — the same shape of mistake that let 159 integration
 * tests pass while E2E-D1 was broken.
 *
 * <p>Derived from docs/SPEC.md (items 2, 5, 11 and the P-09 visibility model), the screen
 * contract in docs/TEST-MATRIX.md, and the public helper signatures only.
 */
@WithJenkins
public class DiscoverOnlyRequestScreenTest {

    /** The footer of the request list: {@code Page 1 (3 requests)}. */
    private static final Pattern FOOTER = Pattern.compile("Page\\s+(\\d+)\\s+\\((\\d+)\\s+requests\\)");

    /** The explicit branch the detail screen shows instead of an empty region. */
    private static final String NOT_AVAILABLE = "The job is not available";

    private JenkinsRule j;

    private FreeStyleProject jobJ; // b holds Item/Discover only: the finding's trigger
    private FreeStyleProject jobK; // b holds Item/Read: the rows that must survive
    private Folder folder;         // b holds Item/Discover only, for the item-level helper

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.save();

        // Jobs first: the authorization strategy below grants per-item permissions on them.
        jobJ = j.createFreeStyleProject("secret-j");
        BatchControlFixtures.setBatchControl(jobJ, new BatchControlJobProperty(true));
        jobK = j.createFreeStyleProject("open-k");
        BatchControlFixtures.setBatchControl(jobK, new BatchControlJobProperty(true));
        // SPEC item 6a: the fixture runs below are timer runs, which need activated jobs (note 91).
        // The instance is still unsecured here, so neutral ids keep the activation requests out of
        // u1's own request screens this class measures.
        BatchControlFixtures.activate(jobJ, "bc-requester", "a1");
        BatchControlFixtures.activate(jobK, "bc-requester", "a1");
        folder = j.jenkins.createProject(Folder.class, "secret-folder");

        // One run of open-k, so the detail screen's recent-run table has something to render.
        // A timer cause is used because open-k requires approval for human-started runs.
        j.assertBuildStatusSuccess(jobK.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        j.waitUntilNoActivity();

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(strategy(false));

        // Guard (SPEC item 5, D-38a): a run request needs Item/Read on the job, so b, holding
        // Request but not Item/Read on secret-j, cannot file one now. The rows below measure
        // requests b filed while b could still read the job (see requestAs).
        int stored = RunRequestService.get().list().size();
        try (ACLContext ignored = as("b")) {
            assertThrows(AccessDeniedException.class,
                    () -> RunRequestService.get().create(jobJ, new LinkedHashMap<>(), "no read", "a1"),
                    "guard: a requester without Item/Read on the job must be refused (D-38a)");
        }
        assertEquals(stored, RunRequestService.get().list().size(), "guard: the refused request must not be stored");

        // The premise of the whole finding, asserted rather than assumed: b may learn that
        // secret-j exists and may not read it, and core signals that by throwing.
        try (ACLContext ignored = as("b")) {
            assertTrue(jobJ.hasPermission(Item.DISCOVER), "fixture: b must hold Item/Discover on secret-j");
            assertFalse(jobJ.hasPermission(Item.READ), "fixture: b must NOT hold Item/Read on secret-j");
            assertTrue(jobK.hasPermission(Item.READ), "fixture: b must hold Item/Read on open-k");
            assertThrows(AccessDeniedException.class,
                    () -> Jenkins.get().getItemByFullName("secret-j", Job.class), "fixture: core must still throw for a discover-only lookup - if this ever"
                            + " stops throwing, the premise of S-16 is gone and these rows need"
                            + " re-deciding, not relaxing");
        }
    }

    /**
     * T-SEC-20: the request list of a discover-only caller shows every request that caller may
     * see, the footer total is a number and equals the number of visible rows, and the request
     * filed by someone else against the unreadable job is absent.
     *
     * <p>Against the broken code this screen answered 200 with an empty table and a footer of
     * {@code Page 1 ( requests)}, so the row count, the total and their equality are all part of
     * the assertion. The administrator's view of the same store is the false-positive guard: it
     * proves the hidden request exists and is really being withheld from b rather than missing.
     */
    @Test
    public void t_sec_20_requestListStaysPopulatedForDiscoverOnlyCaller() throws Exception {
        String mineOnK1 = requestAs("b", jobK, "nightly close, first window").getId();
        String mineOnK2 = requestAs("b", jobK, "nightly close, second window").getId();
        String mineOnJ = requestAs("b", jobJ, "reconciliation on the restricted job").getId();
        String foreignOnJ = requestAs("u1", jobJ, "someone else's run of the restricted job").getId();

        HtmlPage page = (HtmlPage) get(webClient("b"), "batch-control/requests/");
        assertEquals(200, page.getWebResponse().getStatusCode(), "the request list must render for a discover-only caller");

        List<String> rowIds = requestRowIds(page);
        assertTrue(rowIds.contains(mineOnK1), "b's own request on the readable job must be listed; rows were " + rowIds);
        assertTrue(rowIds.contains(mineOnK2), "b's second request must be listed too; rows were " + rowIds);
        assertTrue(rowIds.contains(mineOnJ), "b's own request against the discover-only job must be listed - the requester"
                + " always sees their own request (P-09); rows were " + rowIds);
        assertFalse(rowIds.contains(foreignOnJ), "a request filed by another user against a job b may not read must be absent;"
                + " rows were " + rowIds);
        assertEquals(3, rowIds.size(), "exactly the three visible requests may be listed; rows were " + rowIds);

        Matcher footer = FOOTER.matcher(page.asNormalizedText());
        assertTrue(footer.find(), "the footer must report a page and a total; a blank total is the S-16 symptom."
                + " The footer area read: " + footerExcerpt(page));
        assertEquals(rowIds.size(), Integer.parseInt(footer.group(2)), "the footer total must equal the number of visible rows");

        // False-positive guard: the withheld request really is in the store, and an administrator
        // sees all four. Without this, an empty store would satisfy every assertion above.
        assertEquals(4, RunRequestService.get().list().size(), "fixture: all four requests must exist in the store");
        HtmlPage adminPage = (HtmlPage) get(webClient("admin"), "batch-control/requests/");
        assertTrue(requestRowIds(adminPage).contains(foreignOnJ), "an administrator must see the request that is withheld from b");
        assertEquals(4, requestRowIds(adminPage).size(), "an administrator must see all four requests");
    }

    /**
     * T-SEC-21: on its own request's detail page a discover-only caller gets a screen that is
     * actually rendered — the job link, the recent-run table and the approver dropdown are all
     * present. Against the broken code the same page was 200 with those three regions blank,
     * because one swallowed lookup nulls every expression on the page.
     */
    @Test
    public void t_sec_21_ownRequestDetailRendersJobLinkRecentRunsAndApproverDropdown()
            throws Exception {
        // The discover-only job must exist and be referenced by another request, so this page is
        // rendered in the same session state the finding was reported in.
        requestAs("u1", jobJ, "someone else's run of the restricted job");
        String id = requestAs("b", jobK, "month-end close").getId();

        HtmlPage page = (HtmlPage) get(webClient("b"), "batch-control/requests/" + id + "/");
        assertEquals(200, page.getWebResponse().getStatusCode(), "the detail page of one's own request must render");

        HtmlAnchor jobLink = page.getAnchors().stream()
                .filter(a -> a.getHrefAttribute().endsWith("/" + jobK.getUrl()))
                .findFirst().orElse(null);
        assertNotNull(jobLink, "the Job row must be a live link to the readable job (" + jobK.getUrl()
                + "); anchors were " + page.getAnchors().stream()
                        .map(HtmlAnchor::getHrefAttribute).collect(Collectors.toList()));
        assertEquals(jobK.getFullName(), jobLink.getTextContent().trim(), "the job link must be captioned with the job's full name");

        assertFalse(page.asNormalizedText().contains(NOT_AVAILABLE), "the readable job must not fall into the \"job is not available\" branch");
        List<?> runRows = page.getByXPath("//h2[contains(text(),'Recent Runs')]"
                + "/following-sibling::table[1]/tbody/tr");
        assertEquals(1, runRows.size(), "the recent-run table must render the one run open-k has; the page was: "
                + excerpt(page.asNormalizedText()));
        assertNotNull(page.getAnchors().stream()
                        .filter(a -> a.getHrefAttribute().endsWith("/" + jobK.getUrl() + "1/"))
                        .findFirst().orElse(null), "the recent-run row must link to build #1 of the job");

        // SPEC 3 (D-37): the change form edits the designated set through the repeated field
        // `approvers`, rendered either as a (multi-)select or as one checkbox per approver.
        List<org.htmlunit.html.HtmlForm> changeForms = page.getForms().stream()
                .filter(f -> f.getActionAttribute().contains("changeApprover"))
                .collect(Collectors.toList());
        List<String> options = new ArrayList<>();
        for (org.htmlunit.html.HtmlForm form : changeForms) {
            for (DomElement element : form.getElementsByTagName("select")) {
                if (element instanceof HtmlSelect && "approvers".equals(element.getAttribute("name"))) {
                    ((HtmlSelect) element).getOptions().forEach(o -> options.add(o.getValueAttribute()));
                }
            }
            for (DomElement element : form.getElementsByTagName("input")) {
                if ("approvers".equals(element.getAttribute("name"))
                        && "checkbox".equalsIgnoreCase(element.getAttribute("type"))) {
                    options.add(element.getAttribute("value"));
                }
            }
        }
        assertFalse(options.isEmpty(), "the requester's approver control (field `approvers`) must be rendered; forms on the page: "
                + page.getForms().stream().map(f -> f.getActionAttribute())
                        .collect(Collectors.toList()));
        assertTrue(options.containsAll(Arrays.asList("a1", "a2")), "the approver control must offer the configured approvers, but offered " + options);
    }

    /**
     * T-SEC-22: the requester of a request against a job they may not read gets the explicit
     * "job is not available" branch, not a blank region. The disclosure boundary is unchanged —
     * no run of the unreadable job is listed — which is the point of asserting both halves here.
     */
    @Test
    public void t_sec_22_requestAgainstUnreadableJobShowsTheNotAvailableBranch() throws Exception {
        // secret-j has a run, so "no rows" cannot be confused with "the job has no runs".
        j.assertBuildStatusSuccess(jobJ.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        j.waitUntilNoActivity();
        assertEquals(1, jobJ.getBuilds().size(), "fixture: the unreadable job must have a run that could leak");

        String id = requestAs("b", jobJ, "reconciliation on the restricted job").getId();

        HtmlPage page = (HtmlPage) get(webClient("b"), "batch-control/requests/" + id + "/");
        assertEquals(200, page.getWebResponse().getStatusCode(), "the requester must still reach their own request's detail page");
        String text = page.asNormalizedText();

        assertTrue(text.contains(NOT_AVAILABLE), "the page must say the job is not available instead of leaving the region"
                + " blank; the page was: " + excerpt(text));
        assertTrue(text.contains(jobJ.getFullName()), "the request's own data must still be rendered (the job name it names)");
        assertTrue(text.contains("reconciliation on the restricted job"), "the reason the requester typed must still be rendered - a blank page also"
                + " \"contains no runs\"");
        assertEquals(0, page.getByXPath("//h2[contains(text(),'Recent Runs')]"
                + "/following-sibling::table[1]/tbody/tr").size(), "no run of the unreadable job may be listed");
        assertNull(page.getAnchors().stream()
                        .filter(a -> a.getHrefAttribute().endsWith("/" + jobJ.getUrl()))
                        .findFirst().orElse(null), "the unreadable job must not be linked");
    }

    /**
     * T-SEC-23: across those three page loads nothing logs an {@code AccessDeniedException}.
     * The broken code produced roughly 190 log lines per swallowed lookup, and the log flood was
     * itself part of the finding. The positive assertions are repeated here on purpose: "no
     * exception was logged" is also true of a page that never rendered.
     */
    @Test
    public void t_sec_23_noAccessDeniedStackTracesWhileRenderingThoseScreens() throws Exception {
        String mineOnK = requestAs("b", jobK, "month-end close").getId();
        String mineOnJ = requestAs("b", jobJ, "reconciliation on the restricted job").getId();
        requestAs("u1", jobJ, "someone else's run of the restricted job");

        try (LogRecorder log = new LogRecorder()
                .record("hudson.ExpressionFactory2", Level.ALL).capture(1000)) {
            JenkinsRule.WebClient wc = webClient("b");

            HtmlPage list = (HtmlPage) get(wc, "batch-control/requests/");
            assertEquals(200, list.getWebResponse().getStatusCode());
            assertTrue(requestRowIds(list).contains(mineOnK), "guard: the list must have rendered rows while the log was being recorded");

            HtmlPage readable = (HtmlPage) get(wc, "batch-control/requests/" + mineOnK + "/");
            assertEquals(200, readable.getWebResponse().getStatusCode());
            assertTrue(readable.asNormalizedText().contains(jobK.getFullName()), "guard: the readable detail page must have rendered its job");

            HtmlPage unreadable = (HtmlPage) get(wc, "batch-control/requests/" + mineOnJ + "/");
            assertEquals(200, unreadable.getWebResponse().getStatusCode());
            assertTrue(unreadable.asNormalizedText().contains(NOT_AVAILABLE), "guard: the unreadable detail page must have rendered its branch");

            List<String> offenders = new ArrayList<>();
            for (LogRecord record : log.getRecords()) {
                if (mentionsAccessDenied(record)) {
                    offenders.add(record.getLevel() + " " + record.getMessage() + " / "
                            + (record.getThrown() == null ? "no throwable"
                                    : record.getThrown().toString()));
                }
            }
            assertTrue(offenders.isEmpty(), "no permission-denied throwable may reach the Jelly expression evaluator's log"
                    + " while these screens render, but " + offenders.size() + " did: " + offenders);
        }
    }

    /**
     * T-SEC-24: the helper the fix added returns null for a discover-only lookup rather than
     * throwing, returns the job for a reader, and returns null for a missing name, a null name
     * and an empty string. The item-level helper behaves the same, folders included.
     */
    @Test
    public void t_sec_24_visibilityHelpersReturnNullInsteadOfThrowing() {
        try (ACLContext ignored = as("b")) {
            assertNull(Visibility.findVisibleJob("secret-j"), "a discover-only job must resolve to null, not throw");
            assertNull(Visibility.findVisibleItem("secret-j"), "the item-level helper must do the same for a discover-only job");
            assertNull(Visibility.findVisibleItem("secret-folder"), "a discover-only FOLDER must resolve to null, not throw");

            assertSame(jobK, Visibility.findVisibleJob("open-k"), "a readable job must resolve to the job itself");
            assertSame(jobK, Visibility.findVisibleItem("open-k"), "the item-level helper must resolve a readable job too");

            assertNull(Visibility.findVisibleJob("no-such-job"), "a name that names nothing must resolve to null");
            assertNull(Visibility.findVisibleJob(null), "a null name must resolve to null");
            assertNull(Visibility.findVisibleJob(""), "an empty name must resolve to null");
            assertNull(Visibility.findVisibleItem("no-such-item"), "a name that names nothing must resolve to null (item helper)");
            assertNull(Visibility.findVisibleItem(null), "a null name must resolve to null (item helper)");
            assertNull(Visibility.findVisibleItem(""), "an empty name must resolve to null (item helper)");
        }

        // False-positive guard: a helper that returned null for everything would satisfy most of
        // the above, so pin that a reader really does get the objects back.
        try (ACLContext ignored = as("admin")) {
            assertSame(jobJ, Visibility.findVisibleJob("secret-j"), "guard: a reader must get the very job the discover-only caller could not");
            assertSame(folder, Visibility.findVisibleItem("secret-folder"), "guard: a reader must get the folder back from the item-level helper");
        }
    }

    /**
     * T-SEC-25: the run-request visibility predicate does not throw for a discover-only caller,
     * and the requester still sees their own request. The paired negative — a third party does
     * not see a request against a job they cannot read — is what keeps the fix from having simply
     * made everything visible.
     */
    @Test
    public void t_sec_25_runRequestVisibilityPredicateDoesNotThrowForDiscoverOnlyCaller() {
        RunRequest mine = requestAs("b", jobJ, "reconciliation on the restricted job");
        RunRequest foreign = requestAs("u1", jobJ, "someone else's run of the restricted job");

        try (ACLContext ignored = as("b")) {
            assertTrue(Visibility.canSeeRunRequest(mine), "the requester must see their own request even when the job is invisible to"
                    + " them, and the predicate must not throw getting there");
            assertFalse(Visibility.canSeeRunRequest(foreign), "a request of another user against a job b may not read must stay hidden");
        }
        try (ACLContext ignored = as("u1")) {
            assertTrue(Visibility.canSeeRunRequest(foreign), "guard: u1 (a reader of the job, and its requester) must see it");
        }
        try (ACLContext ignored = as("admin")) {
            assertTrue(Visibility.canSeeRunRequest(mine), "guard: a manager must see every request");
            assertTrue(Visibility.canSeeRunRequest(foreign));
        }
    }

    /**
     * T-SEC-26: the incident detail screen of a discover-only job renders its fields. The run
     * link is correctly absent (the caller may not read the job), but everything the incident
     * record itself holds must still be on the page — with the broken code the whole table went
     * blank because the run-link lookup threw.
     */
    @Test
    public void t_sec_26_incidentDetailRendersForADiscoverOnlyJob() throws Exception {
        jobJ.getBuildersList().add(new org.jvnet.hudson.test.FailureBuilder());
        j.assertBuildStatus(Result.FAILURE,
                jobJ.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        j.waitUntilNoActivity();

        Incident incident = IncidentService.get().list(YearMonth.now(BatchClock.clock())).stream()
                .filter(i -> "secret-j#1".equals(i.getRunId()))
                .findFirst().orElse(null);
        assertNotNull(incident, "fixture: the failed run of the discover-only job must have opened an incident");

        HtmlPage page = (HtmlPage) get(webClient("b"),
                "batch-control/incidents/" + incident.getId() + "/");
        assertEquals(200, page.getWebResponse().getStatusCode(), "the incident detail page must render for a discover-only caller");
        String text = page.asNormalizedText();

        assertTrue(text.contains(incident.getId()), "the incident id must be rendered; the page was: " + excerpt(text));
        assertTrue(text.contains("secret-j#1"), "the run id must be rendered as text even though it cannot be linked");
        assertTrue(text.contains("secret-j"), "the job full name must be rendered");
        assertTrue(text.contains("FAILURE"), "the result must be rendered");
        assertTrue(text.contains("OPEN"), "the status must be rendered");
        assertEquals(1, page.getByXPath("//h2[contains(text(),'History')]"
                + "/following-sibling::table[1]/tbody/tr").size(), "the transition history must hold the opening transition");

        // Coordinator ruling (e2e-03 part 2, SPEC section 6 usability line and DEF-12 / T-05-19):
        // b holds no Item/Read on secret-j, so a rerun request of it can never be submitted
        // (D-38a requires Item/Read) and the rerun form must not be offered at all. This replaces the former
        // expectation that the rerun form's approver dropdown renders for b; the S-16 symptom (a
        // blank screen) is still covered by the field assertions above.
        List<String> rerunForms = page.getForms().stream()
                .map(f -> f.getActionAttribute())
                .filter(action -> action != null && action.contains("rerun"))
                .collect(Collectors.toList());
        assertTrue(rerunForms.isEmpty(), "the rerun form must not be offered to a discover-only caller (no Item/Read);"
                + " rerun forms on the page: " + rerunForms);
        assertTrue(page.getAnchors().stream().noneMatch(a -> a.getHrefAttribute().contains("/rerun")),
                "no rerun link may be offered to a discover-only caller");

        // Security guard (kept): a rerun POSTed anyway by b is refused and creates no request.
        java.util.Set<String> requestsBefore = ApproverFormFixtures.runRequestIds();
        List<org.htmlunit.util.NameValuePair> rerun = new java.util.ArrayList<>();
        rerun.add(new org.htmlunit.util.NameValuePair("approvers", "a1"));
        rerun.add(new org.htmlunit.util.NameValuePair("approver", "a1"));
        rerun.add(new org.htmlunit.util.NameValuePair("reason", "rerun by a discover-only caller"));
        org.htmlunit.WebResponse refused = ApproverFormFixtures.post(j, "b",
                "batch-control/incidents/" + incident.getId() + "/rerun", rerun);
        assertTrue(refused.getStatusCode() >= 400, "a rerun POST by a discover-only caller must be refused, got HTTP " + refused.getStatusCode());
        assertEquals(requestsBefore, ApproverFormFixtures.runRequestIds(), "the refused rerun must create no run request");
        Incident reloaded = IncidentService.get().load(incident.getId());
        assertTrue(reloaded.getRerunRequestIds() == null || reloaded.getRerunRequestIds().isEmpty(),
                "no rerun request id may be linked to the incident");

        // The disclosure boundary, which is what this row can assert without ruling on the open
        // question recorded in matrix note 48: whatever the page links to, the caller still cannot
        // read the job or the build behind it.
        //
        // NOT asserted here on purpose: whether the Run cell should be a link at all. Observed
        // behaviour on this branch is that it IS one (href job/secret-j/1/), built from the stored
        // run id with no permission check, while RequestItem#getExecutedRunUrl deliberately renders
        // plain text for exactly this case ("or when the job is not visible to the caller (P-09)").
        // The two screens of the same plugin disagree; a dead link that looks like a live one is the
        // shape of problem U-02 was filed about. Left for the maintainer to rule on - see note 48.
        assertTrue(get(webClient("b"), "job/secret-j/1/").getWebResponse().getStatusCode() >= 400, "whatever the incident screen renders, b must still be refused the build itself");
        assertTrue(get(webClient("b"), "job/secret-j/").getWebResponse().getStatusCode() >= 400, "whatever the incident screen renders, b must still be refused the job itself");
    }

    // ---------------------------------------------------------------- helpers

    private JenkinsRule.WebClient webClient(String userId) throws Exception {
        return j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
    }

    private Page get(JenkinsRule.WebClient wc, String relative) throws Exception {
        return wc.getPage(new WebRequest(new URL(j.getURL(), relative), HttpMethod.GET));
    }

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    /**
     * Files a run request as {@code userId}. SPEC item 5 (D-38a) requires Item/Read on the job at
     * filing time, so a request of b's against secret-j is filed while b still holds Item/Read on
     * it, and the measured strategy (b discover-only on secret-j) is restored straight after: the
     * screen rows measure a requester who has since lost Item/Read, which is the S-16 situation.
     */
    private RunRequest requestAs(String userId, Job<?, ?> job, String reason) {
        boolean lostReadLater = "b".equals(userId) && job == jobJ;
        if (lostReadLater) {
            j.jenkins.setAuthorizationStrategy(strategy(true));
        }
        try (ACLContext ignored = as(userId)) {
            return RunRequestService.get().create(job, new LinkedHashMap<>(), reason, "a1");
        } finally {
            if (lostReadLater) {
                j.jenkins.setAuthorizationStrategy(strategy(false));
                try (ACLContext ignored = as("b")) {
                    assertFalse(jobJ.hasPermission(Item.READ), "fixture: b is discover-only on secret-j again");
                }
            }
        }
    }

    /**
     * The class's authorization: b holds Request and ViewHistory, Item/Read on open-k and only
     * Item/Discover on secret-j (unless {@code bReadsSecretJ}, used while b files a request);
     * u1 is a requester (Request, Item/Read); a1 the approver.
     */
    private MockAuthorizationStrategy strategy(boolean bReadsSecretJ) {
        MockAuthorizationStrategy strategy = new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                // b: the caller of this finding. No Item/Read anywhere by default.
                .grant(Jenkins.READ, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.VIEW_HISTORY).everywhere().to("b")
                .grant(Item.DISCOVER).onItems(jobJ).to("b")
                .grant(Item.DISCOVER).onItems(folder).to("b")
                .grant(Item.READ).onItems(jobK).to("b")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to("a1");
        if (bReadsSecretJ) {
            strategy.grant(Item.READ).onItems(jobJ).to("b");
        }
        return strategy;
    }

    /** The request ids the list screen actually rendered as rows. */
    private static List<String> requestRowIds(HtmlPage page) {
        return page.getByXPath("//table[contains(@class,'jenkins-table')]/tbody/tr/td[1]//a")
                .stream()
                .map(node -> ((DomElement) node).getTextContent().trim())
                .collect(Collectors.toList());
    }

    private static boolean mentionsAccessDenied(LogRecord record) {
        if (record.getMessage() != null && record.getMessage().contains("AccessDenied")) {
            return true;
        }
        for (Throwable t = record.getThrown(); t != null; t = t.getCause()) {
            if (t.getClass().getName().contains("AccessDenied")) {
                return true;
            }
        }
        return false;
    }

    private static String footerExcerpt(HtmlPage page) {
        String text = page.asNormalizedText();
        int idx = text.indexOf("Page");
        return idx < 0 ? excerpt(text) : excerpt(text.substring(idx));
    }

    private static String excerpt(String text) {
        return text.length() <= 600 ? text : text.substring(0, 600) + "...";
    }
}
