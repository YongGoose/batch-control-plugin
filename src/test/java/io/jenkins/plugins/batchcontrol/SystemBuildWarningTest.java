package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.User;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import jenkins.security.QueueItemAuthenticatorConfiguration;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.WebResponse;
import org.jenkinsci.plugins.authorizeproject.AuthorizeProjectProperty;
import org.jenkinsci.plugins.authorizeproject.GlobalQueueItemAuthenticator;
import org.jenkinsci.plugins.authorizeproject.ProjectQueueItemAuthenticator;
import org.jenkinsci.plugins.authorizeproject.strategy.SpecificUsersAuthorizationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8, the acceptance line after the batch-control-strategy monitor (D-50, e2e-03
 * DEF-37): "while change control is on, jobs in the scope of a pending or active CONFIGURE grant
 * whose builds would run as SYSTEM under the configured build authenticators (for example
 * Authorize Project per-project with no strategy on the job) are listed by that monitor, and the
 * detail page of a CONFIGURE grant request on such a job warns the approver before the decision."
 * Matrix rows T-08-59 and T-08-60 (note 160).
 *
 * <p>bob (RequestGrant) files a CONFIGURE request for {@code sys-job}; a1 is the approver
 * (StrategyFixtures). The warning is recognised by the word "SYSTEM" together with the job name
 * on Manage Jenkins, and by "SYSTEM" on the request's detail page.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-50 and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class SystemBuildWarningTest {

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
     * T-08-59 (D-50 a, b): Authorize Project per-project (ProjectQueueItemAuthenticator) is the
     * only build authenticator; {@code sys-job} has no strategy of its own, {@code own-job} runs as
     * admin through its own AuthorizeProjectProperty. Pending CONFIGURE requests on both: the
     * monitor on Manage Jenkins names {@code sys-job} with "SYSTEM" and not {@code own-job}; the
     * detail page of the {@code sys-job} request warns a1 ("SYSTEM"), the {@code own-job} one does
     * not.
     */
    @Test
    public void t_08_59_perProjectAuthenticatorWithoutJobStrategyIsNamedAndWarned() throws Exception {
        String strategyId = j.jenkins.getDescriptorOrDie(SpecificUsersAuthorizationStrategy.class).getId();
        QueueItemAuthenticatorConfiguration.get().getAuthenticators()
                .add(new ProjectQueueItemAuthenticator(Collections.singletonMap(strategyId, true)));
        assertTrue(ProjectQueueItemAuthenticator.isConfigured(), "fixture: the per-project authenticator must be configured");
        j.createFreeStyleProject("sys-job");
        FreeStyleProject own = j.createFreeStyleProject("own-job");
        own.addProperty(new AuthorizeProjectProperty(new SpecificUsersAuthorizationStrategy("admin")));

        String sysRequest = request("sys-job");
        String ownRequest = request("own-job");

        String manage = manageText();
        assertTrue(manage.contains("sys-job") && manage.contains("SYSTEM"), "the batch-control-strategy monitor must name"
                + " sys-job, whose builds run as SYSTEM: " + UsabilityFixtures.excerpt(manage));
        assertFalse(manage.contains("own-job"), "a job whose builds run as a user must not be listed: "
                + UsabilityFixtures.excerpt(manage));

        String sysDetail = detailText(sysRequest);
        assertTrue(sysDetail.contains("SYSTEM"), "the detail page of a CONFIGURE request on sys-job must warn the"
                + " approver that its builds run as SYSTEM: " + UsabilityFixtures.excerpt(sysDetail));
        String ownDetail = detailText(ownRequest);
        assertFalse(ownDetail.contains("SYSTEM"), "no SYSTEM warning on the request for a job that runs as a user: "
                + UsabilityFixtures.excerpt(ownDetail));
    }

    /**
     * T-08-60 (D-50 negative twin): a global default build authorization (GlobalQueueItemAuthenticator
     * running builds as admin) gives {@code sys-job}'s builds a user identity: with the same
     * pending CONFIGURE request neither Manage Jenkins nor the detail page carries the warning.
     */
    @Test
    public void t_08_60_globalDefaultStrategyShowsNoSystemWarning() throws Exception {
        QueueItemAuthenticatorConfiguration.get().getAuthenticators()
                .add(new GlobalQueueItemAuthenticator(new SpecificUsersAuthorizationStrategy("admin")));
        j.createFreeStyleProject("sys-job");
        String id = request("sys-job");

        String manage = manageText();
        assertFalse(manage.contains("sys-job"), "with a global default build authorization sys-job must not be listed: "
                + UsabilityFixtures.excerpt(manage));
        String detail = detailText(id);
        assertTrue(detail.toLowerCase(Locale.ROOT).contains("sys-job"), "fixture: the detail page must show the request: "
                + UsabilityFixtures.excerpt(detail));
        assertFalse(detail.contains("SYSTEM"), "no SYSTEM warning when builds run as a user: "
                + UsabilityFixtures.excerpt(detail));
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

    private String detailText(String requestId) throws Exception {
        WebResponse detail = ApproverFormFixtures.get(j, "a1", "batch-control/grants/" + requestId + "/");
        assertEquals(200, detail.getStatusCode(), "fixture: a1 must open the request's detail page");
        return detail.getContentAsString();
    }
}
