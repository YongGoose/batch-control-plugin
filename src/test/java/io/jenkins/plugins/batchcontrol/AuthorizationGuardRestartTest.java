package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import hudson.model.User;
import hudson.security.AuthorizationMatrixProperty;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Arrays;
import jenkins.model.Jenkins;
import jenkins.security.QueueItemAuthenticatorConfiguration;
import org.jenkinsci.plugins.authorizeproject.GlobalQueueItemAuthenticator;
import org.jenkinsci.plugins.authorizeproject.strategy.SpecificUsersAuthorizationStrategy;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.WINDOW_MINUTES;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-58a (1)(5), ARCHITECTURE 5 (grant file fields): the "changed under a grant" state is stored
 * and survives a restart. Matrix row T-02-68 (note 177). Written from docs/SPEC.md,
 * docs/DECISIONS.md D-58a, docs/ARCHITECTURE.md and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
public class AuthorizationGuardRestartTest {

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-02-68: bob changes the script of {@code pipe} under his CONFIGURE window (his own POST).
     * After a restart and the end of the window, the build's entry for bob is reverted.
     */
    @Test
    public void t_02_68_guardedStateSurvivesARestart() throws Throwable {
        session.then(r -> {
            BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
            r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
            BatchControlMatrixAuthorizationStrategy strategy = StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy());
            strategy.add(Jenkins.READ, PermissionEntry.user("batch"));
            strategy.add(Item.READ, PermissionEntry.user("batch"));
            strategy.add(Item.BUILD, PermissionEntry.user("batch"));
            r.jenkins.setAuthorizationStrategy(strategy);
            User.getById("batch", true).save();
            QueueItemAuthenticatorConfiguration.get().getAuthenticators()
                    .add(new GlobalQueueItemAuthenticator(new SpecificUsersAuthorizationStrategy("batch")));
            StrategyFixtures.changeControlOn();
            WorkflowJob pipe = r.jenkins.createProject(WorkflowJob.class, "pipe");
            pipe.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
            StrategyFixtures.grant("bob", GrantScope.Type.JOB, "pipe", Arrays.asList(GrantAction.CONFIGURE));
            String xml = pipe.getConfigFile().asString().replace("<script>echo &apos;hello&apos;</script>",
                    "<script>properties([authorizationMatrix(entries: [user(name: &apos;bob&apos;, permissions: "
                            + "[&apos;Job/Configure&apos;])])])</script>");
            JenkinsRuleHelper.postConfigXml(r, "bob", pipe, xml);
        });
        session.then(r -> {
            BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(WINDOW_MINUTES + 1)), ZoneOffset.UTC));
            WorkflowJob pipe = r.jenkins.getItemByFullName("pipe", WorkflowJob.class);
            assertTrue(((CpsFlowDefinition) pipe.getDefinition()).getScript().contains("authorizationMatrix"),
                    "fixture: bob's script edit must have been saved");
            r.buildAndAssertSuccess(pipe);
            AuthorizationMatrixProperty amp = pipe.getProperty(AuthorizationMatrixProperty.class);
            assertTrue(amp == null || amp.getGrantedPermissionEntries().values().stream()
                    .noneMatch(s -> s.stream().anyMatch(pe -> "bob".equals(pe.getSid()))),
                    "after a restart the item changed under the grant is still guarded");
        });
    }

    /** POST config.xml as {@code user}; asserts success. */
    static final class JenkinsRuleHelper {
        private JenkinsRuleHelper() {
        }

        static void postConfigXml(org.jvnet.hudson.test.JenkinsRule r, String user, WorkflowJob job, String xml)
                throws Exception {
            org.jvnet.hudson.test.JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false)
                    .login(user);
            org.htmlunit.WebRequest req = new org.htmlunit.WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"),
                    org.htmlunit.HttpMethod.POST);
            req.setAdditionalHeader("Content-Type", "application/xml");
            req.setRequestBody(xml);
            int code = wc.getPage(req).getWebResponse().getStatusCode();
            assertTrue(code < 400, "fixture: bob's script edit inside his window must be saved, got " + code);
        }
    }
}
