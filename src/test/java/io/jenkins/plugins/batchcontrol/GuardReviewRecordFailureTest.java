package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug hunt A R3-01 (TEST-MATRIX note 301), the review path: "Mark as reviewed" applies the review even
 * when its {@code GUARD_REVIEWED} change record cannot be appended, and the request does not answer
 * HTTP 500. Matrix row T-02-126.
 *
 * <p>Basis: SPEC 2 (D-58b: the "changed under a grant" state ends only through the explicit "Mark as
 * reviewed" action (POST, native Item/Configure or Overall/Administer), which writes a
 * {@code GUARD_REVIEWED} record), D-58a (4) (the {@code batch-control-strategy} monitor lists the items in
 * that state), the R3-01 contract (a failure to append the change record never stops the state change;
 * it is logged at WARNING or SEVERE), ARCHITECTURE 5 ({@code changedItems} of the grant file loses an
 * item when it is reviewed). The positive review with a writable store is T-02-64b
 * ({@link AuthorizationEntryGuardTest}); this row asserts its premise (the item listed) before the fault.
 *
 * <p>Batch Control matrix strategy, change control on, builds run as a fixed low-privilege account
 * (Authorize Project), as in {@link GuardReviewGapTest}.
 *
 * <p>Written from docs/SPEC.md item 2, docs/DECISIONS.md D-58a/D-58b and docs/ARCHITECTURE.md section 5
 * only (no src/main knowledge).
 */
@WithJenkins
public class GuardReviewRecordFailureTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);
        StrategyFixtures.configureBuildAuthenticator();
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-02-126 (P0): u1 saves {@code one} through a CONFIGURE window, so {@code one} is changed under a grant
     * (premise: the monitor is active and the stored window lists {@code one} in {@code changedItems}); the
     * window is revoked. With {@code batch-control/changes/} unwritable the administrator POSTs the monitor's
     * {@code markReviewed} for {@code one}: the answer is not an error (no HTTP 500), the review is applied
     * ({@code one} leaves {@code changedItems}, the monitor is no longer active), and the failed record is
     * logged at WARNING or SEVERE.
     */
    @Test
    public void t_02_126_markReviewedAppliesWhenTheRecordCannotBeWritten() throws Exception {
        FreeStyleProject one = j.createFreeStyleProject("one");
        String requestId = submitGrantOk(j, "u1", "one", List.of("CONFIGURE"), 30, "work on one", null, "a1");
        assertSuccess(decideGrant(j, "a1", requestId, "approve", "ok"), "fixture: approval by a1");
        String windowId = WindowStateFixtures.windowId("u1", "one");
        saveAs("u1", one);
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator revokes the window
            GrantService.get().revoke(windowId);
        }
        assertTrue(changedItems(windowId).contains("one"), "premise (D-58a, ARCHITECTURE 5): one is changed under the window: "
                + changedItems(windowId));
        assertTrue(StrategyFixtures.strategyMonitor().isActivated(), "premise (D-58a (4)): the monitor lists the changed item");

        int code;
        List<String> problems;
        try (RecordFaultFixtures.Fault ignored = RecordFaultFixtures.makeUnwritable(
                RecordFaultFixtures.changesDir(j.jenkins.getRootDir().toPath()));
             LogRecorder log = new LogRecorder().record("io.jenkins.plugins.batchcontrol", Level.WARNING).capture(200)) {
            WebResponse response = markReviewed("one");
            code = response.getStatusCode();
            assertTrue(code < 400, "R3-01: Mark as reviewed must complete although the GUARD_REVIEWED record cannot be written"
                    + " (no HTTP 500), got HTTP " + code + ": " + excerpt(response.getContentAsString()));
            problems = log.getRecords().stream()
                    .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                    .map(r -> r.getLevel() + " " + r.getMessage())
                    .collect(Collectors.toList());
        }

        assertFalse(changedItems(windowId).contains("one"), "R3-01, D-58b: the review must be applied: one leaves changedItems: "
                + changedItems(windowId));
        assertFalse(StrategyFixtures.strategyMonitor().isActivated(), "R3-01: with its only item reviewed the monitor is no longer active");
        assertFalse(problems.isEmpty(), "R3-01: the failed GUARD_REVIEWED record must be logged at WARNING or SEVERE");
    }

    // ---------------------------------------------------------------- helpers

    /** The item names inside the stored window's {@code changedItems} element (ARCHITECTURE 5), or an empty list. */
    private List<String> changedItems(String grantId) throws Exception {
        String stored = WindowStateFixtures.storedGrant(j, grantId);
        Matcher block = Pattern.compile("(?s)<changedItems>(.*?)</changedItems>").matcher(stored);
        if (!block.find()) {
            return List.of();
        }
        Matcher names = Pattern.compile(">([^<>]+)<").matcher(block.group(1));
        List<String> out = new java.util.ArrayList<>();
        while (names.find()) {
            String name = names.group(1).trim();
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return out;
    }

    /** {@code user} saves {@code item} through {@code config.xml} with a changed description. */
    private void saveAs(String user, FreeStyleProject item) throws Exception {
        String xml = item.getConfigFile().asString();
        String description = "<description>edited by " + user + "</description>";
        String edited;
        if (xml.contains("<description/>")) {
            edited = xml.replace("<description/>", description);
        } else if (xml.contains("<description>")) {
            edited = xml.replaceFirst("<description>[^<]*</description>", description);
        } else {
            edited = xml.replaceFirst("(<project(?:\\s[^>]*)?>)", "$1" + description);
        }
        assertFalse(edited.equals(xml), "fixture: the description changes");
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, user);
        WebRequest post = new WebRequest(wc.createCrumbedUrl(item.getUrl() + "config.xml"), HttpMethod.POST);
        post.setAdditionalHeader("Content-Type", "application/xml");
        post.setRequestBody(edited);
        int code = wc.getPage(post).getWebResponse().getStatusCode();
        assertTrue(code < 400, "fixture: " + user + " saves " + item.getFullName() + " inside the window, got HTTP " + code);
    }

    private WebResponse markReviewed(String item) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "admin");
        URL url = new URL(wc.createCrumbedUrl("manage/administrativeMonitor/batch-control-strategy/markReviewed").toExternalForm()
                + "&item=" + java.net.URLEncoder.encode(item, StandardCharsets.UTF_8));
        return wc.getPage(new WebRequest(url, HttpMethod.POST)).getWebResponse();
    }
}
