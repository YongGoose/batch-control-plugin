package io.jenkins.plugins.batchcontrol;

import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Batch Control global configuration, seen by the people who maintain it. Matrix rows
 * T-CFG-04 (e2e-03 DEF-08, note 123) and T-02-44 (DEF-10, note 124).
 *
 * <p>Written from docs/SPEC.md (section 5, section 6 usability line, items 2, 7, 8, 11),
 * docs/reports/e2e-03.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ConfigUsabilityTest {

    private static final String DESCRIPTOR =
            "descriptorByName/io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration/";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, BatchControlPermissions.MANAGE).everywhere().to("manager")
                .grant(Jenkins.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovers(Arrays.asList("admin"));
        cfg.save();
    }

    /**
     * T-CFG-04 (DEF-08): invalid values in the number, duration-option and incident-result fields
     * get a message next to the field (the field's form validation answers an error; a valid
     * value answers none), and a submitted form carrying them is not saved as a success: nothing
     * invalid is stored and the previous values are kept.
     */
    @Test
    public void t_cfg_04_invalidGlobalSettingsAreRefusedWithAFieldMessage() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        assertEquals(240, cfg.getMaxGrantMinutes(), "fixture: maxGrantMinutes default");
        assertEquals(72, cfg.getPendingTimeoutHours(), "fixture: pendingTimeoutHours default");
        assertEquals(Arrays.asList(15, 30, 60), cfg.getGrantDurationOptions(), "fixture: grantDurationOptions default");
        assertEquals(Arrays.asList("FAILURE", "UNSTABLE"), cfg.getIncidentResults(), "fixture: incidentResults default");

        // the message next to the field: each field's check refuses the invalid value and accepts a valid one
        String[][] checks = {
            {"MaxGrantMinutes", "0", "120"},
            {"MaxGrantMinutes", "-1", "240"},
            {"PendingTimeoutHours", "0", "24"},
            {"ApprovedRunTimeoutMinutes", "-1", "60"},
            {"RetentionMonths", "0", "24"},
            {"GrantDurationOptions", "15,abc", "15, 30"},
            {"IncidentResults", "FAILURE, BOGUS", "FAILURE, ABORTED"},
        };
        for (String[] check : checks) {
            String invalid = validate(check[0], check[1]);
            assertTrue(isError(invalid), "check" + check[0] + " must show an error next to the field for '" + check[1]
                    + "', answered: " + excerpt(invalid));
            String valid = validate(check[0], check[2]);
            assertFalse(isError(valid), "check" + check[0] + " must accept the valid value '" + check[2] + "' (negative twin), answered: "
                    + excerpt(valid));
        }

        // the submitted form: invalid values are not saved as a success
        JenkinsRule.WebClient wc = UsabilityFixtures.client(j, "admin");
        HtmlForm form = wc.goTo("configure").getFormByName("config");
        UsabilityFixtures.setField(form, "maxGrantMinutes", "0");
        UsabilityFixtures.setField(form, "grantDurationOptions", "15,abc");
        UsabilityFixtures.setField(form, "incidentResults", "FAILURE, BOGUS");
        Page result = j.submit(form);
        int code = result.getWebResponse().getStatusCode();
        String resultUrl = result.getUrl().toExternalForm();
        // a successful save of the global form redirects to the Manage Jenkins page
        assertTrue(code >= 400 || !UsabilityFixtures.stripQueryAndSlash(resultUrl).endsWith("/manage"),"a form with invalid values must not be answered as a successful save (HTTP "
                + code + ", landed on " + resultUrl + ")");
        UsabilityFixtures.assertPlainRefusal("the refused global save", UsabilityFixtures.text(result), null);

        BatchControlGlobalConfiguration after = BatchControlGlobalConfiguration.get();
        assertEquals(240, after.getMaxGrantMinutes(), "maxGrantMinutes 0 must not be stored and the previous value must stay");
        assertEquals(Arrays.asList(15, 30, 60), after.getGrantDurationOptions(), "'15,abc' must not be stored (not even as [15])");
        assertFalse(after.getIncidentResults().contains("BOGUS"), "BOGUS is not a build result and must not be stored as an incident result: "
                + after.getIncidentResults());
        assertEquals(Arrays.asList("FAILURE", "UNSTABLE"), after.getIncidentResults(), "the previous incident results must stay");
    }

    /**
     * T-02-44 (DEF-10): the BatchControl/Manage permission is enough to open and save the Batch
     * Control configuration (SPEC 6 usability line, SPEC 2). A holder of Overall/Read +
     * BatchControl/Manage finds the configuration from the Batch Control screen, it opens (no 403)
     * and a changed value is saved. Negative: a holder of ViewHistory only is offered no such link
     * and the same URL refuses them without changing anything.
     */
    @Test
    public void t_02_44_manageHolderOpensAndSavesTheBatchControlConfiguration() throws Exception {
        HtmlPage root = UsabilityFixtures.htmlPage(j, "manager", "batch-control/");
        assertEquals(200, root.getWebResponse().getStatusCode(), "fixture: a Manage holder reaches the Batch Control screen");
        HtmlAnchor link = configurationLink(root);
        assertNotNull(link, "the Batch Control screen must offer a Manage holder a link to the Batch Control configuration"
                + " (caption naming configuration or settings); anchors were " + UsabilityFixtures.resolvedHrefs(root));
        String target = root.getFullyQualifiedUrl(link.getHrefAttribute()).toExternalForm();
        String relative = target.substring(j.getURL().toExternalForm().length());

        JenkinsRule.WebClient wc = UsabilityFixtures.client(j, "manager");
        Page opened = wc.getPage(new WebRequest(new java.net.URL(target), HttpMethod.GET));
        assertEquals(200, opened.getWebResponse().getStatusCode(), "the configuration must open for a BatchControl/Manage holder, got HTTP "
                + opened.getWebResponse().getStatusCode() + " at " + target + ": " + excerpt(UsabilityFixtures.text(opened)));
        assertTrue(opened instanceof HtmlPage, "the configuration must be an HTML page");
        HtmlForm form = null;
        for (HtmlForm candidate : ((HtmlPage) opened).getForms()) {
            if (UsabilityFixtures.hasField(candidate, "pendingTimeoutHours")) {
                form = candidate;
            }
        }
        assertNotNull(form, "the page must carry the Batch Control settings form (field pendingTimeoutHours); forms: "
                + UsabilityFixtures.formActions((HtmlPage) opened));
        UsabilityFixtures.setField(form, "pendingTimeoutHours", "48");
        Page saved = j.submit(form);
        assertTrue(saved.getWebResponse().getStatusCode() < 400, "the Manage holder's save must succeed, got HTTP "
                + saved.getWebResponse().getStatusCode() + ": " + excerpt(UsabilityFixtures.text(saved)));
        assertEquals(48, BatchControlGlobalConfiguration.get().getPendingTimeoutHours(), "the Manage holder's change must be saved");

        // negative: ViewHistory alone offers no link and cannot open or change the configuration
        HtmlPage viewerRoot = UsabilityFixtures.htmlPage(j, "viewer", "batch-control/");
        assertEquals(null, configurationLink(viewerRoot), "a user without BatchControl/Manage must not be offered the configuration link");
        Page refused = UsabilityFixtures.get(j, UsabilityFixtures.clientNoJs(j, "viewer"), relative);
        int code = refused.getWebResponse().getStatusCode();
        assertTrue(code == 403 || code == 404, "the configuration must refuse a user without BatchControl/Manage, got HTTP " + code);
        assertEquals(48, BatchControlGlobalConfiguration.get().getPendingTimeoutHours(), "nothing may have changed");
    }

    // ---------------------------------------------------------------- helpers

    private static final Pattern CONFIG_CAPTION = Pattern.compile("(?i)(configur|settings)");

    private HtmlAnchor configurationLink(HtmlPage page) throws Exception {
        String root = j.getURL().toExternalForm();
        for (HtmlAnchor a : UsabilityFixtures.anchorsCaptioned(page, CONFIG_CAPTION)) {
            String href = a.getHrefAttribute();
            if (href == null || href.isEmpty() || href.startsWith("#")) {
                continue;
            }
            String resolved = page.getFullyQualifiedUrl(href).toExternalForm();
            if (resolved.startsWith(root) && !resolved.contains("/job/")) {
                return a;
            }
        }
        return null;
    }

    /** POSTs the field's form validation (as the configure page does, with the crumb) and returns the answer. */
    private String validate(String field, String value) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("value", value));
        org.htmlunit.WebResponse response = ApproverFormFixtures.post(j, "admin", DESCRIPTOR + "check" + field, params);
        assertEquals(200, response.getStatusCode(), "check" + field + " must be served for the configure form (a message next to the field),"
                + " got HTTP " + response.getStatusCode());
        return response.getContentAsString();
    }

    private static boolean isError(String formValidationBody) {
        return formValidationBody.contains("class=\"error\"") || formValidationBody.contains("class=error")
                || formValidationBody.contains("class='error'");
    }
}
