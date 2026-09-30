package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.decideActivation;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.recordsFor;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivationOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * e2e-04 FD-07, SPEC 6 (#21 coalescing: "one job and cause kind produce at most one record per
 * hour") read with SPEC 6a (activation and hold) and the usability line (recorded history names
 * what happened): a refusal after a HOLD must have a record of its own, not be merged into a
 * record written before the job was activated. Matrix row T-06-85 (note 169).
 *
 * <p>Written from docs/SPEC.md, docs/reports/e2e-04.md and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class HoldRefusalRecordTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-06-85 (FD-07): a timer run of a job that was never activated is refused (record 1); the job
     * is activated and a timer run builds; the job is put on hold and a timer run is refused again,
     * all within the hour. The second refusal has its own TRIGGER_BLOCKED record (a second record,
     * not the one written before the activation).
     */
    @Test
    public void t_06_85_refusalAfterHoldHasItsOwnRecord() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("hold-x");
        BatchControlJobProperty cleared = new BatchControlJobProperty(false);
        cleared.setBlockTimer(false);
        cleared.setBlockUpstream(false);
        setBatchControl(job, cleared);

        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "fixture: a never-activated job refuses a timer run");
        j.waitUntilNoActivity();
        assertEquals(1, blocked(job).size(), "fixture: the first refusal is recorded");
        ChangeRecord first = blocked(job).get(0);

        String activate = submitActivationOk(j, "u1", job, "ACTIVATE", "go live", "a1");
        assertSuccess(decideActivation(j, "a1", activate, "approve", "ok"), "fixture: activation");
        j.assertBuildStatusSuccess(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));

        String hold = submitActivationOk(j, "u1", job, "HOLD", "pause", "a1");
        assertSuccess(decideActivation(j, "a1", hold, "approve", "ok"), "fixture: hold");
        List<ChangeRecord> held = recordsFor(ChangeType.HELD, "hold-x");
        assertEquals(1, held.size(), "fixture: one HELD record");

        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "a held job refuses a timer run");
        j.waitUntilNoActivity();
        List<ChangeRecord> records = blocked(job);
        assertEquals(2, records.size(), "the refusal after the hold must have its own TRIGGER_BLOCKED record, not be merged"
                + " into the one written before the activation: " + describe(records));
        assertTrue(records.stream().anyMatch(r -> !r.getId().equals(first.getId())), "a record other than the one written"
                + " before the activation must exist: " + describe(records));
        assertNotNull(first.getId());
    }

    private static List<ChangeRecord> blocked(FreeStyleProject job) {
        return recordsFor(ChangeType.TRIGGER_BLOCKED, job.getFullName());
    }

    private static String describe(List<ChangeRecord> records) {
        return records.stream().map(r -> r.getAt() + " " + r.getDetail()).collect(Collectors.joining("; ", "[", "]"));
    }
}
