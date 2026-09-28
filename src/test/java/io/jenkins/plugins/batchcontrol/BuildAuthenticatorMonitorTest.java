package io.jenkins.plugins.batchcontrol;

import hudson.model.AdministrativeMonitor;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import jenkins.security.QueueItemAuthenticatorConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * security-05 S-02, D-35d(2), SPEC item 8: builds that run as SYSTEM are outside the self-grant
 * guard, so the {@code batch-control-strategy} monitor warns when change control is on and no
 * build authenticator (Authorize Project) is configured. Matrix row T-02-41.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-35d and docs/reports/security-05.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class BuildAuthenticatorMonitorTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
    }

    /**
     * T-02-41: with the Batch Control matrix strategy installed (so the strategy half of the
     * monitor is satisfied) and change control on, no QueueItemAuthenticator activates the
     * monitor and /manage mentions Authorize Project. With a QueueItemAuthenticator configured
     * the monitor is quiet and the warning is gone. Guard: with change control off and no
     * authenticator the monitor is quiet (SPEC 1).
     */
    @Test
    public void t_02_41_monitorWarnsWithoutBuildAuthenticator() throws Exception {
        AdministrativeMonitor monitor = StrategyFixtures.strategyMonitor();
        assertTrue(QueueItemAuthenticatorConfiguration.get().getAuthenticators().isEmpty(), "premise: no build authenticator");
        assertFalse(monitor.isActivated(), "guard: with change control off the monitor must be quiet");

        BatchControlGlobalConfiguration cfg = StrategyFixtures.changeControlOn();
        assertTrue(monitor.isActivated(), "change control on without a build authenticator must activate the monitor");
        String manage = j.createWebClient().login("admin").goTo("manage/").getWebResponse().getContentAsString();
        assertTrue(manage.contains("Authorize Project"), "the monitor's message must mention Authorize Project");

        StrategyFixtures.configureBuildAuthenticator();
        assertFalse(monitor.isActivated(), "with a build authenticator and the Batch Control strategy the monitor must be quiet");
        String after = j.createWebClient().login("admin").goTo("manage/").getWebResponse().getContentAsString();
        assertFalse(after.contains("Authorize Project"), "the warning must disappear once an authenticator is configured");

        cfg.setChangeControlEnabled(false);
        cfg.save();
        assertFalse(monitor.isActivated());
    }
}
