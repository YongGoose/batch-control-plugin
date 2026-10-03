package io.jenkins.plugins.batchcontrol;

import hudson.PluginManager;
import hudson.PluginWrapper;
import java.util.Locale;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #78, D-35f, LIMITATIONS 9 (matrix note 224, T-02-122): a real Jenkins with role-strategy 898
 * (older than the declared optional minimum 927, D-35g) does not load Batch Control and says why; Jenkins
 * itself still boots and role-strategy 898 is active.
 *
 * <p>The 898 plugin is read from the test class path at {@value #OLD_HPI}. The build must put it
 * there (a test-scoped copy of {@code org.jenkins-ci.plugins:role-strategy:898.vc050ed2424ca_:hpi});
 * until it does, the row is <b>skipped</b> by an assumption, not passed. This class references no
 * Batch Control or role-strategy type: its code runs in a Jenkins where Batch Control is not loaded.
 *
 * <p>Written from issue #78, D-35f, LIMITATIONS 9 and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class RoleStrategyTooOldRealJenkinsTest {

    static final String OLD_HPI = "old-plugins/role-strategy.hpi";

    @RegisterExtension
    final RealJenkinsExtension rr = oldRoleStrategy();

    private static RealJenkinsExtension oldRoleStrategy() {
        RealJenkinsExtension ext = new RealJenkinsExtension().omitPlugins("role-strategy");
        if (RoleStrategyTooOldRealJenkinsTest.class.getClassLoader().getResource(OLD_HPI) != null) {
            ext.addPlugins(OLD_HPI);
        }
        return ext;
    }

    /** T-02-122: next to role-strategy 898 Batch Control is not active and names the version problem. */
    @Test
    public void t_02_122_batchControlIsNotLoadedNextToRoleStrategy898() throws Throwable {
        assumeTrue(getClass().getClassLoader().getResource(OLD_HPI) != null,
                "role-strategy 898 is not on the test class path at " + OLD_HPI + " (needs the pom copy step)");
        rr.then(RoleStrategyTooOldRealJenkinsTest::refused);
    }

    private static void refused(JenkinsRule r) throws Throwable {
        PluginWrapper role = Jenkins.get().getPluginManager().getPlugin("role-strategy");
        assertNotNull(role, "premise: role-strategy is installed");
        assertTrue(role.getVersion().startsWith("898."), "premise: the installed role-strategy is 898, was " + role.getVersion());
        assertTrue(role.isActive(), "premise: role-strategy 898 itself loads");

        PluginManager pm = Jenkins.get().getPluginManager();
        PluginWrapper self = pm.getPlugin("batch-control");
        PluginManager.FailedPlugin failed = pm.getFailedPlugins().stream()
                .filter(f -> "batch-control".equals(f.name)).findFirst().orElse(null);
        String failedNames = pm.getFailedPlugins().stream().map(f -> f.name).toList().toString();
        assertTrue(self != null || failed != null,
                "premise: batch-control is installed, listed as a plugin or as a failed plugin; failed: " + failedNames);
        String reason;
        if (self != null) {
            assertFalse(self.isActive(), "batch-control must not be active next to role-strategy 898");
            reason = String.join(" | ", self.getDependencyErrors());
        } else {
            reason = String.valueOf(failed.cause) + " " + failed.getExceptionString();
        }
        assertTrue(reason.toLowerCase(Locale.ROOT).contains("role"), "the refusal must name role-strategy: " + reason);
        assertTrue(reason.contains("927"), "the refusal must name the required version 927 (D-35g): " + reason);
        System.out.println("T-02-122 refusal of batch-control: " + reason.lines().limit(3).toList());
        // nothing of Batch Control may be loaded
        assertTrue(Jenkins.get().getPluginManager().getPlugins().stream()
                        .noneMatch(p -> "batch-control".equals(p.getShortName()) && p.isActive()),
                "no active batch-control may exist next to role-strategy 898");
        assertEquals(null, Jenkins.get().getPlugin("batch-control"), "batch-control's plugin instance must not exist");
    }
}
