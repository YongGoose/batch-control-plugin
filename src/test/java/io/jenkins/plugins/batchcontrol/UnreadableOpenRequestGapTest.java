package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.decideActivation;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.recordsFor;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivationOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An open request whose stored file cannot be read costs only itself: the pages, the pending counts, the
 * stored expiry and the invalidations of the other requests go on, and the request is handled as soon as its
 * file can be read again. Matrix rows T-GAP-416 .. T-GAP-421 (note 281).
 *
 * <p>Basis: SPEC 7 ("승인 대기 요청은 설정된 기간이 지나면 자동 만료", "만료 시 상태가 EXPIRED로 바뀌고 이력에 남는다",
 * "PENDING/APPROVED 요청의 대상 잡이 rename 또는 move되면 요청은 상태 INVALIDATED로 종료되고 이력에 남는다", D-21); SPEC 6a
 * ("an approved HOLD ... is never undone ... by a stale pending ACTIVATE (approving one request invalidates the
 * job's other pending activation requests)", activation requests are "decided like a run request"); SPEC 8 (the
 * pending timeout of change requests, T-08-12); SPEC 4 (the per-minute expiry work); SPEC 8 line 176 (D-67:
 * pending counts as tab badges); SPEC 6 usability (a page is not broken by one record); LIMITATIONS 30 and 32
 * (a request does not follow its job; an unreadable request file is skipped with a warning instead of breaking
 * a listing); DECISIONS D-21. The scenarios are the ones relayed by the coordinator (rows 5 to 7 of the brief);
 * T-GAP-420 (the RT-03 swap with an unreadable request) is added beyond it.
 *
 * <p>Fault injection only through the store on disk: a request file replaced with the first half of its own
 * XML (premise: not readable XML), and later written back byte for byte. Time never passes for real: the
 * plugin clock ({@link BatchClock}) is fixed and moved, and {@link ExpiryPeriodicWork#doRun()} is invoked
 * directly (matrix notes 1 and 2). Whether the work throws is printed, not asserted.
 *
 * <p>Fixture: run and change control on, pendingTimeoutHours 1; r (Overall/Read, Item/Read, Item/Build,
 * BatchControl/Request, RequestGrant) requests, a1 (Overall/Read, Item/Read, Approve; the only approver)
 * decides, admin renames and creates jobs.
 *
 * <p>Written from docs/SPEC.md items 4, 6, 6a, 7 and 8, docs/DECISIONS.md D-21, docs/LIMITATIONS.md items 30 and
 * 32 and docs/ARCHITECTURE.md section 5 (store layout) only (no src/main knowledge).
 */
@WithJenkins
public class UnreadableOpenRequestGapTest {

    private static final Instant T0 = Instant.parse("2026-10-06T00:00:00Z");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        at(T0);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST, BatchControlPermissions.REQUEST_GRANT)
                .everywhere().to("r")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.setPendingTimeoutHours(1);
        cfg.save();
    }

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
    }

    /**
     * T-GAP-416 (SPEC 7, SPEC 4, D-67, LIMITATIONS 32): pending run requests R1 and R2 of r on the
     * approval-required {@code uo-run}, created at T0; R1's file {@code requests/run/<R1>.xml} is replaced with
     * damaged XML. a1 opens {@code batch-control/}: HTTP 200 and the requests tab badge says 1;
     * {@code batch-control/requests/} answers 200 and links R2. The expiry work at T0+2 h: R2 is EXPIRED and
     * R1's file is untouched (byte for byte). R1's file is written back; the work at T0+2 h 1 min: R1 is
     * EXPIRED. Nothing runs.
     */
    @Test
    public void t_gap_416_anUnreadableRunRequestCostsTheOtherOneNeitherItsBadgeNorItsExpiry() throws Exception {
        FreeStyleProject job = controlled("uo-run");
        String r1 = as("r", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "uo run one", "a1").getId());
        String r2 = as("r", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "uo run two", "a1").getId());
        assertOpenRequestIsolation("requests", "requests/run/", r1, r2,
                id -> RunRequestService.get().load(id).getStatus());
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "SPEC 7: an expired request never runs");
    }

    /**
     * T-GAP-417 (SPEC 8 / T-08-12, SPEC 4, D-67, LIMITATIONS 32): the same with r's pending CONFIGURE change
     * requests G1 and G2 on {@code uo-grant} ({@code requests/grant/<id>.xml}, the grants tab badge,
     * {@code batch-control/grants/}).
     */
    @Test
    public void t_gap_417_anUnreadableChangeRequestCostsTheOtherOneNeitherItsBadgeNorItsExpiry() throws Exception {
        j.createFreeStyleProject("uo-grant");
        String g1 = as("r", () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "uo-grant"),
                List.of(GrantAction.CONFIGURE), 30, "uo grant one", "a1").getId());
        String g2 = as("r", () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "uo-grant"),
                List.of(GrantAction.CONFIGURE), 30, "uo grant two", "a1").getId());
        assertOpenRequestIsolation("grants", "requests/grant/", g1, g2,
                id -> GrantRequestService.get().load(id).getStatus());
    }

    /**
     * T-GAP-418 (SPEC 6a "decided like a run request", SPEC 7, SPEC 4, D-67, LIMITATIONS 32): the same with r's
     * pending ACTIVATE requests A1 and A2 on {@code uo-act}, which is not activated
     * ({@code activation-requests/<id>.xml}, the activations tab badge, {@code batch-control/activations/}).
     * The job stays not activated.
     */
    @Test
    public void t_gap_418_anUnreadableActivationRequestCostsTheOtherOneNeitherItsBadgeNorItsExpiry() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("uo-act");
        assertFalse(isActivated(job), "premise (SPEC 6a): a job created under run control starts not activated");
        String a1 = as("r", () -> ActivationService.get().create(job, ActivationRequest.Action.ACTIVATE, "uo activation one",
                List.of("a1")).getId());
        String a2 = as("r", () -> ActivationService.get().create(job, ActivationRequest.Action.ACTIVATE, "uo activation two",
                List.of("a1")).getId());
        assertOpenRequestIsolation("activations", "activation-requests/", a1, a2,
                id -> ActivationService.get().load(id).getStatus());
        assertFalse(isActivated(job), "SPEC 6a: an expired ACTIVATE request activates nothing");
    }

    /**
     * T-GAP-419 (SPEC 7 D-21, LIMITATIONS 30): pending run requests R1 and R2 of r on the approval-required
     * {@code ren-j} and R3 on {@code ren-k}; the files of R1 and R3 are replaced with damaged XML; the
     * administrator renames {@code ren-j} to {@code ren-j2}. R2 is INVALIDATED (its reason mentions the rename).
     * R1's file is written back: a1's approval of R1 is refused (4xx) and R1 ends INVALIDATED with a reason that
     * mentions the rename; nothing runs. R3's file is written back: R3 is PENDING, still after an expiry work
     * before its timeout. Guard: a1's approval of R3 is accepted and {@code ren-k} runs once.
     */
    @Test
    public void t_gap_419_aRequestUnreadableDuringARenameIsInvalidatedOnceReadable() throws Exception {
        FreeStyleProject renamed = controlled("ren-j");
        FreeStyleProject other = controlled("ren-k");
        String r1 = as("r", () -> RunRequestService.get().create(renamed, new LinkedHashMap<>(), "ren one", "a1").getId());
        String r2 = as("r", () -> RunRequestService.get().create(renamed, new LinkedHashMap<>(), "ren two", "a1").getId());
        String r3 = as("r", () -> RunRequestService.get().create(other, new LinkedHashMap<>(), "ren three", "a1").getId());
        Path f1 = store().resolve("requests/run/" + r1 + ".xml");
        Path f3 = store().resolve("requests/run/" + r3 + ".xml");
        byte[] original1 = damage(f1);
        byte[] original3 = damage(f3);

        as("admin", () -> {
            renamed.renameTo("ren-j2");
            return null;
        });
        assertNotNull(j.jenkins.getItemByFullName("ren-j2"), "premise: ren-j is renamed to ren-j2");
        RunRequest second = RunRequestService.get().load(r2);
        assertEquals(RequestStatus.INVALIDATED, second.getStatus(), "SPEC 7 D-21: the readable request of the renamed job is INVALIDATED");
        assertMentionsRename(second, "guard: the readable request's reason");

        Files.write(f1, original1);
        assertClientError(decideRun(j, "a1", r1, "approve", "approved after the rename"),
                "SPEC 7 D-21: approving a request whose job was renamed while its file could not be read");
        RunRequest first = RunRequestService.get().load(r1);
        assertEquals(RequestStatus.INVALIDATED, first.getStatus(), "SPEC 7 D-21: the request ends INVALIDATED once readable");
        assertNull(first.getExecutedRunId(), "the refused request has no run");
        assertMentionsRename(first, "the request unreadable during the rename");
        j.waitUntilNoActivity();
        assertTrue(renamed.getBuilds().isEmpty(), "D-21: the renamed job does not run on a request its approver reviewed under another name");
        assertEquals(0, j.jenkins.getQueue().getItems().length, "nothing is queued");

        Files.write(f3, original3);
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(r3).getStatus(),
                "a request on another job, unreadable during the rename, is PENDING once readable");
        at(T0.plus(Duration.ofMinutes(5)));
        runExpiry("T-GAP-419");
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(r3).getStatus(),
                "the periodic work before its timeout leaves the request on the other job PENDING");
        assertSuccess(decideRun(j, "a1", r3, "approve", "ok"), "guard: a1's approval of the request on the other job");
        j.waitUntilNoActivity();
        assertEquals(1, other.getBuilds().size(), "guard: the approved request on the other job runs once");
    }

    /**
     * T-GAP-420 (SPEC 7 D-21, red-team RT-03 "swap"): r's pending run request R1 on the approval-required
     * {@code swap-j}; R1's file is replaced with damaged XML; the administrator renames {@code swap-j} to
     * {@code swap-j2} and creates a new approval-required job at the name {@code swap-j}. R1's file is written
     * back: a1's approval of R1 is refused (4xx), R1 ends INVALIDATED with a reason that mentions the rename, and
     * neither the new {@code swap-j} nor {@code swap-j2} runs or is queued.
     */
    @Test
    public void t_gap_420_aRequestUnreadableDuringARenameNeverRunsTheJobNowAtItsName() throws Exception {
        FreeStyleProject reviewed = controlled("swap-j");
        String r1 = as("r", () -> RunRequestService.get().create(reviewed, new LinkedHashMap<>(), "swap one", "a1").getId());
        Path f1 = store().resolve("requests/run/" + r1 + ".xml");
        byte[] original = damage(f1);

        as("admin", () -> {
            reviewed.renameTo("swap-j2");
            return null;
        });
        FreeStyleProject impostor = controlled("swap-j");
        assertTrue(impostor != reviewed && reviewed.getFullName().equals("swap-j2"), "premise: another job now has the name swap-j");

        Files.write(f1, original);
        assertClientError(decideRun(j, "a1", r1, "approve", "approved after the swap"),
                "SPEC 7 D-21: approving a request whose job was renamed while its file could not be read");
        RunRequest first = RunRequestService.get().load(r1);
        assertEquals(RequestStatus.INVALIDATED, first.getStatus(), "SPEC 7 D-21: the request ends INVALIDATED");
        assertNull(first.getExecutedRunId(), "the refused request has no run");
        assertMentionsRename(first, "the request unreadable during the rename");
        j.waitUntilNoActivity();
        assertTrue(impostor.getBuilds().isEmpty(), "RT-03: the job now at the reviewed name never runs on the old approval");
        assertTrue(reviewed.getBuilds().isEmpty(), "D-21: the renamed job does not run either");
        assertEquals(0, j.jenkins.getQueue().getItems().length, "nothing is queued");
    }

    /**
     * T-GAP-421 (SPEC 6a "an approved HOLD ... is never undone ... by a stale pending ACTIVATE (approving one
     * request invalidates the job's other pending activation requests)"): on {@code sup-j} (not activated, timer
     * and upstream switches off) r files ACTIVATE requests A0, A1 and A2; A1's file is replaced with damaged XML;
     * a1 approves A0 (the job is activated; guard: the readable A2 is INVALIDATED); r files a HOLD and a1 approves
     * it (the job is not activated). A1's file is written back: a1's approval of A1 is refused (4xx) and A1 ends
     * INVALIDATED as superseded (its reason says so). The job stays on hold: one ACTIVATED and one HELD record,
     * and its timer is refused.
     */
    @Test
    public void t_gap_421_anActivateUnreadableWhenAHoldWasApprovedCannotUndoTheHold() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("sup-j");
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(job, property);
        assertFalse(isActivated(job), "premise (SPEC 6a): sup-j starts not activated");
        String a0 = submitActivationOk(j, "r", job, "ACTIVATE", "sup zero", "a1");
        String a1 = submitActivationOk(j, "r", job, "ACTIVATE", "sup one", "a1");
        String a2 = submitActivationOk(j, "r", job, "ACTIVATE", "sup two", "a1");
        Path f1 = store().resolve("activation-requests/" + a1 + ".xml");
        byte[] original = damage(f1);

        assertSuccess(decideActivation(j, "a1", a0, "approve", "go live"), "fixture: a1 approves A0");
        assertTrue(isActivated(job), "premise: the approved ACTIVATE activates sup-j");
        ActivationRequest readable = ActivationService.get().load(a2);
        assertEquals(RequestStatus.INVALIDATED, readable.getStatus(), "guard (SPEC 6a): the readable pending ACTIVATE is invalidated");
        System.out.println("T-GAP-421 observation: the readable superseded request's reason: " + readable.getDecisionComment());
        String hold = submitActivationOk(j, "r", job, "HOLD", "sup hold", "a1");
        assertSuccess(decideActivation(j, "a1", hold, "approve", "hold it"), "fixture: a1 approves the HOLD");
        assertFalse(isActivated(job), "premise: the approved HOLD puts sup-j on hold");

        Files.write(f1, original);
        assertClientError(decideActivation(j, "a1", a1, "approve", "late"),
                "SPEC 6a: approving an ACTIVATE that was unreadable when the job's HOLD was approved");
        ActivationRequest stale = ActivationService.get().load(a1);
        assertEquals(RequestStatus.INVALIDATED, stale.getStatus(), "SPEC 6a: the stale ACTIVATE ends INVALIDATED");
        String reason = String.valueOf(stale.getDecisionComment());
        System.out.println("T-GAP-421 observation: the stale request's reason: " + reason);
        assertTrue(reason.toLowerCase(Locale.ROOT).contains("supersed"), "the stale ACTIVATE ends INVALIDATED as superseded: " + reason);
        assertFalse(isActivated(job), "SPEC 6a: a stale ACTIVATE never undoes an approved HOLD");
        assertEquals(1, recordsFor(ChangeType.ACTIVATED, "sup-j").size(), "only A0 activated the job");
        assertEquals(1, recordsFor(ChangeType.HELD, "sup-j").size(), "the HOLD is recorded once");
        int next = job.getNextBuildNumber();
        int builds = job.getBuilds().size();
        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "SPEC 6a: the held job's timer is refused");
        assertBlocked(j, job, next, builds);
    }

    // ------------------------------------------------------------------ helpers

    private interface StatusOf {
        RequestStatus of(String id);
    }

    /**
     * The common body of T-GAP-416..418: {@code damaged} and {@code healthy} are pending requests created at T0
     * and decided by a1; {@code damaged}'s file under {@code dir} is replaced with damaged XML.
     */
    private void assertOpenRequestIsolation(String section, String dir, String damaged, String healthy, StatusOf status) throws Exception {
        assertEquals("2", badge(overview("a1"), section), "premise (D-67): a1's " + section + " badge counts both pending requests");
        Path file = store().resolve(dir + damaged + ".xml");
        byte[] original = damage(file);
        byte[] broken = Files.readAllBytes(file);

        HtmlPage page = overview("a1");
        assertEquals("1", badge(page, section), "D-67, LIMITATIONS 32: the " + section + " badge counts the readable pending request only");
        Page list = ApproverFormFixtures.client(j, "a1").getPage(new URL(j.getURL(), "batch-control/" + section + "/"));
        assertEquals(200, list.getWebResponse().getStatusCode(), "SPEC 6 usability, LIMITATIONS 32: the " + section
                + " list still opens: " + excerpt(list.getWebResponse().getContentAsString()));
        assertTrue(list.getWebResponse().getContentAsString().contains(healthy), "the " + section + " list still links the readable request "
                + healthy + ": " + excerpt(list.getWebResponse().getContentAsString()));

        at(T0.plus(Duration.ofHours(2)));
        runExpiry("first run");
        assertEquals(RequestStatus.EXPIRED, status.of(healthy), "SPEC 7: the readable pending request past the timeout is EXPIRED");
        assertArrayEquals(broken, Files.readAllBytes(file), "the unreadable request's file is left untouched");

        Files.write(file, original);
        at(T0.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(1)));
        runExpiry("second run");
        assertEquals(RequestStatus.EXPIRED, status.of(damaged), "SPEC 7: once readable, the request past the timeout is EXPIRED");
        assertEquals(RequestStatus.EXPIRED, status.of(healthy), "the other request stays EXPIRED");
        assertNull(badge(overview("a1"), section), "no pending request is left to count");
    }

    /** Replaces {@code file} with the first half of its own XML (premise: not readable XML); returns the original bytes. */
    private static byte[] damage(Path file) throws Exception {
        assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): the request is stored at " + file);
        byte[] original = Files.readAllBytes(file);
        String xml = new String(original, StandardCharsets.UTF_8);
        String damaged = xml.substring(0, xml.length() / 2);
        assertThrows(RuntimeException.class, () -> Jenkins.XSTREAM2.fromXML(damaged), "premise: the damaged copy is not readable XML");
        Files.writeString(file, damaged, StandardCharsets.UTF_8);
        return original;
    }

    private static void assertMentionsRename(RunRequest request, String what) {
        String reason = String.valueOf(request.getDecisionComment());
        System.out.println("T-GAP observation: " + what + ": " + reason);
        assertTrue(reason.toLowerCase(Locale.ROOT).contains("renam"), what + " mentions the rename: " + reason);
    }

    private FreeStyleProject controlled(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    private HtmlPage overview(String user) throws Exception {
        Page p = ApproverFormFixtures.client(j, user).getPage(new URL(j.getURL(), "batch-control/"));
        assertEquals(200, p.getWebResponse().getStatusCode(), user + ": batch-control/ answers 200: "
                + excerpt(p.getWebResponse().getContentAsString()));
        assertTrue(p instanceof HtmlPage, "batch-control/ renders HTML");
        return (HtmlPage) p;
    }

    /** The badge text of a tab, or null when the tab has no (non-empty, non-zero) badge (as SectionTabsTest reads it). */
    private static String badge(HtmlPage page, String section) {
        DomElement tab = null;
        for (Object o : page.querySelectorAll("nav[data-batch-control-tabs] a[data-batch-control-tab]")) {
            if (section.equals(((DomElement) o).getAttribute("data-batch-control-tab"))) {
                tab = (DomElement) o;
            }
        }
        if (tab == null) {
            return null;
        }
        for (HtmlElement e : tab.getHtmlElementDescendants()) {
            if (e.getAttribute("class").contains("badge")) {
                String text = e.asNormalizedText().trim();
                return text.isEmpty() || "0".equals(text) ? null : text;
            }
        }
        return null;
    }

    private static void runExpiry(String label) {
        try {
            ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
        } catch (Throwable t) {
            System.out.println("Expiry work (" + label + ") threw " + t);
        }
    }

    private Path store() {
        return j.jenkins.getRootDir().toPath().resolve("batch-control");
    }

    private static void at(Instant instant) {
        BatchClock.setForTest(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private interface Body<T> {
        T run() throws Exception;
    }

    private static <T> T as(String userId, Body<T> body) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(userId, true).impersonate2())) {
            return body.run();
        }
    }
}
