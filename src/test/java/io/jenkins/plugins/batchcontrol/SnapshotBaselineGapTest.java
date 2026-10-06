package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The baseline a CONFIGURE diff is computed against: the configuration snapshot of an item. Matrix rows
 * T-GAP-404 .. T-GAP-407 (note 281).
 *
 * <p>Basis: SPEC 9 ("생성·수정·삭제·이름변경·이동은 경로와 무관하게 누가·언제·무엇을 바꿨는지 자동으로 기록", "UI, REST
 * ({@code config.xml} POST) ... 경로의 변경이 모두 ChangeRecord로 남는다", "CONFIGURE 변경에 unified diff가 저장된다",
 * "기록은 어느 스위치든 켜지면 활성", "a save that changes no user-editable configuration writes no CONFIGURE
 * record"); DECISIONS D-76 (1): "When recording turns on (neither switch was on before), the configuration
 * snapshot of every item is refreshed to its current configuration, so changes made while recording was off
 * are not attributed to the first person who saves afterwards and an item re-created at a deleted item's name
 * does not inherit the old snapshot; an item saved with no snapshot at all is recorded as CONFIGURE without a
 * diff (SPEC 9)"; LIMITATIONS 17 and 53 (the snapshot {@code batch-control/snapshots/<job>.xml}; a CONFIGURE
 * record whose previous configuration is not available has no diff and a note instead, and the next change
 * has a diff again); ARCHITECTURE 5 ({@code snapshots/<jobFullName 인코딩>.xml}; a top-level name is not
 * encoded). The wording of the note for a missing snapshot is the one relayed by the coordinator: "No diff: no
 * earlier configuration of this item was recorded."
 *
 * <p>Fixture: the administrator acts over HTTP ({@code config.xml} POST with a crumb); recording is switched
 * through the global configuration (setter then save, SPEC 1 "a switch change takes effect only once the new
 * configuration is saved"). Fault injection only through the store on disk (a snapshot file deleted).
 *
 * <p>Written from docs/SPEC.md items 1 and 9, docs/DECISIONS.md D-76, docs/LIMITATIONS.md items 17 and 53 and
 * docs/ARCHITECTURE.md section 5 only (no src/main knowledge).
 */
@WithJenkins
public class SnapshotBaselineGapTest {

    static final String NO_SNAPSHOT_NOTE = "No diff: no earlier configuration of this item was recorded.";

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
     * T-GAP-404 (SPEC 9; D-76 (1)): with both switches off, job {@code pre-j} exists with the description
     * {@code before switch-on} (no record: premise). Change control is turned on. The administrator changes the
     * description to {@code after switch-on} over REST: exactly one CONFIGURE record for {@code pre-j}, by the
     * administrator, whose unified diff removes the old and adds the new description. A following save of the
     * identical {@code config.xml} (and a programmatic save) adds no record.
     */
    @Test
    public void t_gap_404_firstChangeOfAPreExistingJobAfterSwitchOnHasADiff() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("pre-j");
        job.setDescription("before switch-on");
        assertEquals(0, records(ChangeType.CONFIGURE, "pre-j").size() + records(ChangeType.CREATE, "pre-j").size(),
                "premise (SPEC 9): nothing is recorded while both switches are off");

        recording(true, false);

        assertEquals(200, postConfigXml("admin", job, withDescription(job.getConfigFile().asString(), "after switch-on")),
                "fixture: the administrator's config.xml POST succeeds");
        assertEquals("after switch-on", job("pre-j").getDescription(), "premise: the change is saved");
        List<ChangeRecord> configure = records(ChangeType.CONFIGURE, "pre-j");
        assertEquals(1, configure.size(), "SPEC 9: the first change after recording turned on writes exactly one CONFIGURE record: "
                + describe(configure));
        ChangeRecord record = configure.get(0);
        assertEquals("admin", record.getUser(), "SPEC 9: the record names who changed it");
        assertDiffShows(record, "before switch-on", "after switch-on",
                "D-76 (1): the pre-existing job's current configuration is the baseline of its first change");

        assertEquals(200, postConfigXml("admin", job("pre-j"), job("pre-j").getConfigFile().asString()),
                "fixture: the identical config.xml POST succeeds");
        job("pre-j").save();
        assertEquals(1, records(ChangeType.CONFIGURE, "pre-j").size(), "SPEC 9: a save that changes nothing adds no CONFIGURE record: "
                + describe(records(ChangeType.CONFIGURE, "pre-j")));
    }

    /**
     * T-GAP-405 (D-76 (1) "changes made while recording was off are not attributed to the first person who saves
     * afterwards"): change control on; job {@code off-j} created with the description {@code v1}. Change control
     * off; the description is set to {@code v-off} (no record: premise). Change control on again; the
     * administrator changes the description to {@code v3} over REST: exactly one new CONFIGURE record, whose
     * diff goes from {@code v-off} to {@code v3} and does not mention {@code v1}.
     */
    @Test
    public void t_gap_405_aChangeMadeWhileRecordingWasOffIsNotInTheNextDiff() throws Exception {
        recording(true, false);
        FreeStyleProject job = j.createFreeStyleProject("off-j");
        job.setDescription("v1");
        int before = records(ChangeType.CONFIGURE, "off-j").size();

        recording(false, false);
        job("off-j").setDescription("v-off");
        assertEquals(before, records(ChangeType.CONFIGURE, "off-j").size(), "premise (SPEC 9): nothing is recorded while both switches are off");

        recording(true, false);
        assertEquals(200, postConfigXml("admin", job("off-j"), withDescription(job("off-j").getConfigFile().asString(), "v3")),
                "fixture: the administrator's config.xml POST succeeds");
        List<ChangeRecord> after = records(ChangeType.CONFIGURE, "off-j");
        assertEquals(before + 1, after.size(), "SPEC 9: one change, one CONFIGURE record: " + describe(after));
        ChangeRecord record = after.get(after.size() - 1);
        assertDiffShows(record, "v-off", "v3", "D-76 (1): the baseline is the configuration at switch-on");
        assertFalse(record.getDiff().contains("<description>v1</description>"),
                "D-76 (1): the change made while recording was off is not attributed to the administrator: " + record.getDiff());
    }

    /**
     * T-GAP-406 (D-76 (1) "an item re-created at a deleted item's name does not inherit the old snapshot"):
     * change control on; job {@code again-j} created with the description {@code old item}. Change control off;
     * {@code again-j} is deleted and a new {@code again-j} is created with the description {@code new item}.
     * Change control on; the administrator changes the new job's description to {@code new item edited} over
     * REST: exactly one new CONFIGURE record for {@code again-j}, whose diff goes from {@code new item} to
     * {@code new item edited} and does not mention {@code old item}.
     */
    @Test
    public void t_gap_406_anItemReCreatedWhileRecordingWasOffDoesNotInheritTheOldSnapshot() throws Exception {
        recording(true, false);
        FreeStyleProject old = j.createFreeStyleProject("again-j");
        old.setDescription("old item");

        recording(false, false);
        old.delete();
        FreeStyleProject renewed = j.createFreeStyleProject("again-j");
        renewed.setDescription("new item");
        int before = records(ChangeType.CONFIGURE, "again-j").size();

        recording(true, false);
        assertEquals(200, postConfigXml("admin", job("again-j"),
                withDescription(job("again-j").getConfigFile().asString(), "new item edited")), "fixture: the config.xml POST succeeds");
        List<ChangeRecord> after = records(ChangeType.CONFIGURE, "again-j");
        assertEquals(before + 1, after.size(), "SPEC 9: one change, one CONFIGURE record: " + describe(after));
        ChangeRecord record = after.get(after.size() - 1);
        assertDiffShows(record, "new item", "new item edited", "D-76 (1): the re-created job's own configuration is its baseline");
        assertFalse(record.getDiff().contains("old item"),
                "D-76 (1): the deleted job's snapshot is not the re-created job's baseline: " + record.getDiff());
    }

    /**
     * T-GAP-407 (D-76 (1) "an item saved with no snapshot at all is recorded as CONFIGURE without a diff";
     * LIMITATIONS 53): change control on; job {@code nosnap-j} created with the description {@code first}
     * (premise: {@code snapshots/nosnap-j.xml} is a regular file). The snapshot file is deleted. The
     * administrator changes the description to {@code second} over REST: exactly one new CONFIGURE record, with
     * no diff and the detail "No diff: no earlier configuration of this item was recorded.". A further change to
     * {@code third}: one more CONFIGURE record whose diff goes from {@code second} to {@code third}.
     */
    @Test
    public void t_gap_407_aChangeWithNoSnapshotIsRecordedWithoutADiffAndTheNextOneHasADiff() throws Exception {
        recording(true, false);
        FreeStyleProject job = j.createFreeStyleProject("nosnap-j");
        job.setDescription("first");
        Path snapshot = store().resolve("snapshots/nosnap-j.xml");
        assertTrue(Files.isRegularFile(snapshot), "premise (ARCHITECTURE 5, LIMITATIONS 17): the job's snapshot is stored at " + snapshot);
        int before = records(ChangeType.CONFIGURE, "nosnap-j").size();
        Files.delete(snapshot);

        assertEquals(200, postConfigXml("admin", job("nosnap-j"), withDescription(job("nosnap-j").getConfigFile().asString(), "second")),
                "SPEC 9: the save succeeds without a snapshot");
        assertEquals("second", job("nosnap-j").getDescription(), "premise: the change is saved");
        List<ChangeRecord> afterFirst = records(ChangeType.CONFIGURE, "nosnap-j");
        assertEquals(before + 1, afterFirst.size(), "SPEC 9: the change is recorded although no snapshot exists: " + describe(afterFirst));
        ChangeRecord undiffed = afterFirst.get(afterFirst.size() - 1);
        assertEquals("admin", undiffed.getUser(), "SPEC 9: the record names who changed it");
        assertTrue(undiffed.getDiff() == null || undiffed.getDiff().isBlank(),
                "D-76 (1): a change with no earlier configuration has no diff, got: " + undiffed.getDiff());
        assertTrue(String.valueOf(undiffed.getDetail()).contains(NO_SNAPSHOT_NOTE),
                "the record says why it has no diff: " + undiffed.getDetail());

        assertEquals(200, postConfigXml("admin", job("nosnap-j"), withDescription(job("nosnap-j").getConfigFile().asString(), "third")),
                "fixture: the second config.xml POST succeeds");
        List<ChangeRecord> afterSecond = records(ChangeType.CONFIGURE, "nosnap-j");
        assertEquals(before + 2, afterSecond.size(), "SPEC 9: the next change writes one more CONFIGURE record: " + describe(afterSecond));
        assertDiffShows(afterSecond.get(afterSecond.size() - 1), "second", "third",
                "LIMITATIONS 53: once the save stored a snapshot, the next change has a diff again");
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

    int postConfigXml(String userId, Item target, String xml) throws Exception {
        return postConfigXml(j, userId, target, xml);
    }

    static int postConfigXml(JenkinsRule j, String userId, Item target, String xml) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, userId);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(target.getUrl() + "config.xml"), HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        WebResponse response = wc.getPage(request).getWebResponse();
        if (response.getStatusCode() != 200) {
            System.out.println("config.xml POST to " + target.getFullName() + " answered " + response.getStatusCode() + ": "
                    + excerpt(response.getContentAsString()));
        }
        return response.getStatusCode();
    }

    /** Sets the description in an item's config.xml text, whatever form the element has or wherever the root element is. */
    static String withDescription(String xml, String text) {
        if (xml.contains("<description/>")) {
            return xml.replaceFirst("<description/>", "<description>" + text + "</description>");
        }
        if (xml.matches("(?s).*<description>.*?</description>.*")) {
            return xml.replaceFirst("(?s)<description>.*?</description>", "<description>" + text + "</description>");
        }
        // insert right after the root element's start tag (the first start tag after the XML declaration)
        return xml.replaceFirst("(?s)^(\\s*(<\\?xml[^>]*\\?>)?\\s*<[A-Za-z_][^>]*[^/]>)", "$1<description>" + text + "</description>");
    }

    /** CONFIGURE/CREATE/... records of {@code target} in the current month of the plugin clock. */
    static List<ChangeRecord> records(ChangeType type, String target) {
        return ApproverFormFixtures.records(type).stream().filter(r -> target.equals(r.getTarget())).collect(Collectors.toList());
    }

    static String describe(List<ChangeRecord> records) {
        return records.stream().map(r -> "[" + r.getType() + " " + r.getTarget() + " by " + r.getUser() + " detail=" + r.getDetail()
                + " diff=" + (r.getDiff() == null ? "<none>" : excerpt(r.getDiff())) + "]").collect(Collectors.joining(", "));
    }

    /** The record's unified diff removes a line with {@code removed} and adds a line with {@code added}. */
    static void assertDiffShows(ChangeRecord record, String removed, String added, String why) {
        String diff = record.getDiff();
        assertNotNull(diff, why + ": the CONFIGURE record must carry a diff (detail: " + record.getDetail() + ")");
        assertFalse(diff.isBlank(), why + ": the diff must not be empty (detail: " + record.getDetail() + ")");
        List<String> lines = Arrays.asList(diff.split("\\R"));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("-") && !l.startsWith("---")
                        && l.contains("<description>" + removed + "</description>")),
                why + ": the diff must remove the description '" + removed + "': " + diff);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("+") && !l.startsWith("+++")
                        && l.contains("<description>" + added + "</description>")),
                why + ": the diff must add the description '" + added + "': " + diff);
    }
}
