package io.jenkins.plugins.batchcontrol;

import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Locale;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.MONITOR_ID;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8 (spec-review M-1, D-35d): "when the installed strategy is matrix-auth's global
 * matrix strategy and change control is on, the batch-control-strategy monitor shows, before its
 * install action, a warning that converting makes per-item authorization properties effective,
 * and the install action asks for confirmation repeating it." Plus the security page names the
 * matrix variant "Batch Control: Project-based Matrix Authorization Strategy" (D-35e/D-35f
 * checklist). Matrix rows T-02-108..110 (note 201).
 *
 * <p>"Before its install action" is read as "in the message shown with the button": core lays
 * the controls out beside the message, and their DOM order is the opposite (note 201).
 *
 * <p>The install action is found as the element of the monitor whose {@code href},
 * {@code data-url} or form {@code action} names {@code administrativeMonitor/batch-control-strategy/migrate};
 * core's confirmation link carries its question in {@code data-message}.
 *
 * <p>Written from docs/SPEC.md item 8 and docs/DECISIONS.md D-35d only (no src/main knowledge).
 */
@WithJenkins
public class StrategyMonitorPerItemTest {

    static final String DISPLAY_NAME = "Batch Control: Project-based Matrix Authorization Strategy";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        StrategyFixtures.changeControlOn();
        StrategyFixtures.configureBuildAuthenticator(); // isolate the strategy half of the monitor (note 53)
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-02-108: global matrix installed, change control on: the monitor's text says "per-item"
     * before the install action, and the install action asks for confirmation (core confirmation
     * link, {@code data-message}) repeating the per-item warning.
     */
    @Test
    public void t_02_108_globalMatrixMonitorWarnsPerItemBeforeConfirmedInstall() throws Exception {
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new GlobalMatrixAuthorizationStrategy()));
        assertTrue(StrategyFixtures.strategyMonitor().isActivated(), "premise: the monitor is active");
        HtmlPage manage = manage();
        DomElement install = installAction(manage);
        assertNotNull(install, "the monitor must offer the install action: " + excerpt(manage.asNormalizedText()));
        String message = install.getAttribute("data-message");
        assertTrue(message.toLowerCase(Locale.ROOT).contains("per-item"),
                "the install action must ask for confirmation repeating the per-item warning (data-message), got '" + message
                        + "' on " + excerpt(install.asXml()));
        DomNode alert = alertOf(install);
        // The monitor's own visible text (attributes such as data-message are not text). Core's
        // monitor markup puts the controls container first in the DOM and lays it out beside the
        // message, so DOM order cannot express "before"; the row asserts that the warning is part
        // of the message the administrator reads with the button, not only of the confirmation.
        String text = alert.asNormalizedText().toLowerCase(Locale.ROOT);
        assertTrue(text.contains("per-item"),
                "the monitor's message must itself carry the per-item warning: " + excerpt(alert.asNormalizedText()));
    }

    /** T-02-109 (guard): with the plain project-matrix strategy the per-item warning is absent. */
    @Test
    public void t_02_109_projectMatrixMonitorHasNoPerItemWarning() throws Exception {
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new ProjectMatrixAuthorizationStrategy()));
        assertTrue(StrategyFixtures.strategyMonitor().isActivated(), "premise: the monitor is active");
        HtmlPage manage = manage();
        DomElement install = installAction(manage);
        assertNotNull(install, "premise: the monitor offers the install action");
        DomNode alert = alertOf(install);
        assertFalse(alert.asXml().toLowerCase(Locale.ROOT).contains("per-item"),
                "with the project-matrix strategy there is no per-item warning: " + excerpt(alert.asNormalizedText()));
    }

    /** T-02-110: the security page and the descriptor name the matrix variant as SPEC/D-35f checklist says. */
    @Test
    public void t_02_110_matrixVariantDisplayNameOnSecurityPage() throws Exception {
        assertEquals(DISPLAY_NAME, new BatchControlMatrixAuthorizationStrategy().getDescriptor().getDisplayName(),
                "the matrix variant's display name");
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        wc.login("admin");
        String page = wc.goTo("manage/configureSecurity/").getWebResponse().getContentAsString();
        assertTrue(page.contains(DISPLAY_NAME), "the security page must list '" + DISPLAY_NAME + "'");
    }

    // ---------------------------------------------------------------- helpers

    private HtmlPage manage() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        wc.login("admin");
        return wc.goTo("manage/");
    }

    private static DomElement installAction(HtmlPage page) {
        String target = "administrativeMonitor/" + MONITOR_ID + "/migrate";
        for (HtmlElement e : page.getHtmlElementDescendants()) {
            if (e.getAttribute("href").contains(target) || e.getAttribute("data-url").contains(target)
                    || e.getAttribute("action").contains(target)) {
                return e;
            }
        }
        return null;
    }

    private static DomNode alertOf(DomElement e) {
        DomNode n = e;
        while (n != null && !(n instanceof HtmlElement h && h.getAttribute("class").contains("alert"))) {
            n = n.getParentNode();
        }
        return n != null ? n : e.getParentNode();
    }
}
