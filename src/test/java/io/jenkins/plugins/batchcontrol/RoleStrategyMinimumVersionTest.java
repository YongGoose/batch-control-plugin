package io.jenkins.plugins.batchcontrol;

import hudson.PluginWrapper;
import hudson.util.VersionNumber;
import java.io.InputStream;
import java.net.URL;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #78, D-35f (matrix notes 223-224, T-02-120/121): Batch Control declares role-strategy 918 as the
 * minimum of an <em>optional</em> dependency, which is what makes Jenkins refuse to load it next to
 * an older role-strategy (LIMITATIONS 9).
 *
 * <p>T-02-120 reads the manifest the build generates for the plugin under test
 * ({@code the.hpl} on the test class path, written by maven-hpi-plugin from the same POM data as the
 * {@code .hpi} manifest) and T-02-121 asserts that the running Jenkins parsed it into the plugin's
 * optional dependency. The minimum is compared, not pinned to one string, because the scheduled
 * role-strategy-latest build (#77) raises {@code role-strategy.version}; a minimum below 918 must
 * fail. What Jenkins does with an older role-strategy is T-02-122 (RoleStrategyTooOldRealJenkinsTest).
 *
 * <p>Written from issue #78, D-35f, LIMITATIONS 9 and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class RoleStrategyMinimumVersionTest {

    static final VersionNumber MINIMUM = new VersionNumber("918.v91e5468d8db_2");

    /** T-02-120: the generated manifest declares role-strategy, optional, at 918 or newer. */
    @Test
    public void t_02_120_manifestDeclaresOptionalRoleStrategyAtLeast918(JenkinsRule j) throws Exception {
        URL hpl = getClass().getClassLoader().getResource("the.hpl");
        assertNotNull(hpl, "premise: maven-hpi-plugin's test manifest the.hpl is on the test class path");
        Manifest mf;
        try (InputStream in = hpl.openStream()) {
            mf = new Manifest(in);
        }
        Attributes main = mf.getMainAttributes();
        assertEquals("batch-control", main.getValue("Short-Name"), "premise: the manifest is Batch Control's");
        String deps = main.getValue("Plugin-Dependencies");
        assertNotNull(deps, "the manifest must declare Plugin-Dependencies");
        String entry = null;
        for (String d : deps.split(",")) {
            if (d.trim().startsWith("role-strategy:")) {
                assertTrue(entry == null, "role-strategy must be declared once: " + deps);
                entry = d.trim();
            }
        }
        assertNotNull(entry, "the manifest must declare role-strategy: " + deps);
        assertTrue(entry.endsWith(";resolution:=optional"), "role-strategy must stay optional: " + entry);
        String version = entry.substring("role-strategy:".length(), entry.indexOf(';'));
        assertTrue(new VersionNumber(version).isNewerThanOrEqualTo(MINIMUM),
                "the declared minimum must be 918.v91e5468d8db_2 or newer, was " + version);
        assertTrue(new VersionNumber("898.vc050ed2424ca_").isOlderThan(new VersionNumber(version)),
                "guard: the BOM's 898 must be below the declared minimum");
    }

    /** T-02-121: the running Jenkins knows role-strategy as an optional dependency with that minimum. */
    @Test
    public void t_02_121_jenkinsParsedTheOptionalMinimum(JenkinsRule j) {
        PluginWrapper self = j.jenkins.getPluginManager().getPlugin("batch-control");
        assertNotNull(self, "premise: the plugin under test is loaded");
        PluginWrapper.Dependency dep = self.getOptionalDependencies().stream()
                .filter(d -> "role-strategy".equals(d.shortName)).findFirst().orElse(null);
        assertNotNull(dep, "role-strategy must be an optional dependency of batch-control: " + self.getOptionalDependencies());
        assertTrue(self.getMandatoryDependencies().stream().noneMatch(d -> "role-strategy".equals(d.shortName)),
                "role-strategy must not be a mandatory dependency");
        assertTrue(new VersionNumber(dep.version).isNewerThanOrEqualTo(MINIMUM),
                "the optional dependency's minimum must be 918 or newer, was " + dep.version);
    }
}
