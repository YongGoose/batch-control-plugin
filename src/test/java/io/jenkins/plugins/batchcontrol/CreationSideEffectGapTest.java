package io.jenkins.plugins.batchcontrol;

import hudson.model.AbstractItem;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.listeners.ItemListener;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.assertDiffShows;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.describe;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.postConfigXml;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.records;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.withDescription;
import static io.jenkins.plugins.batchcontrol.SnapshotRefreshGapTest.addsLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An item listener that, while one item is created, changes that item and another one: the change of the other
 * item is a configuration change, the change of the new item is part of its creation. Matrix row T-GAP-426 (note
 * 282).
 *
 * <p>Basis: DECISIONS D-76 (2): "Saves made while an item is being created ... are part of its CREATE, not
 * CONFIGURE records"; SPEC 9 ("생성·수정·삭제·이름변경·이동은 경로와 무관하게 누가·언제·무엇을 바꿨는지 자동으로 기록",
 * "CONFIGURE 변경에 unified diff가 저장된다"); D-76 (1) (changes are not attributed to the next person who saves);
 * SPEC 8 (D-31, D-34: a job created while run control is on starts locked, whatever the creation path).
 *
 * <p>The side effect comes from a test item listener (default ordinal, armed only in its row) that, when
 * {@code side-t} is created, sets the description of the existing job {@code side-v} and of {@code side-t}
 * itself. Both switches are on, so the D-31/D-34 lock is applied during the creation as well.
 *
 * <p>Written from docs/SPEC.md items 8 and 9 and docs/DECISIONS.md D-31, D-34 and D-76 only (no src/main
 * knowledge).
 */
@WithJenkins
public class CreationSideEffectGapTest {

    static final String OTHER_TEXT = "set on side-v while side-t was created";
    static final String OWN_TEXT = "set on side-t while it was created";

    private static final String JOB_XML = "<?xml version='1.1' encoding='UTF-8'?><project>"
            + "<description>created over rest</description><builders/><publishers/><buildWrappers/></project>";

    private JenkinsRule j;

    /** When armed, changes {@code side-v} and the new {@code side-t} while {@code side-t} is created (once). */
    @TestExtension("t_gap_426_listenerChangingAnotherItemOnCreationRecordsOnlyThatItemsChange")
    public static final class SideEffects extends ItemListener {
        static volatile boolean armed;
        static volatile boolean fired;
        static volatile Throwable failure;

        @Override
        public void onCreated(Item item) {
            if (armed && "side-t".equals(item.getFullName())) {
                armed = false;
                try {
                    FreeStyleProject other = Jenkins.get().getItemByFullName("side-v", FreeStyleProject.class);
                    other.setDescription(OTHER_TEXT);
                    ((AbstractItem) item).setDescription(OWN_TEXT);
                    fired = true;
                } catch (IOException | RuntimeException e) {
                    failure = e;
                }
            }
        }
    }

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        SideEffects.armed = false;
        SideEffects.fired = false;
        SideEffects.failure = null;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin"));
    }

    @AfterEach
    public void tearDown() {
        SideEffects.armed = false;
    }

    /**
     * T-GAP-426 (D-76 (2); SPEC 9): both switches on; job {@code side-v} exists without a description (premise:
     * no CONFIGURE record). With the listener armed, the administrator creates {@code side-t} through
     * {@code createItem} with a {@code config.xml} body; the listener sets {@code side-v}'s and {@code side-t}'s
     * descriptions (premise). {@code side-v}: exactly one CONFIGURE record, whose diff adds its new description.
     * {@code side-t}: one CREATE record and no CONFIGURE record. Guard: a real change of {@code side-t} then
     * writes exactly one CONFIGURE record whose diff goes from the listener's description to the new one.
     */
    @Test
    public void t_gap_426_listenerChangingAnotherItemOnCreationRecordsOnlyThatItemsChange() throws Exception {
        switchesOn();
        FreeStyleProject other = j.createFreeStyleProject("side-v");
        assertNull(other.getDescription(), "premise: side-v has no description");
        assertEquals(0, records(ChangeType.CONFIGURE, "side-v").size(), "premise (D-76 (2)): creating side-v wrote no CONFIGURE record: "
                + describe(records(ChangeType.CONFIGURE, "side-v")));

        SideEffects.armed = true;
        int code = createFromXml("side-t", JOB_XML);
        assertTrue(code < 400, "fixture: createItem with XML creates side-t, got " + code);
        j.waitUntilNoActivity();
        assertNull(SideEffects.failure, "fixture: the listener's changes succeeded: " + SideEffects.failure);
        assertTrue(SideEffects.fired, "premise: the listener ran on side-t's creation");
        assertEquals(OTHER_TEXT, j.jenkins.getItemByFullName("side-v", FreeStyleProject.class).getDescription(), "premise: side-v changed");
        FreeStyleProject created = j.jenkins.getItemByFullName("side-t", FreeStyleProject.class);
        assertNotNull(created, "premise: side-t exists");
        assertEquals(OWN_TEXT, created.getDescription(), "premise: side-t carries the listener's description");

        List<ChangeRecord> otherConfigure = records(ChangeType.CONFIGURE, "side-v");
        assertEquals(1, otherConfigure.size(), "SPEC 9: the change of the existing side-v is one CONFIGURE record: " + describe(otherConfigure));
        System.out.println("T-GAP-426 observation: side-v's CONFIGURE record is by " + otherConfigure.get(0).getUser());
        assertTrue(addsLine(otherConfigure.get(0), "<description>" + OTHER_TEXT + "</description>"),
                "SPEC 9: side-v's CONFIGURE diff adds its new description: " + describe(otherConfigure));

        List<ChangeRecord> create = records(ChangeType.CREATE, "side-t");
        List<ChangeRecord> configure = records(ChangeType.CONFIGURE, "side-t");
        assertEquals(1, create.size(), "SPEC 9: creating side-t writes exactly one CREATE record: " + describe(create));
        assertEquals(0, configure.size(), "D-76 (2): the listener's save of side-t during its creation is part of its CREATE, not a"
                + " CONFIGURE record: " + describe(configure));

        assertEquals(200, postConfigXml(j, "admin", created, withDescription(created.getConfigFile().asString(), "guard edit")),
                "fixture: the guard edit of side-t is saved");
        List<ChangeRecord> afterGuard = records(ChangeType.CONFIGURE, "side-t");
        assertEquals(1, afterGuard.size(), "guard (SPEC 9): one real change of side-t writes exactly one CONFIGURE record: " + describe(afterGuard));
        assertDiffShows(afterGuard.get(0), OWN_TEXT, "guard edit",
                "D-76 (1) and (2): the configuration side-t had when its creation ended is the baseline of its first change");
        assertEquals(1, records(ChangeType.CONFIGURE, "side-v").size(), "SPEC 9: side-t's later change records nothing for side-v");
    }

    // ------------------------------------------------------------------ helpers

    private void switchesOn() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.save();
        assertTrue(BatchControlGlobalConfiguration.get().isRunControlEnabled() && BatchControlGlobalConfiguration.get().isChangeControlEnabled(),
                "fixture: both switches are on");
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
