package io.jenkins.plugins.batchcontrol;

import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsSessionRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * SPEC section 5 (global configuration keys and defaults). Matrix rows T-CFG-01 .. T-CFG-03.
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class GlobalConfigDefaultsTest {

    @Rule
    public JenkinsSessionRule session = new JenkinsSessionRule();

    /** T-CFG-01: fresh install (nothing ever saved) -> every default matches the SPEC section 5 table. */
    @Test
    public void t_cfg_01_freshInstallHasSpecSection5Defaults() throws Throwable {
        session.then(r -> {
            BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
            assertFalse("runControlEnabled default", cfg.isRunControlEnabled());
            assertFalse("changeControlEnabled default", cfg.isChangeControlEnabled());
            assertEquals("approvers default", Collections.emptyList(), cfg.getApprovers());
            assertTrue("allowAdminSelfApproval default", cfg.isAllowAdminSelfApproval());
            assertEquals("pendingTimeoutHours default", 72, cfg.getPendingTimeoutHours());
            assertEquals("approvedRunTimeoutMinutes default", 60, cfg.getApprovedRunTimeoutMinutes());
            assertEquals("grantDurationOptions default", Arrays.asList(15, 30, 60), cfg.getGrantDurationOptions());
            assertEquals("maxGrantMinutes default", 240, cfg.getMaxGrantMinutes());
            assertEquals("incidentResults default", Arrays.asList("FAILURE", "UNSTABLE"), cfg.getIncidentResults());
            assertEquals("retentionMonths default", 24, cfg.getRetentionMonths());
        });
    }

    /** T-CFG-02: non-default values saved before a restart are still there after the restart. */
    @Test
    public void t_cfg_02_savedValuesSurviveRestart() throws Throwable {
        session.then(r -> {
            BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
            cfg.setRunControlEnabled(true);
            cfg.setChangeControlEnabled(true);
            cfg.setApprovers(Arrays.asList("a1", "a2"));
            cfg.setAllowAdminSelfApproval(false);
            cfg.setPendingTimeoutHours(48);
            cfg.setApprovedRunTimeoutMinutes(30);
            cfg.setGrantDurationOptions(Arrays.asList(10, 20));
            cfg.setMaxGrantMinutes(120);
            cfg.setIncidentResults(Arrays.asList("FAILURE"));
            cfg.setRetentionMonths(12);
            cfg.save();
        });
        session.then(r -> {
            BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
            assertTrue(cfg.isRunControlEnabled());
            assertTrue(cfg.isChangeControlEnabled());
            assertEquals(Arrays.asList("a1", "a2"), cfg.getApprovers());
            assertFalse(cfg.isAllowAdminSelfApproval());
            assertEquals(48, cfg.getPendingTimeoutHours());
            assertEquals(30, cfg.getApprovedRunTimeoutMinutes());
            assertEquals(Arrays.asList(10, 20), cfg.getGrantDurationOptions());
            assertEquals(120, cfg.getMaxGrantMinutes());
            assertEquals(Arrays.asList("FAILURE"), cfg.getIncidentResults());
            assertEquals(12, cfg.getRetentionMonths());
        });
    }

    /** T-CFG-03: invalid pendingTimeoutHours (0, negative) is rejected or ignored; never stored. */
    @Test
    public void t_cfg_03_invalidPendingTimeoutIsNotStored() throws Throwable {
        session.then(r -> {
            BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
            assertEquals(72, cfg.getPendingTimeoutHours());

            for (int invalid : new int[] {0, -5}) {
                try {
                    cfg.setPendingTimeoutHours(invalid);
                } catch (IllegalArgumentException expected) {
                    // rejecting with an exception is one acceptable behavior
                }
            }
            cfg.save();

            assertEquals("an invalid value must never be stored; the previous value is kept",
                    72, BatchControlGlobalConfiguration.get().getPendingTimeoutHours());
        });
    }
}
