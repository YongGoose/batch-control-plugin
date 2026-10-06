package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.ops.RetentionPeriodicWork;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 3, scenario L3-08: retention edge cases. Matrix rows T-GAP-329 .. T-GAP-332 (note 279).
 *
 * <p>Basis: ARCHITECTURE 5 "retention never deletes a grant file whose changedItems is non-empty", "Retention
 * also deletes closed requests and ended grants older than the first kept month", "디코딩 시 경로 탈출(..)
 * 검증", {@code incidents/index/YYYY-MM.jsonl}; D-58a (5) "retention keeps a grant file while that list is
 * non-empty"; SPEC 4 line 76 "Closed requests and grants past the retention period are deleted by
 * retention" and "A line longer than 1 MiB is skipped on every read path"; SPEC 4 line 75 and D-43 (month
 * names are ASCII {@code YYYY-MM}; pre-release names are not read); SPEC 12 (retention deletes expired month
 * files; monthly summary). The ids an index line may name follow D-68 / S-39-01 (an id that is not a store
 * identifier names no record and never reaches another file).
 *
 * <p>{@code retentionMonths = 1}; the plugin clock ({@link BatchClock}) is moved to three months ago to
 * create old records; {@link RetentionPeriodicWork} is run directly (RetentionClosedRequestsTest).
 *
 * <p>Batch Control matrix strategy, change control and run control on; u1 and u2 hold RequestGrant, a1
 * approves; viewer holds ViewHistory.
 *
 * <p>Written from docs/SPEC.md items 4 and 12, docs/DECISIONS.md D-43, D-58a and D-68 and
 * docs/ARCHITECTURE.md section 5 only (no src/main knowledge).
 */
@WithJenkins
public class RetentionGapTest {

    private JenkinsRule j;
    private Instant old;
    private YearMonth oldMonth;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "u2", "a1", "viewer"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u2"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        strategy.add(BatchControlPermissions.VIEW_HISTORY, PermissionEntry.user("viewer"));
        j.jenkins.setAuthorizationStrategy(strategy);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "admin"));
        cfg.setRetentionMonths(1);
        cfg.save();
        oldMonth = YearMonth.now(ZoneOffset.UTC).minusMonths(3);
        old = oldMonth.atDay(15).atTime(12, 0).toInstant(ZoneOffset.UTC);
    }

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
    }

    /**
     * T-GAP-329 (L3-08; ARCHITECTURE 5, D-58a (5)): three months ago u1's CONFIGURE window on {@code rj}
     * was approved and u1 saved {@code rj} under it ({@code rj} changed under a grant); u2's equally old
     * window on {@code rk} changed nothing. After retention, u1's grant file and its request file are still
     * there and the monitor still lists {@code rj}; u2's grant file and request file are deleted (guard).
     * After the administrator marks {@code rj} reviewed, the next retention run deletes u1's files too.
     */
    @Test
    public void t_gap_329_retentionKeepsAWindowWhoseItemIsStillChanged() throws Exception {
        FreeStyleProject rj = uncontrolled(j.createFreeStyleProject("rj"));
        uncontrolled(j.createFreeStyleProject("rk"));
        BatchClock.setForTest(Clock.fixed(old, ZoneOffset.UTC));
        String changed = openWindow("u1", "rj");
        String unchanged = openWindow("u2", "rk");
        saveAs("u1", rj, "edited three months ago");
        BatchClock.reset();
        assertTrue(WindowStoreFaultGapTest.monitorItems(j).contains("rj"), "premise: the monitor lists rj as changed");
        Path changedGrant = store().resolve("grants/" + changed + ".xml");
        Path changedRequest = store().resolve("requests/grant/" + changed + ".xml");
        Path unchangedGrant = store().resolve("grants/" + unchanged + ".xml");
        Path unchangedRequest = store().resolve("requests/grant/" + unchanged + ".xml");
        for (Path p : List.of(changedGrant, changedRequest, unchangedGrant, unchangedRequest)) {
            assertTrue(Files.isRegularFile(p), "premise (ARCHITECTURE 5: a window's grant file has its request's id): " + p);
        }

        retention();
        assertTrue(Files.isRegularFile(changedGrant), "ARCHITECTURE 5: the grant file whose changedItems is non-empty is kept");
        assertTrue(Files.isRegularFile(changedRequest), "its grant request file is kept with it");
        assertTrue(WindowStoreFaultGapTest.monitorItems(j).contains("rj"), "rj is still listed as changed");
        assertFalse(Files.exists(unchangedGrant), "guard (SPEC 4): an equally old ended window without changes is deleted");
        assertFalse(Files.exists(unchangedRequest), "guard: and its request");

        JenkinsRule.WebClient admin = ApproverFormFixtures.client(j, "admin");
        int review = admin.getPage(new WebRequest(new URL(admin.createCrumbedUrl("manage/administrativeMonitor/batch-control-strategy/markReviewed")
                .toExternalForm() + "&item=rj"), HttpMethod.POST)).getWebResponse().getStatusCode();
        assertTrue(review < 400, "fixture: the administrator marks rj reviewed, got " + review);
        assertFalse(WindowStoreFaultGapTest.monitorItems(j).contains("rj"), "premise: rj is no longer listed");
        retention();
        assertFalse(Files.exists(changedGrant), "after the review the next retention run deletes the window");
        assertFalse(Files.exists(changedRequest), "and its request");
    }

    /**
     * T-GAP-330 (L3-08; SPEC 4 line 76 "A line longer than 1 MiB is skipped on every read path", SPEC 12):
     * an incident created three months ago; its month's index {@code incidents/index/<old month>.jsonl}
     * gets a line longer than 1 MiB before the valid line. Retention completes, deletes that month's index
     * and the valid line's incident file, and writes a RETENTION record.
     */
    @Test
    public void t_gap_330_retentionSkipsAnOverlongIndexLineAndStillDeletesTheMonth() throws Exception {
        Incident incident = oldIncident("ret-long");
        Path index = indexFile(oldMonth);
        String valid = Files.readString(index, StandardCharsets.UTF_8);
        String longLine = "{\"id\":\"" + "x".repeat(1_100_000) + "\"}\n";
        Files.writeString(index, longLine + valid, StandardCharsets.UTF_8);
        assertTrue(Files.size(index) > 1024 * 1024, "premise: the index holds a line longer than 1 MiB");
        Path incidentFile = store().resolve("incidents/" + incident.getId() + ".xml");
        assertTrue(Files.isRegularFile(incidentFile), "premise: the incident is stored at " + incidentFile);
        int retentionRecords = ApproverFormFixtures.records(ChangeType.RETENTION).size();

        retention();
        assertFalse(Files.exists(index), "SPEC 12: the expired month's index is deleted");
        assertFalse(Files.exists(incidentFile), "the valid line's incident file is deleted");
        assertTrue(ApproverFormFixtures.records(ChangeType.RETENTION).size() > retentionRecords, "SPEC 12: a RETENTION record is written");
    }

    /**
     * T-GAP-331 (L3-08; ARCHITECTURE 5 path-escape check, S-39-01): the old month's incident index gets two
     * lines copied from its real line with the ids {@code ../sentinel} and {@code a.b}; the files
     * {@code batch-control/sentinel.xml} and {@code incidents/a.b.xml} are planted. Retention deletes the
     * month's index and the real incident's file, and neither planted file.
     */
    @Test
    public void t_gap_331_retentionNeverFollowsAnIndexIdOutOfItsDirectory() throws Exception {
        Incident incident = oldIncident("ret-escape");
        Path index = indexFile(oldMonth);
        String line = Files.readAllLines(index, StandardCharsets.UTF_8).stream().filter(l -> l.contains(incident.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("premise: the index names the incident"));
        Files.writeString(index, line.replace(incident.getId(), "../sentinel") + "\n" + line.replace(incident.getId(), "a.b") + "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        Path sentinel = store().resolve("sentinel.xml");
        Path dotted = store().resolve("incidents/a.b.xml");
        Files.writeString(sentinel, "<planted/>", StandardCharsets.UTF_8);
        Files.writeString(dotted, "<planted/>", StandardCharsets.UTF_8);

        retention();
        assertFalse(Files.exists(index), "the expired month's index is deleted");
        assertFalse(Files.exists(store().resolve("incidents/" + incident.getId() + ".xml")), "guard: the real incident's file is deleted");
        assertTrue(Files.exists(sentinel), "ARCHITECTURE 5: an id '../sentinel' never reaches a file outside incidents/");
        assertTrue(Files.exists(dotted), "S-39-01: an id 'a.b' is not an incident identifier and reaches no file");
    }

    /**
     * T-GAP-332 (L3-08; SPEC 4 line 75 / D-43 month names are {@code YYYY-MM} in ASCII and other names are
     * not read; SPEC 12): the foreign files {@code runs/notes.jsonl}, {@code runs/2026-13.jsonl} and
     * {@code changes/2026-1.jsonl} lie in the store. viewer's history screen, change list and monthly
     * summary answer 200, no page offers {@code notes}, {@code 2026-13} or {@code 2026-1} as a month, and
     * retention neither deletes the files nor fails (a RETENTION record of the expired month is still
     * written, guard: an old run month is deleted).
     */
    @Test
    public void t_gap_332_foreignMonthFilesAreIgnoredByScreensAndRetention() throws Exception {
        Path notes = store().resolve("runs/notes.jsonl");
        Path badMonth = store().resolve("runs/2026-13.jsonl");
        Path shortMonth = store().resolve("changes/2026-1.jsonl");
        StoreDataFixtures.RunLine template = StoreDataFixtures.runLineTemplate();
        StoreDataFixtures.writeRunMonth(template, oldMonth, 3, old, old.plusSeconds(60), "ret-old", 1);
        Path oldRuns = StoreDataFixtures.runsFile(oldMonth);
        String line = template.render("foreign#1", "foreign", 1, Instant.now());
        for (Path p : List.of(notes, badMonth, shortMonth)) {
            Files.createDirectories(p.getParent());
            Files.writeString(p, line + "\n", StandardCharsets.UTF_8);
        }
        String month = StoreDataFixtures.monthName(YearMonth.now(BatchClock.clock()));
        for (String path : new String[] {"batch-control/history/", "batch-control/changes/", "batch-control/history/?kind=changes",
                "batch-control/history/summary?month=" + month}) {
            WebResponse page = get(path);
            assertEquals(200, page.getStatusCode(), path + " renders: " + excerpt(page.getContentAsString()));
            String html = page.getContentAsString();
            for (String foreign : new String[] {"month=notes", "month=2026-13", "month=2026-1\"", "month=2026-1&", "\"2026-13\"", "\"notes\""}) {
                assertFalse(html.contains(foreign), "D-43: " + path + " offers no foreign month (" + foreign + ")");
            }
        }
        int retentionRecords = ApproverFormFixtures.records(ChangeType.RETENTION).size();
        retention();
        for (Path p : List.of(notes, badMonth, shortMonth)) {
            assertTrue(Files.exists(p), "retention leaves the foreign file alone: " + p);
        }
        assertFalse(Files.exists(oldRuns), "guard: retention still deletes the expired run month " + oldRuns);
        assertTrue(ApproverFormFixtures.records(ChangeType.RETENTION).size() > retentionRecords, "guard: and records it");
    }

    // ------------------------------------------------------------------ helpers

    private Path store() {
        return j.jenkins.getRootDir().toPath().resolve("batch-control");
    }

    private Path indexFile(YearMonth month) {
        Path index = store().resolve("incidents/index/" + StoreDataFixtures.monthName(month) + ".jsonl");
        assertTrue(Files.isRegularFile(index), "premise (ARCHITECTURE 5): the incident index of " + month + " is " + index);
        return index;
    }

    /** A failed build three months ago (plugin clock) and its incident in that month. */
    private Incident oldIncident(String name) throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject(name));
        job.getBuildersList().add(new FailureBuilder());
        BatchControlFixtures.activateAsAdmin(job);
        BatchClock.setForTest(Clock.fixed(old, ZoneOffset.UTC));
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        j.waitUntilNoActivity();
        List<Incident> incidents = IncidentService.get().list(oldMonth).stream().filter(i -> (name + "#1").equals(i.getRunId()))
                .collect(Collectors.toList());
        BatchClock.reset();
        assertEquals(1, incidents.size(), "premise: the failed build opened an incident in " + oldMonth);
        return incidents.get(0);
    }

    private String openWindow(String userId, String fullName) throws Exception {
        String id = submitGrantOk(j, userId, fullName, List.of("CONFIGURE"), 30, "work on " + fullName, null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        return id;
    }

    private void saveAs(String user, FreeStyleProject job, String description) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, user);
        WebRequest post = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        post.setAdditionalHeader("Content-Type", "application/xml");
        post.setRequestBody(StoreFaultGapTest.withDescription(job.getConfigFile().asString(), description));
        int code = wc.getPage(post).getWebResponse().getStatusCode();
        assertTrue(code < 400, "fixture: " + user + " saves " + job.getFullName() + ", got " + code);
    }

    private static void retention() throws Exception {
        ExtensionList.lookupSingleton(RetentionPeriodicWork.class).doRun();
    }

    private WebResponse get(String path) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "viewer");
        wc.getOptions().setJavaScriptEnabled(false);
        return wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET)).getWebResponse();
    }
}
