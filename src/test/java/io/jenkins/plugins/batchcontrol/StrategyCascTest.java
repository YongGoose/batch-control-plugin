package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.yaml.YamlSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.has;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 2, D-35a: the strategy and the global configuration round-trip through JCasC.
 * Matrix rows T-02-20 (matrix-auth subclass, PoC-5 row 8) and T-02-21 (role-strategy subclass,
 * PoC-5 row 8). PoC-5 found that without the subclass's own configurator JCasC reports
 * "can't handle type" and exports nothing, so the export is asserted, not only the apply.
 *
 * <p>The YAML symbols {@code batchControlProjectMatrix} and {@code batchControlRoleBased} are
 * the PoC-5 names; they are listed in the test-author report as expected API.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-35a and docs/POC-RESULTS.md PoC-5 only
 * (no src/main knowledge).
 */
@WithJenkins
public class StrategyCascTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
    }

    private static String export() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConfigurationAsCode.get().export(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void apply(String yaml) throws Exception {
        ConfigurationAsCode.get().configureWith(YamlSource.of(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * T-02-20: the Batch Control matrix strategy exports under its own symbol with its entries,
     * the global configuration exports its values, and applying the symbol installs the
     * subclass with exactly the applied entries. Guard: matrix-auth's own {@code projectMatrix}
     * symbol still installs the plain parent (an explicit administrator choice).
     */
    @Test
    public void t_02_20_matrixStrategyAndGlobalConfigRoundTripThroughCasc() throws Exception {
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("casc-approver-7"));
        cfg.save();

        String yaml = export();
        assertTrue(yaml.contains("batchControlProjectMatrix"), "the export must name the Batch Control matrix strategy:\n" + yaml);
        assertTrue(yaml.contains("alice") && yaml.contains("c1"), "the export must carry the matrix entries");
        assertTrue(yaml.contains("casc-approver-7"), "the export must carry the Batch Control global configuration (approvers)");
        assertTrue(yaml.contains("changeControlEnabled: true"), "the export must carry the change-control switch");

        apply("jenkins:\n  authorizationStrategy:\n    batchControlProjectMatrix:\n      entries:\n"
                + "        - user:\n            name: admin\n            permissions:\n              - Overall/Administer\n"
                + "        - user:\n            name: carol\n            permissions:\n              - Overall/Read\n"
                + "              - BatchControl/RequestGrant\n");
        assertSame(BatchControlMatrixAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "applying the symbol must install the Batch Control matrix strategy");
        assertTrue(has(j.jenkins, "carol", Jenkins.READ), "the applied entry must be effective");
        assertTrue(has(j.jenkins, "carol", io.jenkins.plugins.batchcontrol.security.BatchControlPermissions.REQUEST_GRANT),
                "a BatchControl/<Name> permission must be accepted by the configurator (D-41)");
        assertFalse(has(j.jenkins, "alice", Jenkins.READ), "an entry that is not in the applied YAML must be gone");

        apply("jenkins:\n  authorizationStrategy:\n    projectMatrix:\n      entries:\n"
                + "        - user:\n            name: admin\n            permissions:\n              - Overall/Administer\n");
        assertSame(ProjectMatrixAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "guard: matrix-auth's own symbol must still install the plain parent");
    }

    /**
     * T-02-21: the Batch Control role strategy exports under its own symbol with its roles, and
     * applying the symbol installs the subclass with effective item roles. Guard: a job outside
     * the applied pattern gets nothing.
     */
    @Test
    public void t_02_21_roleStrategyRoundTripsThroughCasc() throws Exception {
        j.jenkins.setAuthorizationStrategy(
                new BatchControlRoleBasedAuthorizationStrategy(StrategyFixtures.roles(), Collections.emptySet()));
        String yaml = export();
        assertTrue(yaml.contains("batchControlRoleBased"), "the export must name the Batch Control role strategy:\n" + yaml);
        assertTrue(yaml.contains("team-.*"), "the export must carry the item role pattern");

        apply("jenkins:\n  authorizationStrategy:\n    batchControlRoleBased:\n      roles:\n        global:\n"
                + "          - name: admin\n            permissions:\n              - Overall/Administer\n"
                + "            entries:\n              - user: admin\n"
                + "        items:\n          - name: team\n            pattern: team-.*\n            permissions:\n              - Job/Configure\n"
                + "            entries:\n              - user: bob\n");
        assertSame(BatchControlRoleBasedAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "applying the symbol must install the Batch Control role strategy");
        assertTrue(has(j.createFreeStyleProject("team-z"), "bob", Item.CONFIGURE), "the applied item role must be effective");
        assertFalse(has(j.createFreeStyleProject("other-z"), "bob", Item.CONFIGURE), "guard: the item role must not reach a non-matching job");
    }
}
