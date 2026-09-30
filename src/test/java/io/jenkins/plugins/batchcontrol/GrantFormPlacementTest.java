package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * e2e-04 FD-08, SPEC 8 with the SPEC 6 usability line (the job page's Request Change Permission
 * opens the request form with the job filled in; invalid input is answered next to the field):
 * the form must not sit below the lists of requests and grants. Matrix row T-UI-26 (note 171).
 *
 * <p>Placement is read as document order in {@code #main-panel}: the form (action ending in
 * {@code batch-control/grants/create}) and, after a refused submission, the error text must
 * come before the first table. Written from docs/SPEC.md, docs/reports/e2e-04.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class GrantFormPlacementTest {

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
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-UI-26 (FD-08): with several of u1's grant requests listed, u1 opens the prefilled grant
     * form of {@code fd8-job} (the job page's entry). The form comes before the first list table.
     * A submission longer than maxGrantMinutes (240) is refused, and the refusal's message
     * ("240") and the re-shown form also come before the first list table.
     */
    @Test
    public void t_ui_26_grantFormAndItsErrorComeBeforeTheLists() throws Exception {
        j.createFreeStyleProject("fd8-job");
        for (int i = 0; i < 3; i++) {
            j.createFreeStyleProject("fd8-other-" + i);
            submitGrantOk(j, "u1", "JOB", "fd8-other-" + i, Arrays.asList("CONFIGURE"), 30, "maintenance " + i, null, "a1");
        }

        HtmlPage prefilled = UsabilityFixtures.htmlPage(j, "u1", "batch-control/grants/?scopeType=JOB&scopeFullName=fd8-job");
        assertFormBeforeLists("the prefilled form", prefilled);

        JenkinsRule.WebClient wc = UsabilityFixtures.clientNoJs(j, "u1");
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("scopeType", "JOB"));
        params.add(new NameValuePair("scopeFullName", "fd8-job"));
        params.add(new NameValuePair("actions", "CONFIGURE"));
        params.add(new NameValuePair("durationMinutes", "9999"));
        params.add(new NameValuePair("reason", "too long a window"));
        params.add(new NameValuePair("approvers", "a1"));
        WebRequest request = new WebRequest(wc.createCrumbedUrl("batch-control/grants/create"), HttpMethod.POST);
        request.setRequestParameters(params);
        Page answer = wc.getPage(request);
        assertTrue(answer instanceof HtmlPage, "fixture: the refusal must be an HTML page");
        HtmlPage refused = (HtmlPage) answer;
        assertFormBeforeLists("the refused submission", refused);

        String main = mainText(refused);
        int error = main.indexOf("240");
        int firstTable = firstTableOffset(refused, main);
        assertTrue(error >= 0, "fixture: the refusal must name the maximum of 240 minutes: " + excerpt(main));
        assertTrue(firstTable < 0 || error < firstTable, "the refusal's message must come before the lists, not below"
                + " them: message at " + error + ", first list at " + firstTable + ": " + excerpt(main));
    }

    private static void assertFormBeforeLists(String what, HtmlPage page) throws Exception {
        List<HtmlForm> forms = UsabilityFixtures.formsEndingWith(page, "batch-control/grants/create");
        assertFalse(forms.isEmpty(), what + ": the page must carry the grant request form; forms: "
                + UsabilityFixtures.formActions(page));
        String main = mainText(page);
        int firstTable = firstTableOffset(page, main);
        assertTrue(firstTable >= 0, "fixture: " + what + " must list the user's requests in a table: " + excerpt(main));
        String formText = forms.get(0).asNormalizedText();
        String head = formText.length() > 40 ? formText.substring(0, 40) : formText;
        int form = main.indexOf(head);
        assertTrue(form >= 0 && form < firstTable, what + ": the grant request form must come before the lists (form at "
                + form + ", first list at " + firstTable + ")");
    }

    private static String mainText(HtmlPage page) {
        DomElement main = page.getElementById("main-panel");
        return main == null ? page.asNormalizedText() : main.asNormalizedText();
    }

    /** Offset in {@code main} of the first table's text, or -1 if the main panel has no table. */
    private static int firstTableOffset(HtmlPage page, String main) {
        DomElement panel = page.getElementById("main-panel");
        DomElement scope = panel == null ? page.getDocumentElement() : panel;
        for (DomElement table : scope.getElementsByTagName("table")) {
            String text = table.asNormalizedText();
            if (text.isBlank()) {
                continue;
            }
            String head = text.length() > 40 ? text.substring(0, 40) : text;
            return main.indexOf(head);
        }
        return -1;
    }
}
