package io.jenkins.plugins.batchcontrol;

import hudson.model.ChoiceParameterDefinition;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.User;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.file_parameters.Base64FileParameterDefinition;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.model.experimentalflags.UserExperimentalFlagsProperty;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6, D-60: "when a person's build submission with parameters (the build form, the
 * parameters dialog of the new job page, {@code /job/X/buildWithParameters} from a browser) is
 * refused on an approval-required job, the refusal leads to the Request Run form of that job with
 * the submitted parameter values filled in (sensitive values are never carried; file values are not
 * carried either, issue #115); nothing is queued and nothing is stored until the requester submits
 * the form. Opened from the new job page, the refusal does not fall back to the classic build
 * form." Coverage inventory G-M3, G-M4 and G-M5 (JenkinsRule part); matrix rows T-06-100 ..
 * T-06-102 (note 269). The string/choice/password carry-over itself is T-06-89 .. T-06-97.
 *
 * <p>The frozen contract of those rows applies: the refusal answers {@code 303} to
 * {@code <job>/batch-control/} carrying values as {@code p.<NAME>=<value>}; the Request Run form
 * marks a carry-over that dropped file values with a {@code data-batch-control-notice="prefilled"}
 * notice naming them (T-UI-116).
 *
 * <p>Users: {@code u1} requester (Item/Read, Item/Build, BatchControl/Request), {@code nobc}
 * (Item/Read, Item/Build, no Batch Control permission), {@code a1} approver.
 *
 * <p>Written from docs/SPEC.md item 6, docs/DECISIONS.md D-60 and D-70 and docs/TEST-MATRIX.md
 * only (no src/main knowledge).
 */
@WithJenkins
public class RefusedBuildCarryOverTest {

    private static final Pattern SELECT_AGAIN = Pattern.compile("(?i)select the files? again");
    private static final String FLAG = "new-job-page.flag";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, Item.BUILD).everywhere().to("nobc")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(List.of("a1"));
        cfg.save();
    }

    /**
     * T-06-100 (G-M3): u1 submits core's parameters form with a small file for the Base64 file B64
     * and a typed DATE. The 303 leads to the job's Request Run form; its query carries
     * {@code p.DATE} and nothing for B64: no {@code p.B64}, neither the Base64 text nor the file's
     * content in the Location. Nothing is queued or stored. The prefilled form carries the
     * {@code prefilled} notice naming B64 and saying to select the file again; DATE is filled in.
     */
    @Test
    public void t_06_100_d60NeverCarriesABase64FileAndNamesIt() throws Exception {
        FreeStyleProject job = d60Job("b64-d60", new Base64FileParameterDefinition("B64"),
                new StringParameterDefinition("DATE", "2000-01-01"));
        byte[] content = payload("b64-d60-marker-Gm3", 600);
        String base64 = Base64.getEncoder().encodeToString(content);
        Set<String> before = ApproverFormFixtures.runRequestIds();

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("u1");
        HtmlForm form = coreParametersForm(wc, job);
        TypedParameterFixtures.setValue(form, "DATE", "2026-10-06");
        TypedParameterFixtures.setFile(form, "B64", uploadFile("small.bin", content));
        wc.getOptions().setRedirectEnabled(false);
        Page answer = j.submit(form);

        URL target = assertRedirectToRequestForm(answer, job);
        String location = answer.getWebResponse().getResponseHeaderValue("Location");
        Map<String, String> query = TypedParameterFixtures.query(target);
        assertEquals("2026-10-06", query.get("p.DATE"), "the string value must be carried: " + location);
        assertTrue(query.keySet().stream().noneMatch(k -> k.startsWith("p.B64")), "no value may be carried for the file parameter: " + location);
        assertFalse(location.contains(base64.substring(0, 24)), "the Base64 text must not be carried in the Location: " + location);
        assertFalse(location.contains(URLEncoder.encode(base64.substring(0, 24), StandardCharsets.UTF_8)),
                "the Base64 text must not be carried in the Location, not even encoded: " + location);
        assertFalse(location.contains("b64-d60-marker-Gm3"), "the file's content must not be carried: " + location);
        assertNothingQueuedOrStored(job, before);

        HtmlPage carried = (HtmlPage) UsabilityFixtures.clientNoJs(j, "u1").getPage(target);
        assertEquals(200, carried.getWebResponse().getStatusCode(), "the prefilled Request Run form must open");
        String notice = RerunFallbackFixtures.noticeText(carried, "prefilled");
        assertTrue(notice.contains("B64"), "the prefilled notice must name the Base64 file parameter: " + notice);
        assertTrue(SELECT_AGAIN.matcher(notice).find(), "the notice must say to select the file again: " + notice);
        HtmlForm request = UsabilityFixtures.formsEndingWith(carried, job.getUrl() + "batch-control/submit").get(0);
        assertEquals("2026-10-06", TypedParameterFixtures.valueOf(request, "DATE"), "guard: DATE is filled in");
        assertFalse(carried.getWebResponse().getContentAsString().contains(base64.substring(0, 24)),
                "the prefilled form must not hold the Base64 text");
    }

    /**
     * T-06-101 (G-M4): u1, signed in with a browser session, POSTs
     * {@code job/<name>/buildWithParameters?P=from-browser&C=second} with a crumb. The answer is a
     * 303 to the job's Request Run form carrying {@code p.P} and {@code p.C}; following it shows
     * both filled in; nothing is queued or stored. Guard: the same POST by nobc (no Batch Control
     * permission) does not lead to the Request Run form and stores and queues nothing either.
     */
    @Test
    public void t_06_101_buildWithParametersFromABrowserLeadsToThePrefilledForm() throws Exception {
        FreeStyleProject job = d60Job("bwp-x", new StringParameterDefinition("P", "p-default"),
                new ChoiceParameterDefinition("C", new String[] {"first", "second", "third"}, "a choice"));
        Set<String> before = ApproverFormFixtures.runRequestIds();

        WebResponse answer = postBuildWithParameters("u1", job, "P=from-browser&C=second");
        assertEquals(303, answer.getStatusCode(), "a requester's refused buildWithParameters must answer 303: "
                + UsabilityFixtures.excerpt(answer.getContentAsString()));
        String location = answer.getResponseHeaderValue("Location");
        assertNotNull(location, "the 303 must carry a Location");
        URL target = new URL(answer.getWebRequest().getUrl(), location);
        assertEquals(new URL(j.getURL(), job.getUrl() + "batch-control/").getPath(), target.getPath(),
                "the refusal must lead to the job's Request Run form");
        Map<String, String> query = TypedParameterFixtures.query(target);
        assertEquals("from-browser", query.get("p.P"), "the string value must be carried: " + location);
        assertEquals("second", query.get("p.C"), "the choice value must be carried: " + location);
        assertNothingQueuedOrStored(job, before);

        HtmlPage carried = (HtmlPage) UsabilityFixtures.clientNoJs(j, "u1").getPage(target);
        HtmlForm request = UsabilityFixtures.formsEndingWith(carried, job.getUrl() + "batch-control/submit").get(0);
        assertEquals("from-browser", TypedParameterFixtures.valueOf(request, "P"));
        assertEquals("second", TypedParameterFixtures.valueOf(request, "C"));

        WebResponse refused = postBuildWithParameters("nobc", job, "P=from-nobc&C=third");
        String refusedLocation = refused.getResponseHeaderValue("Location");
        assertFalse(refusedLocation != null && refusedLocation.contains("batch-control"),
                "guard: a user who may not request must not be led to the Request Run form: HTTP " + refused.getStatusCode()
                        + " Location " + refusedLocation);
        assertTrue(refused.getStatusCode() >= 400, "guard: nobc's buildWithParameters must be refused, got HTTP " + refused.getStatusCode());
        assertNothingQueuedOrStored(job, before);
    }

    /**
     * T-06-102 (G-M5, JenkinsRule part): u1 has core's new job page switched on. u1 submits the
     * job's parameters form (the form the new job page's parameters dialog posts). The answer is a
     * 303 to the job's Request Run form with P carried; followed, it is the Request Run form with P
     * filled in and not core's classic {@code parameters} form; nothing is queued or stored.
     */
    @Test
    public void t_06_102_newJobPageSubmissionLeadsToThePrefilledRequestForm() throws Exception {
        FreeStyleProject job = d60Job("newpage-d60", new StringParameterDefinition("P", "p-default"));
        User.getById("u1", true).addProperty(new UserExperimentalFlagsProperty(Map.of(FLAG, "true")));
        Set<String> before = ApproverFormFixtures.runRequestIds();

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("u1");
        HtmlForm form = coreParametersForm(wc, job);
        TypedParameterFixtures.setValue(form, "P", "from-the-new-page");
        wc.getOptions().setRedirectEnabled(false);
        Page answer = j.submit(form);

        URL target = assertRedirectToRequestForm(answer, job);
        assertEquals("from-the-new-page", TypedParameterFixtures.query(target).get("p.P"), "the value must be carried");
        assertNothingQueuedOrStored(job, before);
        HtmlPage carried = (HtmlPage) UsabilityFixtures.clientNoJs(j, "u1").getPage(target);
        assertEquals(200, carried.getWebResponse().getStatusCode(), "the prefilled Request Run form must open");
        assertTrue(carried.getForms().stream().noneMatch(f -> "parameters".equals(f.getNameAttribute())),
                "the refusal must not fall back to core's classic build form");
        HtmlForm request = UsabilityFixtures.formsEndingWith(carried, job.getUrl() + "batch-control/submit").get(0);
        assertEquals("from-the-new-page", TypedParameterFixtures.valueOf(request, "P"), "P must be filled in");
    }

    // ---------------------------------------------------------------- helpers

    /** As RefusedBuildPrefillTest's job: approval-required, activated, timer and upstream doors open. */
    private FreeStyleProject d60Job(String name, hudson.model.ParameterDefinition... definitions) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(job, property);
        BatchControlFixtures.activate(job);
        job.addProperty(new ParametersDefinitionProperty(definitions));
        return job;
    }

    private HtmlForm coreParametersForm(JenkinsRule.WebClient wc, FreeStyleProject job) throws Exception {
        Page page = wc.getPage(new WebRequest(new URL(j.getURL(), job.getUrl() + "build?delay=0sec"), HttpMethod.GET));
        assertTrue(page instanceof HtmlPage, "fixture: core's parameters form must be HTML");
        return ((HtmlPage) page).getFormByName("parameters");
    }

    private WebResponse postBuildWithParameters(String userId, FreeStyleProject job, String query) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, userId);
        URL url = new URL(wc.createCrumbedUrl(job.getUrl() + "buildWithParameters").toExternalForm() + "&" + query);
        return wc.getPage(new WebRequest(url, HttpMethod.POST)).getWebResponse();
    }

    private URL assertRedirectToRequestForm(Page answer, FreeStyleProject job) throws Exception {
        assertEquals(303, answer.getWebResponse().getStatusCode(), "a requester's refused build must answer 303 (D-60): "
                + UsabilityFixtures.excerpt(answer.getWebResponse().getContentAsString()));
        String location = answer.getWebResponse().getResponseHeaderValue("Location");
        assertNotNull(location, "the 303 must carry a Location");
        URL target = new URL(answer.getUrl(), location);
        assertEquals(new URL(j.getURL(), job.getUrl() + "batch-control/").getPath(), target.getPath(),
                "the refusal must lead to the job's Request Run form");
        return target;
    }

    private void assertNothingQueuedOrStored(FreeStyleProject job, Set<String> before) throws Exception {
        j.waitUntilNoActivity();
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "no run request may be stored before the form is submitted");
        assertTrue(j.jenkins.getQueue().isEmpty(), "the queue must be empty");
        assertEquals(1, job.getNextBuildNumber(), "no build number may have been consumed");
        assertTrue(job.getBuilds().isEmpty(), "no build may exist");
    }
}
