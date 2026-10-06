package io.jenkins.plugins.batchcontrol;

import hudson.model.AbstractItem;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jenkins.branch.OrganizationFolder;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.assertDiffShows;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.describe;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.postConfigXml;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.records;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.withDescription;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The configuration snapshots are refreshed when recording turns on: an unchanged save then records nothing, run
 * control alone refreshes too, and a snapshot left behind by a deleted item is never the baseline of a new item at
 * its name. Matrix rows T-GAP-422, T-GAP-423 and T-GAP-429 (note 282).
 *
 * <p>Basis: DECISIONS D-76 (1): "When recording turns on (neither switch was on before), the configuration
 * snapshot of every item is refreshed to its current configuration, so changes made while recording was off are
 * not attributed to the first person who saves afterwards and an item re-created at a deleted item's name does not
 * inherit the old snapshot"; D-76 (2): "Saves made while an item is being created (including an organization
 * folder ... created from config.xml) are part of its CREATE, not CONFIGURE records"; SPEC 9 ("a save that changes
 * no user-editable configuration writes no CONFIGURE record", "CONFIGURE 변경에 unified diff가 저장된다", "변경 통제
 * 스위치가 꺼져 있어도 실행 통제가 켜져 있으면 변경 기록은 남는다(기록은 어느 스위치든 켜지면 활성)"); LIMITATIONS 17
 * and 53 ({@code batch-control/snapshots/<job>.xml} is the baseline of a CONFIGURE diff); ARCHITECTURE 5 (a
 * top-level name is not encoded in the snapshot file name).
 *
 * <p>Fixture: the administrator acts over HTTP ({@code config.xml} POST and {@code createItem} with a crumb);
 * recording is switched through the global configuration (setter then save, SPEC 1). Helpers are those of
 * {@link SnapshotBaselineGapTest}. Every "no record" row ends with a guard: a real change afterwards writes a
 * CONFIGURE record with the expected diff, so "no record" cannot come from recording that does not work for the
 * item.
 *
 * <p>Written from docs/SPEC.md items 1 and 9, docs/DECISIONS.md D-76, docs/LIMITATIONS.md items 17 and 53 and
 * docs/ARCHITECTURE.md section 5 only (no src/main knowledge).
 */
@WithJenkins
public class SnapshotRefreshGapTest {

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin"));
        cfg = BatchControlGlobalConfiguration.get();
        assertFalse(cfg.isRunControlEnabled(), "premise (SPEC 1): run control is off after installation");
        assertFalse(cfg.isChangeControlEnabled(), "premise (SPEC 1): change control is off after installation");
    }

    /**
     * T-GAP-422 (D-76 (1); SPEC 9 no-op line): change control on; job {@code same-j} created and given the
     * description {@code before off}. Change control off; the description is set to {@code during off} (no
     * record: premise). Change control on again; the administrator POSTs {@code same-j}'s identical
     * {@code config.xml}: no new CONFIGURE record, because the refreshed snapshot already holds {@code during off}.
     * Guard: a real change to {@code edited} then writes exactly one CONFIGURE record whose diff goes from
     * {@code during off} to {@code edited} and does not mention {@code before off}.
     */
    @Test
    public void t_gap_422_unchangedSaveAfterSwitchOnWritesNoConfigureRecord() throws Exception {
        recording(true, false);
        FreeStyleProject job = j.createFreeStyleProject("same-j");
        job.setDescription("before off");
        int before = records(ChangeType.CONFIGURE, "same-j").size();

        recording(false, false);
        job("same-j").setDescription("during off");
        assertEquals(before, records(ChangeType.CONFIGURE, "same-j").size(),
                "premise (SPEC 9): nothing is recorded while both switches are off");

        recording(true, false);
        String unchanged = job("same-j").getConfigFile().asString();
        assertTrue(unchanged.contains("<description>during off</description>"), "premise: the config.xml holds the off-period value");
        assertEquals(200, postConfigXml(j, "admin", job("same-j"), unchanged), "fixture: the identical config.xml POST succeeds");
        List<ChangeRecord> afterNoop = records(ChangeType.CONFIGURE, "same-j");
        assertEquals(before, afterNoop.size(), "D-76 (1), SPEC 9: after switch-on the snapshot holds the current configuration, so"
                + " saving it unchanged writes no CONFIGURE record: " + describe(afterNoop.subList(before, afterNoop.size())));

        assertEquals(200, postConfigXml(j, "admin", job("same-j"), withDescription(job("same-j").getConfigFile().asString(), "edited")),
                "fixture: the guard edit is saved");
        List<ChangeRecord> afterEdit = records(ChangeType.CONFIGURE, "same-j");
        assertEquals(before + 1, afterEdit.size(), "guard (SPEC 9): one real change, one CONFIGURE record: "
                + describe(afterEdit.subList(before, afterEdit.size())));
        ChangeRecord record = afterEdit.get(afterEdit.size() - 1);
        assertDiffShows(record, "during off", "edited", "guard (D-76 (1)): the refreshed snapshot is the baseline");
        assertFalse(record.getDiff().contains("<description>before off</description>"),
                "D-76 (1): the configuration from before recording was off is not the baseline: " + record.getDiff());
    }

    /**
     * T-GAP-423 (D-76 (1) "neither switch was on before"; SPEC 9 "기록은 어느 스위치든 켜지면 활성"): as T-GAP-422 but
     * with run control as the only switch throughout. Run control on; job {@code run-only-j} created and given
     * the description {@code before off}; run control off; the description set to {@code during off} (no record:
     * premise); run control on again; the administrator changes the description to {@code edited} over REST:
     * exactly one new CONFIGURE record, whose diff removes {@code during off} (the off-period value) and adds
     * {@code edited}, and does not mention {@code before off} (the pre-off value).
     */
    @Test
    public void t_gap_423_runControlAloneAlsoRefreshesTheSnapshots() throws Exception {
        recording(false, true);
        FreeStyleProject job = j.createFreeStyleProject("run-only-j");
        job.setDescription("before off");
        int before = records(ChangeType.CONFIGURE, "run-only-j").size();

        recording(false, false);
        job("run-only-j").setDescription("during off");
        assertEquals(before, records(ChangeType.CONFIGURE, "run-only-j").size(),
                "premise (SPEC 9): nothing is recorded while both switches are off");

        recording(false, true);
        assertEquals(200, postConfigXml(j, "admin", job("run-only-j"),
                withDescription(job("run-only-j").getConfigFile().asString(), "edited")), "fixture: the administrator's config.xml POST succeeds");
        assertEquals("edited", job("run-only-j").getDescription(), "premise: the change is saved");
        List<ChangeRecord> after = records(ChangeType.CONFIGURE, "run-only-j");
        assertEquals(before + 1, after.size(), "SPEC 9: with run control on, one change writes one CONFIGURE record: "
                + describe(after.subList(before, after.size())));
        ChangeRecord record = after.get(after.size() - 1);
        assertEquals("admin", record.getUser(), "SPEC 9: the record names who changed it");
        assertDiffShows(record, "during off", "edited", "D-76 (1): turning run control on alone refreshes the snapshot");
        assertFalse(record.getDiff().contains("<description>before off</description>"),
                "D-76 (1): the change made while recording was off is not attributed to the administrator: " + record.getDiff());
    }

    /**
     * T-GAP-429 (D-76 (1) "an item re-created at a deleted item's name does not inherit the old snapshot"; D-76
     * (2) "an organization folder ... created from config.xml"): the {@code config.xml} of an organization folder
     * is taken from a template made while both switches were off. Change control on; job {@code orph} created and
     * given the description {@code deleted job text} (premise: {@code snapshots/orph.xml} is a regular file).
     * Change control off; {@code orph} is deleted (premise, by assumption: its snapshot stays). Change control on;
     * the administrator creates an organization folder at {@code orph} through {@code createItem} with the
     * template XML: one new CREATE record and no new CONFIGURE record for {@code orph}. Its next change (a
     * description over REST) writes a CONFIGURE record whose diff adds the description, and no new CONFIGURE
     * record carries a trace of the deleted job (its description, its {@code <project>} root, its builders).
     */
    @Test
    public void t_gap_429_leftoverSnapshotAtAReusedNameIsNotTheBaselineOfAnOrganizationFolder() throws Exception {
        OrganizationFolder template = j.jenkins.createProject(OrganizationFolder.class, "tmpl-orph-org");
        template.save();
        j.waitUntilNoActivity();
        String orgXml = template.getConfigFile().asString();

        recording(true, false);
        FreeStyleProject orph = j.createFreeStyleProject("orph");
        orph.setDescription("deleted job text");
        Path snapshot = store().resolve("snapshots/orph.xml");
        assertTrue(Files.isRegularFile(snapshot), "premise (ARCHITECTURE 5, LIMITATIONS 17): the job's snapshot is stored at " + snapshot);
        assertTrue(Files.readString(snapshot, StandardCharsets.UTF_8).contains("deleted job text"), "premise: the snapshot holds the change");

        recording(false, false);
        orph.delete();
        assertNull(j.jenkins.getItemByFullName("orph"), "premise: orph is deleted");
        boolean left = Files.isRegularFile(snapshot);
        System.out.println("T-GAP-429 observation: the deleted job's snapshot " + (left ? "stays" : "is gone") + " while recording is off");
        assumeTrue(left, "premise of the row: the deleted job's snapshot stays while recording is off");

        recording(true, false);
        int createBefore = records(ChangeType.CREATE, "orph").size();
        int configureBefore = records(ChangeType.CONFIGURE, "orph").size();
        System.out.println("T-GAP-429 observation: after switch-on the leftover snapshot "
                + (Files.exists(snapshot) ? "is still there" : "is gone"));

        int code = createFromXml("orph", orgXml);
        assertTrue(code < 400, "fixture: createItem with the organization folder XML creates it, got " + code);
        j.waitUntilNoActivity();
        assertNotNull(j.jenkins.getItemByFullName("orph", OrganizationFolder.class), "premise: orph is now an organization folder");
        List<ChangeRecord> create = records(ChangeType.CREATE, "orph");
        List<ChangeRecord> configure = records(ChangeType.CONFIGURE, "orph");
        assertEquals(createBefore + 1, create.size(), "SPEC 9: creating the organization folder writes one CREATE record: "
                + describe(create.subList(createBefore, create.size())));
        assertEquals(configureBefore, configure.size(), "D-76 (1) and (2): creating an organization folder at the deleted job's name"
                + " writes no CONFIGURE record: " + describe(configure.subList(configureBefore, configure.size())));

        AbstractItem org = (AbstractItem) j.jenkins.getItemByFullName("orph");
        String xml = org.getConfigFile().asString();
        String edited = withDescription(xml, "organization folder edited");
        assertNotEquals(xml, edited, "fixture: the edit changes the organization folder's XML");
        assertEquals(200, postConfigXml(j, "admin", org, edited), "fixture: the organization folder's edit is saved");
        List<ChangeRecord> fresh = records(ChangeType.CONFIGURE, "orph");
        fresh = fresh.subList(configureBefore, fresh.size());
        System.out.println("T-GAP-429 observation: CONFIGURE " + fresh.size() + " after one real change " + describe(fresh));
        assertTrue(fresh.stream().anyMatch(r -> addsLine(r, "<description>organization folder edited</description>")),
                "SPEC 9: the organization folder's change writes a CONFIGURE record whose diff adds the description: " + describe(fresh));
        for (ChangeRecord r : fresh) {
            String diff = String.valueOf(r.getDiff());
            assertFalse(diff.contains("deleted job text") || diff.contains("<project>") || diff.contains("</project>")
                            || diff.contains("<builders"),
                    "D-76 (1): the deleted job's snapshot leaves no trace in the organization folder's diff: " + diff);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void recording(boolean changeControl, boolean runControl) throws Exception {
        cfg.setChangeControlEnabled(changeControl);
        cfg.setRunControlEnabled(runControl);
        cfg.save();
        assertEquals(changeControl, BatchControlGlobalConfiguration.get().isChangeControlEnabled(), "fixture: change control switched");
        assertEquals(runControl, BatchControlGlobalConfiguration.get().isRunControlEnabled(), "fixture: run control switched");
    }

    private FreeStyleProject job(String name) {
        FreeStyleProject job = j.jenkins.getItemByFullName(name, FreeStyleProject.class);
        assertNotNull(job, "fixture: " + name + " exists");
        return job;
    }

    private Path store() {
        return j.jenkins.getRootDir().toPath().resolve("batch-control");
    }

    /** The record's diff adds a line containing {@code text}. */
    static boolean addsLine(ChangeRecord record, String text) {
        return record.getDiff() != null && record.getDiff().lines()
                .anyMatch(l -> l.startsWith("+") && !l.startsWith("+++") && l.contains(text));
    }

    private int createFromXml(String name, String xml) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "admin");
        URL url = new URL(wc.createCrumbedUrl("createItem").toExternalForm() + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        WebResponse response = wc.getPage(request).getWebResponse();
        if (response.getStatusCode() >= 400) {
            System.out.println("createItem " + name + " answered " + response.getStatusCode() + ": " + excerpt(response.getContentAsString()));
        }
        return response.getStatusCode();
    }
}
