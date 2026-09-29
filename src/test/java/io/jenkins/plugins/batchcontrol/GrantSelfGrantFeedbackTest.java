package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.AuthorizationMatrixProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTextArea;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.has;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 2, the acceptance line under D-35b (D-48, e2e-03 DEF-35): "when the guard reverts
 * part of a save, the saving user is told: a save made through an HTTP request (the configuration
 * form, a {@code config.xml} POST, {@code createItem}, a copy) answers 403 with a plain message
 * naming the item, saying the authorization entries were not kept because the user's Configure
 * comes only from a temporary grant, that the other changes were saved, and to ask an
 * administrator; the browser form shows it on a standard Jenkins page." Matrix rows T-02-47 ..
 * T-02-49 (note 147).
 *
 * <p>bob holds Configure on the job only through a JOB grant (StrategyFixtures); the job carries
 * its own matrix property with alice's Configure entry and a Read entry for bob, so the form
 * offers a row for bob in which he can tick Configure for himself. Every save also changes the
 * description, which must persist ("the other changes were saved").
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-48 and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class GrantSelfGrantFeedbackTest {

    private static final Pattern NOT_KEPT = Pattern.compile("(?i)not (been )?kept|not saved|were not|reverted|discarded|removed|restored");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
        StrategyFixtures.changeControlOn();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-02-47 (D-48, DEF-35): bob ticks Configure for himself in the job's matrix on the
     * configuration form and changes the description. The answer is 403 on a standard Jenkins
     * page with the plain message; the description change persists; bob gets no entry; one
     * GRANT_VIOLATION record names bob.
     */
    @Test
    public void t_02_47_formSelfGrantIsRefusedWithExplanationAndOtherChangesKept() throws Exception {
        FreeStyleProject job = guardedJob("guard-form");
        int violations = StrategyFixtures.records(ChangeType.GRANT_VIOLATION).size();

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("bob");
        HtmlPage configure = wc.getPage(job, "configure");
        assertEquals(200, configure.getWebResponse().getStatusCode(), "fixture: bob must reach the configuration form");
        HtmlForm form = configure.getFormByName("config");
        setDescription(form, "desc-after-form");
        HtmlCheckBoxInput box = bobsConfigureBox(configure);
        assertFalse(box.isChecked(), "fixture: bob's Configure box must start unticked");
        box.setChecked(true);

        Page answer = j.submit(form);
        assertEquals(violations + 1, StrategyFixtures.records(ChangeType.GRANT_VIOLATION).size(),
                "premise: the guard must have reverted the authorization change and recorded it");

        assertEquals(403, answer.getWebResponse().getStatusCode(), "a form save whose authorization change the guard"
                + " reverted must answer 403, got " + answer.getWebResponse().getStatusCode());
        assertTrue(answer instanceof HtmlPage, "the form save must be answered with an HTML page");
        HtmlPage page = (HtmlPage) answer;
        assertNotNull(page.getElementById("main-panel"), "the message must be shown on a standard Jenkins page: "
                + UsabilityFixtures.excerpt(page.asNormalizedText()));
        assertFeedback("form save", page.asNormalizedText(), job);
        assertOutcome(job, "desc-after-form", violations);
    }

    /**
     * T-02-48 (D-48, DEF-35): the same change through {@code POST config.xml}. 403 with the plain
     * message; the description change persists; bob gets no entry; one GRANT_VIOLATION record.
     */
    @Test
    public void t_02_48_configXmlSelfGrantIsRefusedWithExplanationAndOtherChangesKept() throws Exception {
        FreeStyleProject job = guardedJob("guard-post");
        int violations = StrategyFixtures.records(ChangeType.GRANT_VIOLATION).size();

        String xml = job.getConfigFile().asString()
                .replace("<description>desc-before</description>", "<description>desc-after-post</description>");
        String close = "</hudson.security.AuthorizationMatrixProperty>";
        assertTrue(xml.contains(close) && xml.contains("desc-after-post"), "fixture: unexpected config.xml: " + xml);
        xml = xml.replace(close, "<permission>USER:hudson.model.Item.Configure:bob</permission>" + close);

        Page answer = postConfigXml("bob", job, xml);
        assertEquals(violations + 1, StrategyFixtures.records(ChangeType.GRANT_VIOLATION).size(),
                "premise: the guard must have reverted the authorization change and recorded it");

        assertEquals(403, answer.getWebResponse().getStatusCode(), "a config.xml POST whose authorization change the"
                + " guard reverted must answer 403, got " + answer.getWebResponse().getStatusCode());
        assertFeedback("config.xml POST", UsabilityFixtures.text(answer), job);
        assertOutcome(job, "desc-after-post", violations);
    }

    /**
     * T-02-49 (D-48 "nothing changes for saves the guard does not touch"): bob's description-only
     * save through the form and through {@code POST config.xml} succeeds normally — no 403, no
     * GRANT_VIOLATION record, the description persists.
     */
    @Test
    public void t_02_49_saveThatDoesNotTouchAuthorizationSucceedsNormally() throws Exception {
        FreeStyleProject job = guardedJob("guard-plain");
        int violations = StrategyFixtures.records(ChangeType.GRANT_VIOLATION).size();

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("bob");
        HtmlPage configure = wc.getPage(job, "configure");
        HtmlForm form = configure.getFormByName("config");
        setDescription(form, "desc-plain-form");
        Page formAnswer = j.submit(form);
        int formCode = formAnswer.getWebResponse().getStatusCode();
        assertTrue(formCode >= 200 && formCode < 400, "a form save that does not touch authorization must succeed, got "
                + formCode + ": " + UsabilityFixtures.excerpt(UsabilityFixtures.text(formAnswer)));
        assertEquals("desc-plain-form", reload(job).getDescription());

        String xml = reload(job).getConfigFile().asString()
                .replace("<description>desc-plain-form</description>", "<description>desc-plain-post</description>");
        assertTrue(xml.contains("desc-plain-post"), "fixture: the description edit must be in the XML");
        Page postAnswer = postConfigXml("bob", job, xml);
        assertEquals(200, postAnswer.getWebResponse().getStatusCode(), "a config.xml POST that does not touch"
                + " authorization must answer 200");
        assertEquals("desc-plain-post", reload(job).getDescription());

        assertEquals(violations, StrategyFixtures.records(ChangeType.GRANT_VIOLATION).size(),
                "a save that does not touch authorization must write no GRANT_VIOLATION record");
    }

    // ---------------------------------------------------------------- helpers

    private FreeStyleProject guardedJob(String name) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject(name);
        p.setDescription("desc-before");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        amp.add(Item.READ, PermissionEntry.user("bob"));
        p.addProperty(amp);
        assertFalse(has(p, "bob", Item.CONFIGURE), "premise: bob holds no native Configure");
        StrategyFixtures.grant("bob", GrantScope.Type.JOB, name, Arrays.asList(GrantAction.CONFIGURE));
        assertTrue(has(p, "bob", Item.CONFIGURE), "premise: the grant confers Configure on the job");
        return p;
    }

    private FreeStyleProject reload(FreeStyleProject job) {
        return j.jenkins.getItemByFullName(job.getFullName(), FreeStyleProject.class);
    }

    private void assertOutcome(FreeStyleProject job, String description, int violationsBefore) throws Exception {
        FreeStyleProject current = reload(job);
        assertEquals(description, current.getDescription(), "the other changes of the save must be kept");
        AuthorizationMatrixProperty amp = current.getProperty(AuthorizationMatrixProperty.class);
        assertNotNull(amp, "the job's authorization property must be restored, not removed");
        assertFalse(amp.getGrantedPermissionEntries().getOrDefault(Item.CONFIGURE, java.util.Set.of())
                .contains(PermissionEntry.user("bob")), "bob must not have gained a permanent Configure entry");
        assertFalse(current.getConfigFile().asString().contains("Item.Configure:bob"),
                "the stored config.xml must not carry bob's Configure entry");
        List<io.jenkins.plugins.batchcontrol.model.ChangeRecord> v = StrategyFixtures.records(ChangeType.GRANT_VIOLATION);
        assertEquals(violationsBefore + 1, v.size(), "one GRANT_VIOLATION record must be written");
        assertEquals("bob", v.get(v.size() - 1).getUser(), "the GRANT_VIOLATION record must name bob");
    }

    /** The D-48 message: item, authorization entries not kept, temporary grant, other changes saved, ask an administrator. */
    private static void assertFeedback(String what, String text, FreeStyleProject job) {
        assertGuardFeedback(what, text, job.getFullName(), job.getFullDisplayName());
    }

    /**
     * The D-48 message, shared with the older D-35c rows (T-02-35, T-08-47): the item is named by
     * one of {@code itemNames} (full name or full display name), the authorization entries were not
     * kept, why, the other changes were saved, ask an administrator; no crash text.
     */
    static void assertGuardFeedback(String what, String text, String... itemNames) {
        String lower = text.toLowerCase(Locale.ROOT);
        String shown = UsabilityFixtures.excerpt(text);
        assertTrue(Arrays.stream(itemNames).anyMatch(text::contains), what + ": the message must name the item "
                + Arrays.toString(itemNames) + ": " + shown);
        assertTrue(lower.contains("authoriz"), what + ": the message must speak of the authorization entries: " + shown);
        assertTrue(NOT_KEPT.matcher(text).find(), what + ": the message must say the authorization entries were not"
                + " kept: " + shown);
        assertTrue(lower.contains("grant") || lower.contains("temporar"), what + ": the message must say why (Configure"
                + " comes only from a temporary grant): " + shown);
        assertTrue(lower.contains("other") && lower.contains("saved"), what + ": the message must say that the other"
                + " changes were saved: " + shown);
        assertTrue(lower.contains("administrator"), what + ": the message must say to ask an administrator: " + shown);
        UsabilityFixtures.assertPlainRefusal(what, text, null);
    }

    private Page postConfigXml(String user, FreeStyleProject job, String xml) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        WebRequest req = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml");
        req.setRequestBody(xml);
        return wc.getPage(req);
    }

    private static void setDescription(HtmlForm form, String value) {
        HtmlTextArea description = form.getTextAreaByName("description");
        description.setText(value);
    }

    /** The Configure checkbox in bob's row of the job's matrix table (matrix-auth's form). */
    private static HtmlCheckBoxInput bobsConfigureBox(HtmlPage page) {
        List<HtmlCheckBoxInput> boxes = page.getByXPath("//*[@*[contains(., 'USER:bob') or . = 'bob']]//input[@type='checkbox'"
                + " and contains(@name, 'hudson.model.Item.Configure')]");
        StringBuilder seen = new StringBuilder();
        for (Object o : page.getByXPath("//input[@type='checkbox']")) {
            org.htmlunit.html.HtmlInput in = (org.htmlunit.html.HtmlInput) o;
            org.htmlunit.html.DomNode tr = in.getParentNode();
            while (tr != null && !"tr".equals(tr.getNodeName())) {
                tr = tr.getParentNode();
            }
            seen.append("\n  ").append(in.getAttribute("name")).append(" tr=")
                    .append(tr instanceof org.htmlunit.html.DomElement
                            ? ((org.htmlunit.html.DomElement) tr).getAttribute("name") + "/"
                            + ((org.htmlunit.html.DomElement) tr).getAttribute("data-name") : "-");
        }
        assertEquals(1, boxes.size(), "fixture: the form must offer exactly one Configure box in bob's matrix row, found "
                + boxes.size() + "; checkboxes:" + seen);
        return boxes.get(0);
    }
}
