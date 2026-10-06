package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.htmlunit.Page;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 5 (D-72: "a run request keeps the submitted parameter values with their types, for
 * every parameter type ... and the approved build receives exactly those values") and item 8
 * (D-66, the Request Run dialog), for a parameter type whose value control is not its
 * {@code index.jelly}: DECISIONS D-74 (4) and issue #111 ("include parameter definitions through
 * descriptor.valuePage ... use descriptor.valuePage as core does"). Matrix row T-05-138
 * (spec-review-r6 m-11 (b), note 273).
 *
 * <p>The test parameter type {@link OriginParameterDefinition} extends core's
 * {@code StringParameterDefinition}, and its descriptor names {@code valuePage.jelly} as its value
 * page. That page differs from the string parameter's {@code index.jelly} in three observable
 * ways: its parameter block carries {@code data-r6-value-page}, its text box starts with
 * {@code value-page-default} (the definition's default is {@code definition-default}), and it posts
 * a hidden field {@code origin=value-page}, which the definition appends to the value
 * ({@code <typed>@value-page}; a block without the field gives {@code <typed>@index}). A form that
 * includes the definition with a hard-coded {@code index.jelly} falls back to the string
 * parameter's page and shows none of the three. Core's own build form, which includes
 * {@code descriptor.valuePage}, is the premise that the test type is wired correctly.
 *
 * <p>Users: {@code u1} requester (Item/Read, BatchControl/Request), {@code a1} approver,
 * {@code admin}.
 *
 * <p>Written from docs/SPEC.md items 5 and 8, docs/DECISIONS.md D-66, D-72 and D-74, issue #111
 * and core's public API only (no src/main knowledge).
 */
@WithJenkins
public class ParameterValuePageTest {

    private static final String MARKER = "data-r6-value-page";

    private JenkinsRule j;

    /** A string parameter whose value appends the {@code origin} field its value page posts. */
    public static final class OriginParameterDefinition extends StringParameterDefinition {
        private static final long serialVersionUID = 1L;

        public OriginParameterDefinition(String name) {
            super(name, "definition-default");
        }

        @Override
        public ParameterValue createValue(StaplerRequest2 req, JSONObject jo) {
            return new StringParameterValue(getName(), jo.optString("value", "") + "@" + jo.optString("origin", "index"));
        }
    }

    /** The descriptor of {@link OriginParameterDefinition}: its value page is {@code valuePage.jelly}, not {@code index.jelly}. */
    @TestExtension
    public static final class OriginDescriptor extends ParameterDefinition.ParameterDescriptor {
        public OriginDescriptor() {
            super(OriginParameterDefinition.class);
        }

        @Override
        public String getDisplayName() {
            return "valuePage probe parameter";
        }

        @Override
        public String getValuePage() {
            return getViewPage(clazz, "valuePage.jelly");
        }
    }

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-05-138 (D-72, D-66, D-74 (4), #111): approval-required Freestyle {@code value-page} with the
     * parameter ORIGIN of the test type. Premise: core's build form ({@code build?delay=0sec}, as
     * admin) renders ORIGIN through its value page (marker, {@code value-page-default}). u1's Request
     * Run page renders ORIGIN's block from the value page too (marker inside the form, text box
     * {@code value-page-default}); u1 types {@code typed-on-the-page} and submits: one request whose
     * ORIGIN is {@code typed-on-the-page@value-page}. The dialog fragment carries the marker as well,
     * and a submission through the dialog with {@code typed-in-the-dialog} stores
     * {@code typed-in-the-dialog@value-page}. Once a1 approves the page's request, build #1 receives
     * ORIGIN = {@code typed-on-the-page@value-page}.
     */
    @Test
    public void t_05_138_requestRunFormIncludesEachParameterThroughItsDescriptorsValuePage() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("value-page");
        job.addProperty(new ParametersDefinitionProperty(new OriginParameterDefinition("ORIGIN")));
        setBatchControl(job, new BatchControlJobProperty(true));

        // premise: core's own parameters form includes descriptor.valuePage, so the test type is wired
        Page core = UsabilityFixtures.get(j, UsabilityFixtures.clientNoJs(j, "admin"), job.getUrl() + "build?delay=0sec");
        assertTrue(core instanceof HtmlPage, "premise: core's build form is an HTML page, got " + core.getWebResponse().getContentType());
        HtmlForm coreForm = ((HtmlPage) core).getFormByName("parameters");
        assertFalse(coreForm.querySelectorAll("[" + MARKER + "]").isEmpty(), "premise: core's build form renders ORIGIN through its value page: "
                + UsabilityFixtures.excerpt(coreForm.asXml()));
        assertEquals("value-page-default", TypedParameterFixtures.valueOf(coreForm, "ORIGIN"), "premise: core shows the value page's text box");

        // the Request Run page
        Set<String> before = ApproverFormFixtures.runRequestIds();
        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm form = TypedParameterFixtures.requestRunForm(j, wc, job);
        assertFalse(form.querySelectorAll("[" + MARKER + "]").isEmpty(), "#111: the Request Run form must include ORIGIN through its"
                + " descriptor's value page (no " + MARKER + " block): " + UsabilityFixtures.excerpt(form.asXml()));
        DomElement block = TypedParameterFixtures.parameterBlock(form, "ORIGIN");
        assertTrue(block.hasAttribute(MARKER), "#111: ORIGIN's parameter block must be the value page's: " + UsabilityFixtures.excerpt(block.asXml()));
        assertEquals("value-page-default", TypedParameterFixtures.valueOf(form, "ORIGIN"),
                "#111: the Request Run form must show the value page's control, not the string parameter's index page");
        TypedParameterFixtures.setValue(form, "ORIGIN", "typed-on-the-page");
        Page answer = TypedParameterFixtures.submit(wc, form, "month-end batch with a value page", "a1");
        assertTrue(answer.getWebResponse().getStatusCode() < 400, "the Request Run submission must succeed, got HTTP "
                + answer.getWebResponse().getStatusCode() + ": " + UsabilityFixtures.excerpt(answer.getWebResponse().getContentAsString()));
        Set<String> created = ApproverFormFixtures.runRequestIds();
        created.removeAll(before);
        assertEquals(1, created.size(), "exactly one run request must be created, got " + created);
        String pageRequest = created.iterator().next();
        assertEquals("typed-on-the-page@value-page", RunRequestService.get().load(pageRequest).getParameters().get("ORIGIN"),
                "#111: the stored value must come from the value page's fields (origin=value-page)");

        // the dialog
        HtmlPage fragment = DialogFixtures.fragmentPage(j, "u1", job);
        HtmlForm fragmentForm = DialogFixtures.fragmentForm(fragment, job);
        assertFalse(fragmentForm.querySelectorAll("[" + MARKER + "]").isEmpty(), "#111: the dialog form must include ORIGIN through its"
                + " descriptor's value page: " + UsabilityFixtures.excerpt(fragmentForm.asXml()));
        String dialogRequest = DialogFixtures.submitThroughDialog(j, "u1", job,
                dialog -> TypedParameterFixtures.setValue(dialog, "ORIGIN", "typed-in-the-dialog"));
        assertEquals("typed-in-the-dialog@value-page", RunRequestService.get().load(dialogRequest).getParameters().get("ORIGIN"),
                "#111: the dialog's stored value must come from the value page's fields");

        // the approved build receives the value the value page posted
        DialogTypedParameterTest.approve(pageRequest);
        j.waitUntilNoActivity();
        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build, "the approved request must have run as #1");
        ParametersAction parameters = build.getAction(ParametersAction.class);
        assertNotNull(parameters, "build #1 carries its parameters");
        ParameterValue origin = parameters.getParameter("ORIGIN");
        assertNotNull(origin, "build #1 carries ORIGIN");
        assertEquals("typed-on-the-page@value-page", origin.getValue(), "the approved build receives exactly the submitted value");
        List<ParameterValue> all = parameters.getParameters();
        assertEquals(1, all.size(), "build #1 carries exactly the one parameter, got " + all);
    }
}
