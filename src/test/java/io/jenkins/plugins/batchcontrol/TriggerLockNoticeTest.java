package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6, "when {@code blockTimer} or {@code blockUpstream} is on for a job, the job page
 * shows a notice naming the switch that blocks it and how to clear it (the job configuration,
 * which is change-controlled), to users who can read the job. The notice is absent when both are
 * off or when run control is off. (#21)". Matrix rows T-06-49, T-06-50 (note 86).
 *
 * <p>The switch is recognised by its name, either the field name ({@code blockTimer},
 * {@code blockUpstream}) or its label ("Block timer", "Block upstream"), case-insensitively.
 * The viewer is a plain reader (Jenkins/Read + Item/Read), who has no Configure link in the
 * sidebar, so the "how to clear it" half is measured inside the notice itself.
 *
 * Written from docs/SPEC.md, issue #21 and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class TriggerLockNoticeTest {

    private static final Pattern TIMER = Pattern.compile("(?i)block\\s*timer");
    private static final Pattern UPSTREAM = Pattern.compile("(?i)block\\s*upstream");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ).everywhere().to("reader"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("admin"));
        cfg.save();
    }

    /**
     * T-06-49 (#21): a reader of a job with blockTimer on sees a notice naming blockTimer (and
     * not blockUpstream) that points at the job configuration; with blockUpstream on, the notice
     * names blockUpstream (and not blockTimer); with both on, both are named.
     */
    @Test
    public void t_06_49_jobPageNoticeNamesTheBlockingSwitch() throws Exception {
        job("notice-timer", true, false);
        job("notice-upstream", false, true);
        job("notice-both", true, true);

        String timer = jobText("notice-timer");
        assertTrue(TIMER.matcher(timer).find(), "the job page must name blockTimer while it is on: " + excerpt(timer));
        assertFalse(UPSTREAM.matcher(timer).find(), "blockUpstream is off and must not be named: " + excerpt(timer));
        assertNoticeExplainsHowToClear("notice-timer", TIMER);

        String upstream = jobText("notice-upstream");
        assertTrue(UPSTREAM.matcher(upstream).find(), "the job page must name blockUpstream while it is on: " + excerpt(upstream));
        assertFalse(TIMER.matcher(upstream).find(), "blockTimer is off and must not be named: " + excerpt(upstream));
        assertNoticeExplainsHowToClear("notice-upstream", UPSTREAM);

        String both = jobText("notice-both");
        assertTrue(TIMER.matcher(both).find() && UPSTREAM.matcher(both).find(),
                "with both switches on the notice must name both: " + excerpt(both));
    }

    /**
     * T-06-50 (#21, negative twin): no notice when both switches are off, and none when run
     * control is off even though the job's switches are on. The premise is read back so that an
     * always-absent notice cannot pass: the same job shows it again once run control is on.
     */
    @Test
    public void t_06_50_noNoticeWhenBothOffOrRunControlOff() throws Exception {
        job("notice-off", false, false);
        job("notice-rc-off", true, true);

        String off = jobText("notice-off");
        assertFalse(TIMER.matcher(off).find() || UPSTREAM.matcher(off).find(),
                "no switch may be named when both are off: " + excerpt(off));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(false);
        cfg.save();
        assertFalse(cfg.isRunControlEnabled(), "fixture: run control off");
        BatchControlJobProperty property = j.jenkins.getItemByFullName("notice-rc-off", FreeStyleProject.class)
                .getProperty(BatchControlJobProperty.class);
        assertTrue(property.isBlockTimer() && property.isBlockUpstream(), "fixture: the switches are still on");
        String rcOff = jobText("notice-rc-off");
        assertFalse(TIMER.matcher(rcOff).find() || UPSTREAM.matcher(rcOff).find(),
                "no notice while run control is off: " + excerpt(rcOff));

        cfg.setRunControlEnabled(true);
        cfg.save();
        String rcOn = jobText("notice-rc-off");
        assertTrue(TIMER.matcher(rcOn).find() && UPSTREAM.matcher(rcOn).find(),
                "control: the same job shows the notice once run control is on: " + excerpt(rcOn));
    }

    // ---------------------------------------------------------------- helpers

    private void job(String name, boolean blockTimer, boolean blockUpstream) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(blockTimer);
        property.setBlockUpstream(blockUpstream);
        setBatchControl(job, property);
        assertEquals(blockTimer, property.isBlockTimer(), "fixture: blockTimer");
        assertEquals(blockUpstream, property.isBlockUpstream(), "fixture: blockUpstream");
    }

    private HtmlPage jobPage(String name) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("reader");
        HtmlPage page = (HtmlPage) wc.getPage(new WebRequest(new URL(j.getURL(), "job/" + name + "/"), HttpMethod.GET));
        assertEquals(200, page.getWebResponse().getStatusCode(), "the reader must reach job/" + name + "/");
        return page;
    }

    private String jobText(String name) throws Exception {
        return jobPage(name).asNormalizedText();
    }

    /** The element naming the switch, or one of its three nearest ancestors, points at the job configuration. */
    private void assertNoticeExplainsHowToClear(String name, Pattern switchName) throws Exception {
        HtmlPage page = jobPage(name);
        List<DomElement> candidates = page.getByXPath("//body//*[not(self::script) and not(self::style)]");
        boolean found = false;
        for (DomElement element : candidates) {
            String own = element.getTextContent();
            if (own == null || !switchName.matcher(own).find() || hasChildNaming(element, switchName)) {
                continue;
            }
            DomNode current = element;
            for (int level = 0; level < 4 && current != null; level++) {
                String text = current.getTextContent().toLowerCase(Locale.ROOT);
                if (text.contains("configur")) {
                    found = true;
                    break;
                }
                current = current.getParentNode();
            }
        }
        assertTrue(found, "the notice naming the switch must say how to clear it (the job configuration): "
                + excerpt(page.asNormalizedText()));
    }

    private static boolean hasChildNaming(DomElement element, Pattern switchName) {
        for (DomElement child : element.getChildElements()) {
            if (switchName.matcher(child.getTextContent()).find()) {
                return true;
            }
        }
        return false;
    }

    private static String excerpt(String text) {
        return text.length() > 1500 ? text.substring(0, 1500) + "..." : text;
    }
}
