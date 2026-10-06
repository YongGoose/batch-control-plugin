package io.jenkins.plugins.batchcontrol;

import hudson.model.FileParameterDefinition;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.RunParameterDefinition;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.runRequestIds;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.fallbackTarget;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.fromRerunValues;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.notices;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.openForm;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.postRerun;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 1, scenario L1-15: prefill caps and rerun-to-form details. Matrix rows T-GAP-157
 * .. T-GAP-162 (note 276).
 *
 * <p>Basis: LIMITATIONS 48 "A value is carried only if it is at most 2,000 characters long, and
 * the redirect's URL-encoded query ... is capped at 4,000 characters: values are added in the
 * order the parameters are defined, and one that would push the query over the cap is left out,
 * while a later, shorter one may still fit. The form always opens; a field whose value was not
 * carried starts at its default ... The same URL mechanism and caps apply when an incident rerun
 * continues on the Request Run form"; LIMITATIONS 16 (the rerun fallback form); SPEC 11 (D-72a) "A
 * request submitted from that prefilled form is linked to the incident only after the server
 * re-validates the incident reference"; SPEC 6 usability (refusals keep the input).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-60/D-72a and docs/LIMITATIONS.md only (no
 * src/main knowledge).
 */
@WithJenkins
public class RequestPrefillGapTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-GAP-157 (L1-15; LIMITATIONS 48): u1's refused direct build carries L (2,001 characters), A
     * (580 two-byte characters, about 3,500 characters once URL-encoded), B (900 characters) and C
     * (50 characters), defined in that order. The redirect to the Request Run form leaves L out (too
     * long), carries A, leaves B out (it would push the query over 4,000 characters) and carries C;
     * the query stays within 4,000 characters, and the form fills A and C and leaves L and B at
     * their defaults.
     */
    @Test
    public void t_gap_157_prefillHonoursTheValueAndQueryCaps() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("gap-prefill-caps");
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("L", "l-default"),
                new StringParameterDefinition("A", "a-default"), new StringParameterDefinition("B", "b-default"),
                new StringParameterDefinition("C", "c-default")));
        setBatchControl(job, new BatchControlJobProperty(true));
        String l = "l".repeat(2001);
        String a = "é".repeat(580);
        String b = "b".repeat(900);
        String c = "c".repeat(50);
        Set<String> ids = runRequestIds();
        URL target = refusedBuild(job, "{\"parameter\":[" + entry("L", l) + "," + entry("A", a) + "," + entry("B", b) + ","
                + entry("C", c) + "]}");
        assertTrue(target.getQuery().length() + 1 <= 4000, "the query with its '?' stays within 4,000 characters, was "
                + (target.getQuery().length() + 1));
        Map<String, String> query = TypedParameterFixtures.query(target);
        assertFalse(query.containsKey("p.L"), "a 2,001-character value is not carried");
        assertEquals(a, query.get("p.A"), "A fits and is carried; carried keys: " + query.keySet());
        assertFalse(query.containsKey("p.B"), "B would push the query over the cap and is left out");
        assertEquals(c, query.get("p.C"), "the later, shorter C still fits and is carried");

        HtmlForm form = openForm(UsabilityFixtures.client(j, "u1"), job, target);
        assertEquals("l-default", TypedParameterFixtures.valueOf(form, "L"), "L starts at its default");
        assertEquals(a, TypedParameterFixtures.valueOf(form, "A"));
        assertEquals("b-default", TypedParameterFixtures.valueOf(form, "B"), "B starts at its default");
        assertEquals(c, TypedParameterFixtures.valueOf(form, "C"));
        assertEquals(ids, runRequestIds(), "nothing is stored before the form is submitted");
    }

    /**
     * T-GAP-158 (L1-15; LIMITATIONS 16 and 48): an incident's job (stashed file, so the rerun
     * continues on the form) gains a parameter NEWP after the failed run; on the rerun's form NEWP
     * starts at its default, and the failed run's DATE is carried.
     */
    @Test
    public void t_gap_158_parameterAddedAfterTheFailureStartsAtItsDefault() throws Exception {
        WorkflowJob job = RerunFallbackFixtures.failedStashPipeline(j, "gap-rerun-newp", "gap-newp-marker");
        Incident incident = RerunFallbackFixtures.incidentFor("gap-rerun-newp#1");
        List<hudson.model.ParameterDefinition> defs = new ArrayList<>(job.getProperty(ParametersDefinitionProperty.class)
                .getParameterDefinitions());
        defs.add(new StringParameterDefinition("NEWP", "newp-default"));
        job.removeProperty(ParametersDefinitionProperty.class);
        job.addProperty(new ParametersDefinitionProperty(defs));

        URL target = fallbackTarget(j, incident, postRerun(j, "u1", incident));
        HtmlForm form = openForm(UsabilityFixtures.client(j, "u1"), job, target);
        assertEquals("newp-default", TypedParameterFixtures.valueOf(form, "NEWP"), "the new parameter starts at its default");
        assertEquals(RerunFallbackFixtures.DATE, TypedParameterFixtures.valueOf(form, "DATE"), "guard: the failed run's DATE is carried");
    }

    /**
     * T-GAP-159 (L1-15; SPEC 11 D-72a "an invalid reference is ignored"): the Request Run form opened
     * with {@code fromRerun=%E0%A4%A} (a malformed escape) answers 200 with the form and no rerun
     * notice.
     */
    @Test
    public void t_gap_159_malformedRerunReferenceRendersThePlainForm() throws Exception {
        FreeStyleProject job = RerunFallbackFixtures.approvalRequired(j, "gap-bad-ref");
        Page page = UsabilityFixtures.get(j, UsabilityFixtures.clientNoJs(j, "u1"), job.getUrl() + "batch-control/?fromRerun=%E0%A4%A");
        assertEquals(200, page.getWebResponse().getStatusCode(), "the form renders: " + excerpt(page.getWebResponse().getContentAsString()));
        assertTrue(page instanceof HtmlPage, "the answer is the form page");
        assertFalse(UsabilityFixtures.formsEndingWith((HtmlPage) page, job.getUrl() + "batch-control/submit").isEmpty(), "the form is there");
        assertTrue(notices((HtmlPage) page, "rerun").isEmpty(), "a malformed reference shows no incident notice");
    }

    /**
     * T-GAP-160 (L1-15; LIMITATIONS 48 "a run that no longer exists ... is dropped ... The form
     * always opens"): the Request Run form of a job whose Run parameter names a project with no
     * builds, and of one whose Run parameter names a project that no longer exists, answers 200
     * with the form.
     */
    @Test
    public void t_gap_160_runParameterWithoutBuildsOrProjectStillRenders() throws Exception {
        j.createFreeStyleProject("gap-nobuilds");
        FreeStyleProject empty = j.createFreeStyleProject("gap-runparam-empty");
        empty.addProperty(new ParametersDefinitionProperty(new RunParameterDefinition("R", "gap-nobuilds", "a run", null)));
        setBatchControl(empty, new BatchControlJobProperty(true));
        FreeStyleProject gone = j.createFreeStyleProject("gap-runparam-gone");
        gone.addProperty(new ParametersDefinitionProperty(new RunParameterDefinition("R", "gap-no-such-project", "a run", null)));
        setBatchControl(gone, new BatchControlJobProperty(true));
        for (FreeStyleProject job : new FreeStyleProject[] {empty, gone}) {
            Page page = UsabilityFixtures.get(j, UsabilityFixtures.clientNoJs(j, "u1"), job.getUrl() + "batch-control/");
            assertEquals(200, page.getWebResponse().getStatusCode(), job.getName() + ": the form renders: "
                    + excerpt(page.getWebResponse().getContentAsString()));
            assertFalse(UsabilityFixtures.formsEndingWith((HtmlPage) page, job.getUrl() + "batch-control/submit").isEmpty(),
                    job.getName() + ": the form is there");
        }
    }

    /**
     * T-GAP-161 (L1-15; SPEC 11 D-72a, SPEC 6 usability): the rerun's fallback form submitted with an
     * empty reason is refused and comes back with the incident notice and the hidden incident
     * reference kept; resubmitted with a reason it creates one request linked to the incident.
     */
    @Test
    public void t_gap_161_refusedRerunFormKeepsTheIncidentReference() throws Exception {
        WorkflowJob job = RerunFallbackFixtures.failedStashPipeline(j, "gap-rerun-keep", "gap-keep-marker");
        Incident incident = RerunFallbackFixtures.incidentFor("gap-rerun-keep#1");
        URL target = fallbackTarget(j, incident, postRerun(j, "u1", incident));
        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm form = openForm(wc, job, target);
        assertFalse(notices((HtmlPage) form.getPage(), "rerun").isEmpty(), "premise: the fallback form carries the rerun notice");
        TypedParameterFixtures.setFile(form, "DATA", TypedParameterFixtures.uploadFile("again.bin",
                TypedParameterFixtures.payload("gap-keep-again", 800)));
        Set<String> before = runRequestIds();
        Page refused = TypedParameterFixtures.submit(wc, form, "", "a1");
        assertTrue(refused instanceof HtmlPage, "the refusal is the form page");
        HtmlPage again = (HtmlPage) refused;
        assertEquals(before, runRequestIds(), "an empty reason stores nothing");
        assertFalse(notices(again, "rerun").isEmpty(), "the refusal keeps the incident notice: " + excerpt(again.asNormalizedText()));
        HtmlForm resubmit = UsabilityFixtures.formsEndingWith(again, job.getUrl() + "batch-control/submit").stream().findFirst().orElse(null);
        assertTrue(resubmit != null, "the refusal shows the form again");
        assertTrue(fromRerunValues(resubmit).contains(incident.getId()), "the hidden incident reference is kept: " + fromRerunValues(resubmit));

        TypedParameterFixtures.setFile(resubmit, "DATA", TypedParameterFixtures.uploadFile("again.bin",
                TypedParameterFixtures.payload("gap-keep-again", 800)));
        String id = RerunFallbackFixtures.submitOne(wc, resubmit, "the resubmission with a reason");
        assertEquals(incident.getId(), RunRequestService.get().load(id).getIncidentId(), "the request is linked to the incident");
        assertTrue(RerunFallbackFixtures.rerunIds(incident.getId()).contains(id), "the incident lists the request");
    }

    /**
     * T-GAP-162 (L1-15; LIMITATIONS 48, D-60): the form a refused direct build leads to on a job
     * with a file parameter carries the {@code prefilled} notice (premise); once that form is
     * submitted and refused (empty reason), the re-rendered form no longer shows it.
     */
    @Test
    public void t_gap_162_refusedFormSubmissionDropsTheCarryOverNote() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("gap-prefill-note");
        job.addProperty(new ParametersDefinitionProperty(new FileParameterDefinition("UPLOAD", "core file"),
                new StringParameterDefinition("DATE", "2000-01-01")));
        setBatchControl(job, new BatchControlJobProperty(true));
        URL target = refusedBuild(job, "{\"parameter\":[" + entry("DATE", "2026-10-03") + "]}");
        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm form = openForm(wc, job, target);
        assertFalse(notices((HtmlPage) form.getPage(), "prefilled").isEmpty(), "premise: the carried-over form shows the prefilled note");
        Page refused = TypedParameterFixtures.submit(wc, form, "", "a1");
        assertTrue(refused instanceof HtmlPage, "the refusal is the form page");
        assertFalse(UsabilityFixtures.formsEndingWith((HtmlPage) refused, job.getUrl() + "batch-control/submit").isEmpty(),
                "the refusal shows the form again");
        assertTrue(notices((HtmlPage) refused, "prefilled").isEmpty(), "the re-rendered form must not say the values came from a refused build: "
                + excerpt(((HtmlPage) refused).asNormalizedText()));
    }

    // ------------------------------------------------------------------ helpers

    private static String entry(String name, String value) {
        return "{\"name\":\"" + name + "\",\"value\":" + net.sf.json.util.JSONUtils.quote(value) + "}";
    }

    /** u1's direct build of {@code job} with core's structured {@code json}; returns the 303 target (asserted). */
    private URL refusedBuild(FreeStyleProject job, String json) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("json", json));
        params.add(new NameValuePair("Submit", "Build"));
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "u1");
        org.htmlunit.WebRequest request = new org.htmlunit.WebRequest(wc.createCrumbedUrl(job.getUrl() + "build?delay=0sec"),
                org.htmlunit.HttpMethod.POST);
        request.setCharset(java.nio.charset.StandardCharsets.UTF_8);
        request.setRequestParameters(params);
        WebResponse answer = wc.getPage(request).getWebResponse();
        assertEquals(303, answer.getStatusCode(), "fixture: the requester's refused build redirects: " + excerpt(answer.getContentAsString()));
        URL target = new URL(answer.getWebRequest().getUrl(), answer.getResponseHeaderValue("Location"));
        assertEquals(new URL(j.getURL(), job.getUrl() + "batch-control/").getPath(), target.getPath(), "fixture: the redirect leads to the form");
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "fixture: nothing ran");
        return target;
    }
}
