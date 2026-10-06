package io.jenkins.plugins.batchcontrol;

import hudson.model.AdministrativeMonitor;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.HudsonPrivateSecurityRealm;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.ops.ConfigureWithoutGrantMonitor;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import java.net.URL;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 2, scenario L2-10 (matrix rows T-GAP-236 .. T-GAP-240, note 277): what the
 * standing-permission monitor lists.
 *
 * <p>Basis: SPEC item 8 "변경 통제 on 상태에서, 권한 부여 없이 Item/Configure·Create·Delete·Move를 가진
 * 사용자가 있으면 관리 화면에 경고(AdministrativeMonitor)가 표시된다"; LIMITATIONS 12 "The 'standing change
 * permissions' monitor is best-effort. Its verdict is cached for up to five minutes and it deliberately
 * ignores administrators, so it is a warning, never an enforcement point"; SPEC 1 (no new behaviour
 * while the switch is off); TEST-MATRIX T-08-48/T-08-53 (holders are named on Manage Jenkins, a group
 * as a group).
 *
 * <p>Each row installs one strategy and reads the monitor once, because the verdict is cached
 * (LIMITATIONS 12). The other monitor's warning is kept out of the way with a build authenticator
 * ({@link StrategyFixtures#configureBuildAuthenticator()}).
 *
 * <p>Written from docs/SPEC.md, docs/LIMITATIONS.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class MonitorStandingPermissionGapTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
    }

    /** T-GAP-236 (L2-10, SPEC 8): the matrix gives Item/Configure to group {@code devs}; the monitor names {@code devs}. */
    @Test
    public void t_gap_236_groupHolderIsListedByName() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy s = base();
        s.add(Jenkins.READ, PermissionEntry.group("devs"));
        s.add(Item.CONFIGURE, PermissionEntry.group("devs"));
        install(s, true);
        assertTrue(monitor().isActivated(), "a group holding Item/Configure activates the monitor");
        String text = manageText();
        assertTrue(text.contains("devs"), "the monitor must name the group devs: " + UsabilityFixtures.excerpt(text));
        assertFalse(text.contains("plainreader8"), "guard: a plain reader is not named");
    }

    /**
     * T-GAP-237 (L2-10, SPEC 8 "사용자", LIMITATIONS 12 best-effort): with Jenkins' own user database, a
     * matrix entry for {@code ghost} (no account) holding Item/Configure is not listed, while
     * {@code realcfg} (an account) holding it is.
     */
    @Test
    public void t_gap_237_entryWithoutAnAccountIsNotListed() throws Exception {
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        realm.createAccount("admin", "admin");
        realm.createAccount("realcfg", "realcfg");
        realm.createAccount("plainreader8", "plainreader8");
        j.jenkins.setSecurityRealm(realm);
        BatchControlMatrixAuthorizationStrategy s = base();
        for (String sid : new String[] {"ghost", "realcfg"}) {
            s.add(Jenkins.READ, PermissionEntry.user(sid));
            s.add(Item.CONFIGURE, PermissionEntry.user(sid));
        }
        install(s, true);
        String text = manageText();
        assertTrue(text.contains("realcfg"), "a real account holding Item/Configure is listed: " + UsabilityFixtures.excerpt(text));
        assertFalse(text.contains("ghost"), "an entry without an account is not listed: " + UsabilityFixtures.excerpt(text));
    }

    /**
     * T-GAP-238 (L2-10, LIMITATIONS 12 "deliberately ignores administrators"): group {@code admins}
     * holds Overall/Administer and is not listed; group {@code editors} with Item/Configure is listed
     * (guard).
     */
    @Test
    public void t_gap_238_administratorGroupIsNotListed() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy s = base();
        s.add(Jenkins.ADMINISTER, PermissionEntry.group("admins"));
        s.add(Jenkins.READ, PermissionEntry.group("editors"));
        s.add(Item.CONFIGURE, PermissionEntry.group("editors"));
        install(s, true);
        String text = manageText();
        assertTrue(text.contains("editors"), "guard: the editors group is listed: " + UsabilityFixtures.excerpt(text));
        assertFalse(text.contains("admins"), "a group holding Overall/Administer is not listed: " + UsabilityFixtures.excerpt(text));
    }

    /**
     * T-GAP-239 (L2-10, LIMITATIONS 12 best-effort, a bounded scan): 101 known users each holding
     * Item/Configure, a user {@code many} in 101 groups, and 101 group entries holding Item/Configure.
     * Manage Jenkins still renders (200) and the monitor still lists holders (at least one of the users
     * and one of the groups).
     */
    @Test
    public void t_gap_239_monitorStillRendersWithManyHolders() throws Exception {
        JenkinsRule.DummySecurityRealm realm = j.createDummySecurityRealm();
        String[] groups = new String[101];
        for (int i = 0; i < groups.length; i++) {
            groups[i] = String.format("grp%03d", i);
        }
        realm.addGroups("many", groups);
        j.jenkins.setSecurityRealm(realm);
        BatchControlMatrixAuthorizationStrategy s = base();
        for (int i = 0; i < 101; i++) {
            String user = String.format("cfg%03d", i);
            s.add(Jenkins.READ, PermissionEntry.user(user));
            s.add(Item.CONFIGURE, PermissionEntry.user(user));
            User.getById(user, true).save();
            s.add(Item.CONFIGURE, PermissionEntry.group(groups[i]));
        }
        s.add(Jenkins.READ, PermissionEntry.user("many"));
        User.getById("many", true).save();
        install(s, true);
        assertTrue(monitor().isActivated(), "the monitor must be activated");
        String text = manageText();
        assertTrue(text.matches("(?s).*\\bcfg\\d{3}\\b.*"), "the monitor must still list user holders: " + UsabilityFixtures.excerpt(text));
        assertTrue(text.matches("(?s).*\\bgrp\\d{3}\\b.*"), "the monitor must still list group holders: " + UsabilityFixtures.excerpt(text));
    }

    /**
     * T-GAP-240 (L2-10 (u), SPEC 8 "변경 통제 on 상태에서", SPEC 1): with change control off and the same
     * Configure holder as T-GAP-236, the monitor is not activated, Manage Jenkins names no holder, and
     * the monitor's message view, if it can be opened directly, names none either. Guard: with change
     * control on the holder is named (T-GAP-236).
     */
    @Test
    public void t_gap_240_changeControlOffListsNoHolders() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy s = base();
        s.add(Jenkins.READ, PermissionEntry.user("offcfg9"));
        s.add(Item.CONFIGURE, PermissionEntry.user("offcfg9"));
        install(s, false);
        assertFalse(monitor().isActivated(), "with change control off the monitor must not be activated");
        assertFalse(manageText().contains("offcfg9"), "with change control off Manage Jenkins names no holder");
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        WebResponse view = wc.getPage(new WebRequest(new URL(j.getURL(), "manage/" + monitor().getUrl() + "/message"),
                HttpMethod.GET)).getWebResponse();
        if (view.getStatusCode() == 200) {
            assertFalse(view.getContentAsString().contains("offcfg9"), "the monitor's message view names no holder while change"
                    + " control is off: " + UsabilityFixtures.excerpt(view.getContentAsString()));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static BatchControlMatrixAuthorizationStrategy base() {
        BatchControlMatrixAuthorizationStrategy s = new BatchControlMatrixAuthorizationStrategy();
        s.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        s.add(Jenkins.READ, PermissionEntry.user("plainreader8"));
        s.add(Item.READ, PermissionEntry.user("plainreader8"));
        return s;
    }

    private void install(BatchControlMatrixAuthorizationStrategy s, boolean changeControl) {
        j.jenkins.setAuthorizationStrategy(s);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(changeControl);
        cfg.save();
        StrategyFixtures.configureBuildAuthenticator();
    }

    private static AdministrativeMonitor monitor() {
        AdministrativeMonitor monitor = AdministrativeMonitor.all().get(ConfigureWithoutGrantMonitor.class);
        assertNotNull(monitor, "the standing-permission monitor must be registered");
        return monitor;
    }

    private String manageText() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        HtmlPage manage = wc.goTo("manage/");
        assertEquals(200, manage.getWebResponse().getStatusCode(), "Manage Jenkins must render");
        return manage.asNormalizedText();
    }
}
