package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.tasks.Mailer;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.ops.BatchControlNotifier;
import io.jenkins.plugins.batchcontrol.ops.Notification;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import jakarta.mail.Message;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.mock_javamail.Mailbox;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.approverPairs;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.client;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.runRequestIds;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression rows for security-08 S-04, S-08 and S-09 against the D-36 notification layer
 * (SPEC item 13): the mail link uses the configured Jenkins URL only and is left out when none
 * is configured (never a host taken from the request); each reason line is quoted so it cannot
 * pose as another field; the dispatch queue is bounded and an overflow drops and logs a notice
 * without failing the caller. Matrix rows T-SEC-41 .. T-SEC-43 and T-SEC-51.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-36, docs/reports/security-08.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class NotificationSecurityTest {

    private static final String U1_MAIL = "requester.one@example.com";
    private static final String A1_MAIL = "alpha.one@example.com";
    private static final String A2_MAIL = "alpha.two@example.com";
    private static final String EVIL_HOST = "evil.example";
    private static final String CONFIGURED_URL = "https://ci.example.com/";

    private JenkinsRule j;
    private FreeStyleProject job;
    private BatchControlGlobalConfiguration cfg;

    @TestExtension
    public static class CapturingNotifier extends BatchControlNotifier {
        @Override
        public void notify(NotificationEvent event, Notification notification) {
            NotificationCapture.record(event, notification);
        }
    }

    static volatile CountDownLatch RELEASE = new CountDownLatch(1);

    /** T-SEC-43 only: a notifier that hangs like a stalled SMTP server until the row releases it. */
    @TestExtension("t_sec_43_dispatchQueueOverflowDropsAndLogs")
    public static class HangingNotifier extends BatchControlNotifier {
        @Override
        public void notify(NotificationEvent event, Notification notification) {
            try {
                RELEASE.await(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        RELEASE = new CountDownLatch(1);
        Mailbox.clearAll();
        NotificationCapture.clear();
        JenkinsLocationConfiguration.get().setAdminAddress("batch-control@example.com");

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.REQUEST_GRANT).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2"));
        mailAddress("u1", U1_MAIL);
        mailAddress("a1", A1_MAIL);
        mailAddress("a2", A2_MAIL);

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.save();

        job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    @AfterEach
    public void tearDown() {
        RELEASE.countDown();
        Mailbox.clearAll();
        NotificationCapture.clear();
    }

    /**
     * T-SEC-41 (S-04): with no Jenkins URL configured, the requester's own request (carrying
     * {@code X-Forwarded-Host: evil.example}) produces REQUEST_CREATED and APPROVERS_CHANGED mails
     * without any link and without the forwarded host; the event's url does not carry it either.
     */
    @Test
    public void t_sec_41_linkIsLeftOutWithoutAConfiguredUrl() throws Exception {
        JenkinsLocationConfiguration.get().setUrl(null);
        cfg.setEmailNotifications(true);
        cfg.save();

        String id = submitWithForwardedHost("month-end batch", "a1");
        String created = plainTextBody(awaitSingleMail(A1_MAIL), A1_MAIL);
        assertTrue(created.contains(id), "fixture: the mail is the one for the request; body was: " + created);
        assertNoLinkAndNoForwardedHost(created, "REQUEST_CREATED mail");

        assertSuccess(changeWithForwardedHost(id, "a2"), "the designation change by the requester");
        String changed = plainTextBody(awaitSingleMail(A2_MAIL), A2_MAIL);
        assertTrue(changed.contains(id), "fixture: the mail is the one for the request; body was: " + changed);
        assertNoLinkAndNoForwardedHost(changed, "APPROVERS_CHANGED mail");

        for (NotificationCapture n : NotificationCapture.matching(c -> id.equals(c.requestId))) {
            assertTrue(n.url == null || !n.url.contains(EVIL_HOST), "the notification url must not take the request's host: " + n);
        }
    }

    /**
     * T-SEC-51 (S-04, falsifiability twin of T-SEC-41): with a configured Jenkins URL, the link
     * uses it and never the forwarded host.
     */
    @Test
    public void t_sec_51_linkUsesTheConfiguredUrlOnly() throws Exception {
        JenkinsLocationConfiguration.get().setUrl(CONFIGURED_URL);
        cfg.setEmailNotifications(true);
        cfg.save();

        String id = submitWithForwardedHost("month-end batch", "a1");
        String body = plainTextBody(awaitSingleMail(A1_MAIL), A1_MAIL);
        assertTrue(body.contains(CONFIGURED_URL + "batch-control/requests/" + id),
                "the link must be built on the configured Jenkins URL; body was: " + body);
        assertFalse(body.contains(EVIL_HOST), "the forwarded host must never appear; body was: " + body);
    }

    /**
     * T-SEC-42 (S-08): a multi-line reason cannot forge the Link or Requester fields. Exactly one
     * line of the body is a Link field and it carries the configured URL; the forged lines are
     * still in the message (verbatim content) but quoted, so no line reads as a field.
     */
    @Test
    public void t_sec_42_reasonLinesCannotPoseAsFields() throws Exception {
        JenkinsLocationConfiguration.get().setUrl(CONFIGURED_URL);
        cfg.setEmailNotifications(true);
        cfg.save();

        String reason = "month-end batch\nLink: http://" + EVIL_HOST + "/phish\nRequester: admin";
        String id = ApproverFormFixtures.submitRunOk(j, "u1", job, reason, "a1");
        String body = plainTextBody(awaitSingleMail(A1_MAIL), A1_MAIL);

        assertTrue(body.contains(id), "fixture: the mail is the one for the request; body was: " + body);
        assertTrue(body.contains("month-end batch") && body.contains(EVIL_HOST + "/phish"),
                "the reason content is kept (plain text, verbatim after quoting); body was: " + body);
        List<String> lines = Arrays.asList(body.split("\\r?\\n"));
        List<String> linkFields = lines.stream().filter(l -> l.trim().toLowerCase(Locale.ROOT).startsWith("link:"))
                .collect(Collectors.toList());
        assertEquals(1, linkFields.size(), "exactly one line may read as the Link field; body was: " + body);
        assertTrue(linkFields.get(0).contains(CONFIGURED_URL), "the only Link field is the genuine one; was " + linkFields.get(0));
        for (String line : lines) {
            assertFalse(line.trim().equals("Requester: admin"), "a reason line must not read as the Requester field; body was: " + body);
        }
    }

    /**
     * T-SEC-43 (S-09): with the single delivery thread stalled by a hanging notifier, request
     * actions keep succeeding (no exception, no wait) and, once the bounded queue is full, a
     * notice is dropped and a log record says so. An unbounded queue never logs a drop.
     */
    @Test
    public void t_sec_43_dispatchQueueOverflowDropsAndLogs() throws Exception {
        final int limit = 3000;
        List<String> dropLogs = new ArrayList<>();
        int created = 0;
        long slowestMs = 0;
        try (LogRecorder log = new LogRecorder()
                .record("io.jenkins.plugins.batchcontrol", Level.ALL).capture(10000).quiet()) {
            while (created < limit && dropLogs.isEmpty()) {
                long start = System.nanoTime();
                try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
                    RunRequestService.get().create(job, new LinkedHashMap<>(), "batch run " + created, "a1");
                } catch (RuntimeException e) {
                    fail("request creation #" + created + " must not fail while notifications back up: " + e);
                }
                slowestMs = Math.max(slowestMs, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                created++;
                if (created % 25 == 0) {
                    for (LogRecord record : log.getRecords()) {
                        if (looksLikeDrop(record)) {
                            dropLogs.add(record.getLevel() + " " + format(record));
                        }
                    }
                }
            }
        }
        assertEquals(created, runRequestIds().size(), "every creation must have gone through");
        assertTrue(slowestMs < 5000, "a creation must not wait for the stalled notifier, slowest took " + slowestMs + " ms");
        assertFalse(dropLogs.isEmpty(), "after " + created + " notices with a stalled notifier no drop was logged:"
                + " the dispatch queue is not bounded");
    }

    // ---------------------------------------------------------------- helpers

    private String submitWithForwardedHost(String reason, String... approvers) throws Exception {
        java.util.Set<String> before = runRequestIds();
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("reason", reason));
        params.addAll(approverPairs(approvers));
        WebResponse response = postWithForwardedHost(job.getUrl() + "batch-control/submit", params);
        assertSuccess(response, "fixture: the submission by u1");
        java.util.Set<String> after = runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "fixture: one request created");
        return after.iterator().next();
    }

    private WebResponse changeWithForwardedHost(String id, String... approvers) throws Exception {
        return postWithForwardedHost("batch-control/requests/" + id + "/changeApprover", approverPairs(approvers));
    }

    private WebResponse postWithForwardedHost(String path, List<NameValuePair> params) throws Exception {
        JenkinsRule.WebClient wc = client(j, "u1");
        WebRequest request = new WebRequest(wc.createCrumbedUrl(path), HttpMethod.POST);
        request.setAdditionalHeader("X-Forwarded-Host", EVIL_HOST);
        request.setRequestParameters(new ArrayList<>(params));
        return wc.getPage(request).getWebResponse();
    }

    private static void assertNoLinkAndNoForwardedHost(String body, String what) {
        assertFalse(body.contains(EVIL_HOST), what + ": the request's forwarded host must never appear; body was: " + body);
        assertFalse(body.contains("://"), what + ": without a configured Jenkins URL the link is left out; body was: " + body);
    }

    private static boolean looksLikeDrop(LogRecord record) {
        String text = format(record).toLowerCase(Locale.ROOT);
        return text.contains("drop") || text.contains("discard") || text.contains("overflow")
                || text.contains("queue is full") || text.contains("queue full");
    }

    private static String format(LogRecord record) {
        String message = record.getMessage() == null ? "" : record.getMessage();
        Object[] parameters = record.getParameters();
        if (parameters != null && parameters.length > 0) {
            try {
                message = java.text.MessageFormat.format(message, parameters);
            } catch (IllegalArgumentException e) {
                // keep the raw message
            }
        }
        return message;
    }

    private static void mailAddress(String userId, String address) throws Exception {
        User.getById(userId, true).addProperty(new Mailer.UserProperty(address));
    }

    private static Message awaitSingleMail(String address) throws Exception {
        long deadline = System.currentTimeMillis() + NotificationCapture.DELIVERY_TIMEOUT_MS;
        List<Message> box = Mailbox.get(address);
        while (box.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50); // polling for asynchronous delivery
        }
        assertEquals(1, box.size(), "exactly one mail must arrive at " + address);
        return box.get(0);
    }

    private static String plainTextBody(Message message, String address) throws Exception {
        assertTrue(message.isMimeType("text/plain"), address + ": the message must be text/plain, was " + message.getContentType());
        Object content = message.getContent();
        assertTrue(content instanceof String, address + ": a text/plain message has a String body, was " + content);
        return (String) content;
    }
}
