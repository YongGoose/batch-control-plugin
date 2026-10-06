package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.HudsonPrivateSecurityRealm;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 1, scenario L1-08: the requester-lacks-Build notice for a requester who cannot be
 * resolved. Matrix rows T-GAP-138 and T-GAP-139 (note 276).
 *
 * <p>Basis: SPEC 5 "The request detail page and the approver notification state when the
 * requester lacks {@code Item/Build} on the job" (D-57, D-38a); LIMITATIONS 49 "The notice ... is
 * evaluated for the requester and cached per request for up to five minutes", so every
 * re-evaluation here moves the plugin clock six minutes on. A requester whose account cannot be
 * resolved any more cannot be shown to hold Build, so the notice appears (fail closed); no
 * document says so in as many words (note 276 ambiguity). The notice text is the frozen sentence
 * of RequesterBuildNoticeTest.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-38a/D-57 and docs/LIMITATIONS.md only (no
 * src/main knowledge).
 */
@WithJenkins
public class RequesterNoticeGapTest {

    private static final String NOTICE = RequesterBuildNoticeTest.NOTICE;
    private static final Instant T = Instant.parse("2026-09-24T12:00:00Z");

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(strategy());
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        job = j.createFreeStyleProject("gap-notice");
        setBatchControl(job, new BatchControlJobProperty(true));
        BatchClock.setForTest(Clock.fixed(T, ZoneOffset.UTC));
    }

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
    }

    /**
     * T-GAP-138 (L1-08 case 1): r (Request, Item/Read, Item/Build) files a request; a1's view of it
     * shows no notice (guard). r's user record is then deleted; after the cache life a1's view of
     * the same request shows the notice.
     */
    @Test
    public void t_gap_138_deletedRequesterGetsTheNotice() throws Exception {
        String id = submitRunOk(j, "r", job, "month-end batch", "a1");
        String before = detail(j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("a1"), id);
        assertFalse(before.contains(NOTICE), "guard: no notice while r holds Item/Build: " + excerpt(before));

        User.getById("r", true).delete();
        assertNull(User.getById("r", false), "premise: r's user record is deleted");
        BatchClock.setForTest(Clock.fixed(T.plus(Duration.ofMinutes(6)), ZoneOffset.UTC));

        String after = detail(j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("a1"), id);
        assertTrue(after.contains(NOTICE), "the approver's view must state that the requester lacks Build: " + excerpt(after));
    }

    /**
     * T-GAP-139 (L1-08 case 2 (F)): r files a request under the dummy realm. The realm is switched
     * to {@code HudsonPrivateSecurityRealm} with an account for a1 only: a1's view shows the notice.
     * r then gets an account again (Item/Build through the unchanged strategy); after the cache
     * life the next view no longer shows it.
     */
    @Test
    public void t_gap_139_realmSwitchShowsTheNoticeUntilTheRequesterHasAnAccount() throws Exception {
        String id = submitRunOk(j, "r", job, "month-end batch", "a1");
        User.getById("r", true).delete();

        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        realm.createAccount("a1", "a1-secret-41");
        j.jenkins.setSecurityRealm(realm);
        j.jenkins.setAuthorizationStrategy(strategy());
        BatchClock.setForTest(Clock.fixed(T.plus(Duration.ofMinutes(6)), ZoneOffset.UTC));

        String missing = detail(login("a1", "a1-secret-41"), id);
        assertTrue(missing.contains(NOTICE), "with no account for r the page must show the notice: " + excerpt(missing));

        realm.createAccount("r", "r-secret-41");
        BatchClock.setForTest(Clock.fixed(T.plus(Duration.ofMinutes(13)), ZoneOffset.UTC));
        String restored = detail(login("a1", "a1-secret-41"), id);
        assertFalse(restored.contains("does not have Build permission"),
                "once r has an account holding Build again the next view must not show the notice: " + excerpt(restored));
    }

    // ------------------------------------------------------------------ helpers

    private static MockAuthorizationStrategy strategy() {
        return new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("r")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1");
    }

    private JenkinsRule.WebClient login(String user, String password) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.login(user, password);
        return wc;
    }

    private String detail(JenkinsRule.WebClient wc, String id) throws Exception {
        WebResponse page = wc.getPage(new WebRequest(new URL(j.getURL(), "batch-control/requests/" + id + "/"), HttpMethod.GET))
                .getWebResponse();
        assertEquals(200, page.getStatusCode(), "the approver opens the request page");
        return page.getContentAsString();
    }
}
