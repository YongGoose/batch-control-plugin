package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.User;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Pattern;
import jenkins.security.QueueItemAuthenticatorConfiguration;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.authorizeproject.AuthorizeProjectProperty;
import org.jenkinsci.plugins.authorizeproject.GlobalQueueItemAuthenticator;
import org.jenkinsci.plugins.authorizeproject.ProjectQueueItemAuthenticator;
import org.jenkinsci.plugins.authorizeproject.strategy.SpecificUsersAuthorizationStrategy;
import org.jenkinsci.plugins.authorizeproject.strategy.TriggeringUsersAuthorizationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8, the acceptance line after the batch-control-strategy monitor (D-50, D-50a,
 * security-21): "while change control is on and the configured build authenticators would let a
 * build of a job without its own build authorization and without a user cause run as SYSTEM, the
 * {@code batch-control-strategy} monitor shows one fixed warning (no job names) and the detail
 * page of a pending request that includes CONFIGURE shows the same warning to users who may decide
 * it or hold BatchControl/Manage, not to the requester. A global default build authorization
 * removes both." Matrix rows T-08-59 .. T-08-62 (notes 160, 163).
 *
 * <p>bob (RequestGrant) files the CONFIGURE requests; a1 is the designated approver; m1 holds
 * BatchControl/Manage (StrategyFixtures). The warning is recognised by the word "SYSTEM"; the
 * wording is not pinned.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-50/D-50a, docs/reports/security-21.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class SystemBuildWarningTest {

    /** A count of jobs the viewer cannot see (D-50a withdrew it). */
    private static final Pattern HIDDEN_COUNT = Pattern.compile("(?i)\\d+\\s+more\\s+jobs?|cannot see|can't see");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
        StrategyFixtures.changeControlOn();
        User.getById("admin", true);
    }

    /**
     * T-08-59 (D-50a): Authorize Project per-project (ProjectQueueItemAuthenticator) with no global
     * default; {@code sys-job} has no strategy; a pending CONFIGURE request by bob on it. Manage
     * Jenkins shows the fixed warning without naming the job; the request's detail page shows it
     * to a1 (who may decide) and to m1 (BatchControl/Manage), not to bob (the requester), and no
     * page carries a hidden-job count.
     */
    @Test
    public void t_08_59_perProjectWithoutGlobalDefaultShowsFixedWarningToDecidersOnly() throws Exception {
        perProject();
        j.createFreeStyleProject("sys-job");
        String id = request("sys-job");

        String manage = manageText();
        assertTrue(manage.contains("SYSTEM"), "the batch-control-strategy monitor must show the SYSTEM-build warning: "
                + UsabilityFixtures.excerpt(manage));
        assertFalse(manage.contains("sys-job"), "the warning is instance-wide and names no job: "
                + UsabilityFixtures.excerpt(manage));
        assertFalse(HIDDEN_COUNT.matcher(manage).find(), "no hidden-job count: " + UsabilityFixtures.excerpt(manage));

        for (String decider : new String[] {"a1", "m1"}) {
            String detail = detailText(decider, id);
            assertTrue(detail.contains("SYSTEM"), decider + " must see the SYSTEM-build warning on the request: "
                    + UsabilityFixtures.excerpt(detail));
            assertFalse(HIDDEN_COUNT.matcher(detail).find(), decider + ": no hidden-job count: "
                    + UsabilityFixtures.excerpt(detail));
        }
        String requester = detailText("bob", id);
        assertTrue(requester.contains("sys-job"), "fixture: bob sees his own request");
        assertFalse(requester.contains("SYSTEM"), "the requester must not see the SYSTEM-build warning: "
                + UsabilityFixtures.excerpt(requester));
    }

    /**
     * T-08-60 (D-50a twin): a global default build authorization (GlobalQueueItemAuthenticator
     * running builds as admin): neither Manage Jenkins nor the detail page carries the warning.
     */
    @Test
    public void t_08_60_globalDefaultStrategyShowsNoSystemWarning() throws Exception {
        QueueItemAuthenticatorConfiguration.get().getAuthenticators()
                .add(new GlobalQueueItemAuthenticator(new SpecificUsersAuthorizationStrategy("admin")));
        j.createFreeStyleProject("sys-job");
        String id = request("sys-job");

        String manage = manageText();
        assertFalse(manage.contains("SYSTEM"), "with a global default build authorization there is no SYSTEM-build"
                + " warning: " + UsabilityFixtures.excerpt(manage));
        String detail = detailText("a1", id);
        assertTrue(detail.contains("sys-job"), "fixture: the detail page must show the request");
        assertFalse(detail.contains("SYSTEM"), "no SYSTEM-build warning when builds run as a user: "
                + UsabilityFixtures.excerpt(detail));
    }

    /**
     * T-08-61 (D-50a, security-21 S-21-01 case 1): the only build authenticator is a global default
     * that follows the triggering user (TriggeringUsersAuthorizationStrategy). A build without a
     * user cause (a timer, an SCM poll) still runs as SYSTEM, so the warning appears on Manage
     * Jenkins and to the approver.
     */
    @Test
    public void t_08_61_triggeringUserStrategyAloneStillWarns() throws Exception {
        QueueItemAuthenticatorConfiguration.get().getAuthenticators()
                .add(new GlobalQueueItemAuthenticator(new TriggeringUsersAuthorizationStrategy()));
        j.createFreeStyleProject("sys-job");
        String id = request("sys-job");

        String manage = manageText();
        assertTrue(manage.contains("SYSTEM"), "a strategy that follows the triggering user leaves timer and SCM builds"
                + " as SYSTEM, so the warning must appear: " + UsabilityFixtures.excerpt(manage));
        String detail = detailText("a1", id);
        assertTrue(detail.contains("SYSTEM"), "the approver must see the warning: " + UsabilityFixtures.excerpt(detail));
    }

    /**
     * T-08-62 (D-50a, S-21-01 case 2): per-project mode without a global default, and the only job
     * in the request's scope has its own build authorization (running as admin). A Configure
     * holder can remove it, so the warning still appears on Manage Jenkins and to the approver.
     */
    @Test
    public void t_08_62_jobsOwnBuildAuthorizationDoesNotRemoveTheWarning() throws Exception {
        perProject();
        FreeStyleProject own = j.createFreeStyleProject("own-job");
        own.addProperty(new AuthorizeProjectProperty(new SpecificUsersAuthorizationStrategy("admin")));
        String id = request("own-job");

        String manage = manageText();
        assertTrue(manage.contains("SYSTEM"), "a job's own build authorization can be removed by a Configure holder, so"
                + " the warning must stay: " + UsabilityFixtures.excerpt(manage));
        String detail = detailText("a1", id);
        assertTrue(detail.contains("SYSTEM"), "the approver must see the warning for own-job too: "
                + UsabilityFixtures.excerpt(detail));
    }

    private void perProject() {
        String strategyId = j.jenkins.getDescriptorOrDie(SpecificUsersAuthorizationStrategy.class).getId();
        QueueItemAuthenticatorConfiguration.get().getAuthenticators()
                .add(new ProjectQueueItemAuthenticator(Collections.singletonMap(strategyId, true)));
        assertTrue(ProjectQueueItemAuthenticator.isConfigured(), "fixture: the per-project authenticator must be configured");
    }

    private String request(String job) throws Exception {
        return submitGrantOk(j, "bob", "JOB", job, Arrays.asList("CONFIGURE"), 30, "maintenance of " + job, null, "a1");
    }

    private String manageText() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        HtmlPage manage = wc.goTo("manage/");
        assertEquals(200, manage.getWebResponse().getStatusCode());
        return manage.asNormalizedText();
    }

    private String detailText(String userId, String requestId) throws Exception {
        WebResponse detail = ApproverFormFixtures.get(j, userId, "batch-control/grants/" + requestId + "/");
        assertEquals(200, detail.getStatusCode(), "fixture: " + userId + " must open the request's detail page");
        return detail.getContentAsString();
    }
}
