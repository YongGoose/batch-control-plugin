package io.jenkins.plugins.batchcontrol;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dependency-01 M-3, matrix row T-02-86: a real Jenkins with every optional plugin absent. Only
 * batch-control, its required plugins (cloudbees-folder, ionicons-api, caffeine-api) and their required
 * dependencies are installed: every other plugin of the test classpath is omitted, which covers
 * the five compile-time optional plugins (matrix-auth, role-strategy, configuration-as-code,
 * mailer, rebuild) and the plugins matched by name only (workflow-cps, pipeline-model-definition,
 * naginator, build-token-root). Batch Control loads, the root page works, a direct Build Now is
 * gated, and a run request is submitted, approved and executed.
 *
 * <p>This class deliberately references no optional plugin type: its code runs in the JVM that
 * lacks them.
 *
 * <p>Written from docs/SPEC.md, docs/TEST-MATRIX.md and docs/reports/dependency-01.md only
 * (no src/main knowledge).
 */
public class OptionalDependencyWithoutAllOptionalTest {

    private final String[] omitted = OptionalDependencyFixtures.everythingButRequired();

    @RegisterExtension
    final RealJenkinsExtension rr = new RealJenkinsExtension().omitPlugins(omitted);

    /** T-02-86: with every optional plugin absent Batch Control loads and a run request is approved. */
    @Test
    public void t_02_86_withAllOptionalPluginsAbsentBatchControlLoadsAndRunsApprovedRequest() throws Throwable {
        Set<String> omit = new TreeSet<>(Arrays.asList(omitted));
        for (String name : OptionalDependencyFixtures.COMPILE_TIME_OPTIONAL) {
            assertTrue(omit.contains(name), "premise: the boot must omit " + name + ", omitted " + omit);
        }
        for (String name : new String[] {"workflow-cps", "pipeline-model-definition", "naginator", "build-token-root"}) {
            assertTrue(omit.contains(name), "premise: the boot must omit " + name + ", omitted " + omit);
        }
        rr.then(OptionalDependencyWithoutAllOptionalTest::boot);
    }

    private static void boot(JenkinsRule r) throws Throwable {
        OptionalDependencyFixtures.rootPageAndApprovedRunWork(r,
                "matrix-auth", "role-strategy", "configuration-as-code", "mailer", "rebuild",
                "workflow-cps", "pipeline-model-definition", "naginator", "build-token-root");
    }
}
