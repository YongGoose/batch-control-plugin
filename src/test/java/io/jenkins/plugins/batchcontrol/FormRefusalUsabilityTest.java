package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTextArea;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matrix row T-05-18 (e2e-03 DEF-09, note 125): a refused run request, rejection or grant request
 * is answered on the form itself, with a message in plain words and the user's input kept, not by
 * core's bare "Error" page that loses everything typed (SPEC 6 usability line; SPEC 5 reason and
 * rejection comment; SPEC 8 maxGrantMinutes).
 *
 * <p>Written from docs/SPEC.md, docs/reports/e2e-03.md and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class FormRefusalUsabilityTest {

    private static final String TYPED_PARAM = "typed-value-7731";
    private static final String TYPED_REASON = "keep-this-reason-4417";

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.REQUEST_GRANT).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.setMaxGrantMinutes(20);
        cfg.save();

        job = j.createFreeStyleProject("form-x");
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("P", "default-p")));
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * T-05-18 (DEF-09): (a) the run request form submitted without a reason comes back as the
     * form, naming the reason, with the typed parameter value still in it; (b) a rejection without
     * a comment comes back to the request with a message naming the comment, and the request stays
     * PENDING; (c) a grant request longer than maxGrantMinutes comes back as the form with the
     * typed reason kept and the limit in plain words (no internal key). None of them is core's
     * bare "Error" page; nothing is stored.
     */
    @Test
    public void t_05_18_refusedFormsKeepTheInputAndExplainTheField() throws Exception {
        // (a) run request without a reason, through the real form
        JenkinsRule.WebClient wc = UsabilityFixtures.client(j, "u1");
        HtmlPage formPage = (HtmlPage) wc.getPage(new URL(j.getURL(), job.getUrl() + "batch-control/"));
        assertEquals(200, formPage.getWebResponse().getStatusCode(), "fixture: the request form must open for u1");
        HtmlForm form = UsabilityFixtures.formsEndingWith(formPage, job.getUrl() + "batch-control/submit").stream()
                .findFirst().orElse(null);
        assertNotNull(form, "fixture: the request screen must carry the run request form; forms: "
                + UsabilityFixtures.formActions(formPage));
        HtmlInput param = parameterInput(form, "default-p");
        param.setValue(TYPED_PARAM);
        UsabilityFixtures.setField(form, "reason", "");
        UsabilityFixtures.selectApprover(form, "a1");
        Set<String> before = ApproverFormFixtures.runRequestIds();
        Page answer = j.submit(form);

        assertEquals(before, ApproverFormFixtures.runRequestIds(), "a request without a reason must not be stored (SPEC 5)");
        assertTrue(answer instanceof HtmlPage, "the refusal must be an HTML page");
        HtmlPage refused = (HtmlPage) answer;
        UsabilityFixtures.assertNotBareErrorPage("run request without a reason", refused);
        UsabilityFixtures.assertPlainRefusal("run request without a reason", refused.asNormalizedText(), Pattern.compile("(?i)reason"));
        assertFalse(UsabilityFixtures.formsEndingWith(refused, job.getUrl() + "batch-control/submit").isEmpty(),
                "the refusal must show the request form again, so the user can correct it; forms: " + UsabilityFixtures.formActions(refused));
        assertTrue(UsabilityFixtures.pageKeepsValue(refused, TYPED_PARAM), "the parameter value the user typed must be kept in the form: "
                + excerpt(refused.asNormalizedText()));

        // (b) rejection without a comment, through the request screen's reject form
        String id = ApproverFormFixtures.submitRunOk(j, "u1", job, "month-end close", "a1");
        JenkinsRule.WebClient approver = UsabilityFixtures.client(j, "a1");
        HtmlPage detail = (HtmlPage) approver.getPage(new URL(j.getURL(), "batch-control/requests/" + id + "/"));
        HtmlForm reject = UsabilityFixtures.formsEndingWith(detail, "batch-control/requests/" + id + "/reject").stream()
                .findFirst().orElse(null);
        assertNotNull(reject, "fixture: the request screen must offer the designated approver a reject form; forms: "
                + UsabilityFixtures.formActions(detail));
        clearTextFields(reject);
        Page rejected = j.submit(reject);
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus(), "a rejection without a comment must be refused (SPEC 5)");
        assertTrue(rejected instanceof HtmlPage, "the refusal must be an HTML page");
        UsabilityFixtures.assertNotBareErrorPage("rejection without a comment", (HtmlPage) rejected);
        UsabilityFixtures.assertPlainRefusal("rejection without a comment", ((HtmlPage) rejected).asNormalizedText(),
                Pattern.compile("(?i)comment"));
        assertFalse(UsabilityFixtures.formsEndingWith((HtmlPage) rejected, "batch-control/requests/" + id + "/reject").isEmpty(),
                "the refusal must show the reject form again; forms: " + UsabilityFixtures.formActions((HtmlPage) rejected));

        // (c) grant request over maxGrantMinutes (20)
        Set<String> grantsBefore = ApproverFormFixtures.grantRequestIds();
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("scopeType", "JOB"));
        params.add(new NameValuePair("scopeFullName", "form-x"));
        params.add(new NameValuePair("actions", "CONFIGURE"));
        params.add(new NameValuePair("durationMinutes", "45"));
        params.add(new NameValuePair("reason", TYPED_REASON));
        params.add(new NameValuePair("approvers", "a1"));
        WebRequest request = new WebRequest(wc.createCrumbedUrl("batch-control/grants/create"), HttpMethod.POST);
        request.setRequestParameters(params);
        Page grant = wc.getPage(request);
        assertEquals(grantsBefore, ApproverFormFixtures.grantRequestIds(), "a grant longer than maxGrantMinutes must not be stored (SPEC 8)");
        assertTrue(grant instanceof HtmlPage, "the refusal must be an HTML page, got " + grant.getWebResponse().getContentType());
        HtmlPage grantPage = (HtmlPage) grant;
        String grantText = grantPage.asNormalizedText();
        UsabilityFixtures.assertNotBareErrorPage("grant over the maximum", grantPage);
        UsabilityFixtures.assertPlainRefusal("grant over the maximum", grantText, Pattern.compile("20"));
        assertFalse(grantText.contains("maxGrantMinutes"), "the message must not name the internal key maxGrantMinutes: " + excerpt(grantText));
        assertFalse(UsabilityFixtures.formsEndingWith(grantPage, "batch-control/grants/create").isEmpty(),
                "the refusal must show the grant request form again; forms: " + UsabilityFixtures.formActions(grantPage));
        assertTrue(UsabilityFixtures.pageKeepsValue(grantPage, TYPED_REASON), "the reason the user typed must be kept in the form: "
                + excerpt(grantText));
    }

    /** The text control carrying the parameter's default value (the form pre-fills defaults). */
    private static HtmlInput parameterInput(HtmlForm form, String defaultValue) {
        for (DomElement element : form.getElementsByTagName("input")) {
            if (element instanceof HtmlInput && defaultValue.equals(((HtmlInput) element).getValue())
                    && !"hidden".equalsIgnoreCase(((HtmlInput) element).getTypeAttribute())) {
                return (HtmlInput) element;
            }
        }
        throw new AssertionError("fixture: the request form must offer the parameter P pre-filled with its default; form was: "
                + excerpt(form.asXml()));
    }

    private static void clearTextFields(HtmlForm form) {
        for (DomElement element : form.getElementsByTagName("textarea")) {
            if (element instanceof HtmlTextArea) {
                ((HtmlTextArea) element).setText("");
            }
        }
        for (DomElement element : form.getElementsByTagName("input")) {
            if (element instanceof HtmlInput && "text".equalsIgnoreCase(((HtmlInput) element).getTypeAttribute())) {
                ((HtmlInput) element).setValue("");
            }
        }
    }
}
