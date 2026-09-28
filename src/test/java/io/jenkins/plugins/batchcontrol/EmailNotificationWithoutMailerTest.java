package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.LinkedHashMap;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 13, D-36, matrix row T-13-17: a real Jenkins started WITHOUT the Mailer plugin.
 * Jenkins boots, the global configuration page renders without the {@code emailNotifications}
 * option (its twin with Mailer is T-13-12), and a run request is created, approved and run.
 *
 * <p>This class deliberately references no Mailer type: its code runs in the JVM that lacks
 * the plugin.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-36 and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
public class EmailNotificationWithoutMailerTest {

    @RegisterExtension
    final RealJenkinsExtension rr = new RealJenkinsExtension().omitPlugins("mailer");

    /** T-13-17: without Mailer nothing breaks and the e-mail option is absent. */
    @Test
    public void t_13_17_withoutMailerTheOptionIsAbsentAndRequestsWork() throws Throwable {
        rr.then(EmailNotificationWithoutMailerTest::bootWithoutMailer);
    }

    private static void bootWithoutMailer(JenkinsRule r) throws Throwable {
        assertNull(r.jenkins.getPlugin("mailer"), "premise: the Mailer plugin is not installed");

        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        JenkinsRule.WebClient admin = r.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        WebResponse configure = admin.getPage(new WebRequest(new java.net.URL(r.getURL(), "configure"), HttpMethod.GET))
                .getWebResponse();
        assertEquals(200, configure.getStatusCode(), "the global configuration page must render without Mailer");
        String page = configure.getContentAsString();
        assertTrue(page.contains("runControlEnabled"), "premise: the Batch Control section is on the page");
        assertFalse(page.contains("emailNotifications"), "without Mailer the emailNotifications option must be absent");

        FreeStyleProject job = r.createFreeStyleProject("batch-x");
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
        RunRequest request;
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            request = RunRequestService.get().create(job, new LinkedHashMap<>(), "month-end batch", "a1");
        }
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            RunRequestService.get().approve(request.getId(), "ok");
        }
        r.waitUntilNoActivity();
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(request.getId()).getStatus());
        assertEquals(1, job.getBuilds().size());
    }
}
