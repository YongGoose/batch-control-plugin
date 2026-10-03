package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import hudson.security.HudsonPrivateSecurityRealm;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTextArea;
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
 * Screen-contract rows from the hosting review's UI polish (2026-10-02) for the Batch Control
 * configuration under Manage Jenkins ({@code manage/batch-control-configuration/}): the page
 * offers Save and no Apply button (T-UI-27), and a refused save (HTTP 400) shows the
 * "Nothing was saved." alert with the typed values, in the same settings-subpage layout as the
 * page itself (T-UI-28). Note 186.
 *
 * <p>Same realm and strategy as {@link ConfigAuditTest} (Jenkins' own user database, so an unknown
 * approver id is really unknown): {@code manager} holds Overall/Read, Job/Read and
 * BatchControl/Manage; {@code admin} Administer.
 *
 * Written from docs/SPEC.md, docs/DECISIONS.md D-53 and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class ConfigPagePolishTest {

    private static final String CONFIG = "manage/batch-control-configuration/";
    private static final String NOTHING_SAVED = "Nothing was saved.";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        for (String id : new String[] {"admin", "manager", "a1"}) {
            realm.createAccount(id, id);
        }
        j.jenkins.setSecurityRealm(realm);
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        strategy.add(Jenkins.READ, PermissionEntry.user("manager"));
        strategy.add(Item.READ, PermissionEntry.user("manager"));
        strategy.add(BatchControlPermissions.MANAGE, PermissionEntry.user("manager"));
        j.jenkins.setAuthorizationStrategy(strategy);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-UI-27: for the administrator and for the manager the configuration page carries the
     * settings form with a Save button (premise) and no Apply button anywhere on the page.
     */
    @Test
    public void t_ui_27_configurationPageHasSaveAndNoApply() throws Exception {
        for (String userId : new String[] {"admin", "manager"}) {
            HtmlPage page = configPage(userId);
            HtmlForm form = settingsForm(page);
            assertFalse(buttonsCaptioned(form, "save").isEmpty(), "premise: " + userId + " is offered a Save button: "
                    + UsabilityFixtures.excerpt(page.asNormalizedText()));
            assertTrue(buttonsCaptioned(page.getDocumentElement(), "apply").isEmpty(), userId + ": the configuration page"
                    + " must not offer an Apply button, found " + buttonsCaptioned(page.getDocumentElement(), "apply"));
        }
    }

    /**
     * T-UI-28 (D-53): the manager saves the unknown approver id {@code no-such-user} with a timeout of
     * 24. The answer is HTTP 400 and shows the alert "Nothing was saved.", the settings form again
     * with both typed values kept, in the page's settings-subpage layout (same app bar and side panel
     * presence as the page itself, breadcrumb under Manage Jenkins). Nothing is stored.
     */
    @Test
    public void t_ui_28_refusedSaveShowsNothingSavedWithTypedValuesInTheSubpageLayout() throws Exception {
        HtmlPage original = configPage("manager");
        HtmlForm form = settingsForm(original);
        setApprovers(form, "no-such-user");
        UsabilityFixtures.setField(form, "pendingTimeoutHours", "24");
        Page answer = j.submit(form);

        assertEquals(400, answer.getWebResponse().getStatusCode(), "a refused save must answer 400");
        assertEquals(Arrays.asList("a1"), BatchControlGlobalConfiguration.get().getApprovers(), "nothing may be saved");
        assertEquals(72, BatchControlGlobalConfiguration.get().getPendingTimeoutHours(), "nothing may be saved");
        assertTrue(answer instanceof HtmlPage, "the refusal must be an HTML page");
        HtmlPage refused = (HtmlPage) answer;
        String text = refused.asNormalizedText();

        List<DomElement> alerts = refused.getByXPath("//*[contains(concat(' ', normalize-space(@class), ' '), ' jenkins-alert ')"
                + " or @role='alert']");
        assertTrue(alerts.stream().anyMatch(a -> a.asNormalizedText().contains(NOTHING_SAVED)),
                "the refusal must show the alert \"" + NOTHING_SAVED + "\": " + UsabilityFixtures.excerpt(text));
        assertTrue(text.contains("no-such-user"), "the refusal must name the unknown id: " + UsabilityFixtures.excerpt(text));

        HtmlForm again = settingsForm(refused);
        assertNotNull(again);
        assertTrue(UsabilityFixtures.pageKeepsValue(refused, "no-such-user"), "the typed approver id must be kept");
        assertTrue(UsabilityFixtures.pageKeepsValue(refused, "24"), "the typed timeout must be kept");

        assertEquals(hasClass(original, "jenkins-app-bar"), hasClass(refused, "jenkins-app-bar"),
                "the refusal must use the page's settings-subpage layout (app bar)");
        assertEquals(original.getElementById("side-panel") != null, refused.getElementById("side-panel") != null,
                "the refusal must use the page's settings-subpage layout (side panel)");
        assertTrue(breadcrumbText(refused).contains("Manage Jenkins"), "the refusal must sit under Manage Jenkins in the"
                + " breadcrumb like the page itself: " + breadcrumbText(refused));
        assertTrue(breadcrumbText(original).contains("Manage Jenkins"), "premise: the page sits under Manage Jenkins: "
                + breadcrumbText(original));
    }

    // ---------------------------------------------------------------- helpers

    private HtmlPage configPage(String userId) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        Page page = wc.getPage(new WebRequest(new URL(j.getURL(), CONFIG), HttpMethod.GET));
        assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: " + userId + " must open " + CONFIG);
        return (HtmlPage) page;
    }

    private static HtmlForm settingsForm(HtmlPage page) {
        for (HtmlForm form : page.getForms()) {
            if (UsabilityFixtures.hasField(form, "pendingTimeoutHours")) {
                return form;
            }
        }
        throw new AssertionError("the page must carry the settings form; forms: " + UsabilityFixtures.formActions(page));
    }

    /** Buttons and submit/button inputs whose caption, value or name is {@code word} (case-insensitive). */
    private static List<String> buttonsCaptioned(DomElement root, String word) {
        List<String> out = new ArrayList<>();
        for (HtmlElement e : root.getHtmlElementDescendants()) {
            String tag = e.getTagName();
            boolean button = "button".equals(tag) || ("input".equals(tag)
                    && ("submit".equalsIgnoreCase(e.getAttribute("type")) || "button".equalsIgnoreCase(e.getAttribute("type"))));
            if (!button) {
                continue;
            }
            for (String caption : new String[] {e.asNormalizedText(), e.getAttribute("value"), e.getAttribute("name")}) {
                if (caption != null && caption.trim().toLowerCase(Locale.ROOT).equals(word)) {
                    out.add(e.asXml().trim());
                    break;
                }
            }
        }
        return out;
    }

    private static boolean hasClass(HtmlPage page, String cssClass) {
        return !page.getByXPath("//*[contains(concat(' ', normalize-space(@class), ' '), ' " + cssClass + " ')]").isEmpty();
    }

    private static String breadcrumbText(HtmlPage page) {
        StringBuilder sb = new StringBuilder();
        for (Object o : page.getByXPath("//*[@id='breadcrumbs' or contains(@class, 'jenkins-breadcrumbs')]")) {
            sb.append(((DomElement) o).asNormalizedText()).append(' ');
        }
        return sb.toString();
    }

    private static void setApprovers(HtmlForm form, String value) {
        for (String tag : new String[] {"textarea", "input"}) {
            for (DomElement element : form.getElementsByTagName(tag)) {
                String name = element.getAttribute("name");
                if (name == null || !name.toLowerCase(Locale.ROOT).contains("approver")) {
                    continue;
                }
                if (element instanceof HtmlTextArea) {
                    ((HtmlTextArea) element).setText(value);
                    return;
                }
                if (element instanceof HtmlInput && !"checkbox".equals(element.getAttribute("type"))
                        && !"hidden".equals(element.getAttribute("type"))) {
                    ((HtmlInput) element).setValue(value);
                    return;
                }
            }
        }
        throw new AssertionError("fixture: the settings form must carry an approver text field");
    }
}
