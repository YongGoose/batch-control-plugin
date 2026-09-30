package io.jenkins.plugins.batchcontrol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

/**
 * dependency-01 M-3, matrix row T-02-84: a real Jenkins started WITHOUT configuration-as-code, a
 * compile-time optional plugin guarded by {@code @Extension(optional = true)} alone. Batch
 * Control loads, the root page, {@code /manage} and the Batch Control screen render, a direct
 * Build Now of an approval-required job is gated, and a run request is submitted, approved and
 * executed.
 *
 * <p>This class deliberately references no configuration-as-code type: its code runs in the JVM that lacks
 * the plugin. The shared steps live in {@link OptionalDependencyFixtures}, which is held to the
 * same rule.
 *
 * <p>Written from docs/SPEC.md, docs/TEST-MATRIX.md and docs/reports/dependency-01.md only
 * (no src/main knowledge).
 */
public class OptionalDependencyWithoutConfigurationAsCodeTest {

    @RegisterExtension
    final RealJenkinsExtension rr = new RealJenkinsExtension().omitPlugins("configuration-as-code");

    /** T-02-84: without configuration-as-code Batch Control loads and a run request is submitted and approved. */
    @Test
    public void t_02_84_withoutConfigurationAsCodeBatchControlLoadsAndRunsApprovedRequest() throws Throwable {
        rr.then(OptionalDependencyWithoutConfigurationAsCodeTest::boot);
    }

    private static void boot(JenkinsRule r) throws Throwable {
        OptionalDependencyFixtures.rootPageAndApprovedRunWork(r, "configuration-as-code");
    }
}
