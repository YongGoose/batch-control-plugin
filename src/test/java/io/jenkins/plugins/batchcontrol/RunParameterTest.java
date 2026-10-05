package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.RunParameterDefinition;
import hudson.model.RunParameterValue;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.readable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC items 5, 6 and 11 for core's run parameter ({@code RunParameterDefinition}): item 5 (D-72,
 * "for every parameter type ... the approved build receives exactly those values"; the value is
 * shown from the masked map, and it is neither sensitive nor a file), item 6 (D-60, "the refusal
 * leads to the Request Run form of that job with the submitted parameter values filled in
 * (sensitive values are never carried; file values are not carried either)") and item 11 ("Request
 * rerun ... carries the failed run's own parameter values"). Coverage inventory G-M2; matrix rows
 * T-05-107, T-06-103 and T-11-25 (note 269).
 *
 * <p>The referenced job {@code src} has two successful builds; every row chooses {@code src#1},
 * which is not the definition's default (the newest build), so a value that silently fell back to
 * the default is caught. The display text of a run value is not pinned by SPEC; the rows require
 * it to name the job and the build number ({@code src #1} or {@code src#1}).
 *
 * <p>Users: {@code u1} requester (Item/Read, Item/Build, BatchControl/Request, ViewHistory for the
 * rerun), {@code a1} approver, {@code viewer} (ViewHistory), {@code admin}.
 *
 * <p>Written from docs/SPEC.md items 5, 6 and 11, docs/DECISIONS.md D-60 and D-72 and core's public
 * API only (no src/main knowledge).
 */
@WithJenkins
public class RunParameterTest {

    private static final Pattern SHOWN = Pattern.compile("src ?#1");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(List.of("a1"));
        cfg.save();
        TypedParameterFixtures.CaptureEnv.SEEN.clear();

        FreeStyleProject src = uncontrolled(j.createFreeStyleProject("src"));
        BatchControlFixtures.activateAsAdmin(src); // D-46: a cause-less fixture build needs an activation
        j.buildAndAssertSuccess(src);
        j.buildAndAssertSuccess(src);
        assertEquals(3, src.getNextBuildNumber(), "fixture: src has the builds #1 and #2");
    }

    /**
     * T-05-107 (G-M2): u1 picks {@code src#1} for SRC on the Request Run page. The stored display
     * names {@code src #1}; once a1 approves, the build receives a run value whose run id is
     * {@code src#1} (its SRC_JOBNAME is {@code src}, SRC_NUMBER {@code 1}); {@code requests.csv},
     * {@code runs.csv} and the dashboard show the run value. Guard: the plain value P travels too.
     */
    @Test
    public void t_05_107_runParameterFromTheRequestPageReachesTheBuildAndIsShown() throws Exception {
        FreeStyleProject job = runJob("run-page", false);
        Set<String> before = ApproverFormFixtures.runRequestIds();
        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm form = TypedParameterFixtures.requestRunForm(j, wc, job);
        chooseRun(form, "src#1");
        TypedParameterFixtures.setValue(form, "P", "plain-run-value");
        Page answer = TypedParameterFixtures.submit(wc, form, "rerun the report of src #1", "a1");
        assertTrue(answer.getWebResponse().getStatusCode() < 400, "the submission must succeed, got HTTP " + answer.getWebResponse().getStatusCode());
        Set<String> after = ApproverFormFixtures.runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "exactly one run request must be created, got " + after);
        String id = after.iterator().next();
        String shown = RunRequestService.get().load(id).getParameters().get("SRC");
        assertTrue(shown != null && SHOWN.matcher(shown).matches(), "the stored display of the run value must name src #1, was " + shown);

        DialogTypedParameterTest.approve(id);
        j.waitUntilNoActivity();
        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build, "the approved request must have run as #1");
        j.assertBuildStatusSuccess(build);
        assertReceivesSrc1(build);
        assertEquals("plain-run-value", ((StringParameterValue) build.getAction(ParametersAction.class).getParameter("P")).getValue());
        for (String surface : new String[] {"batch-control/history/requests.csv", "batch-control/history/runs.csv",
                "batch-control/dashboard/"}) {
            String body = readable(j, "viewer", surface);
            assertTrue(SHOWN.matcher(body).find(), surface + " must show the run value (src #1): " + UsabilityFixtures.excerpt(body));
        }
    }

    /**
     * T-06-103 (G-M2, D-60): u1 picks {@code src#1} and types P in core's parameters form of an
     * approval-required job. The refusal leads (303) to the job's Request Run form; following it,
     * the form has P filled in and SRC set to {@code src#1}, the submitted run, not the default
     * {@code src#2} (a run value is neither sensitive nor a file, so SPEC item 6 carries it).
     * Nothing is queued or stored.
     */
    @Test
    public void t_06_103_d60CarriesTheRunParameterValue() throws Exception {
        FreeStyleProject job = runJob("run-d60", false);
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(job, property);
        BatchControlFixtures.activate(job);
        Set<String> before = ApproverFormFixtures.runRequestIds();

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("u1");
        HtmlPage formPage = (HtmlPage) wc.getPage(new WebRequest(new URL(j.getURL(), job.getUrl() + "build?delay=0sec"), HttpMethod.GET));
        HtmlForm form = formPage.getFormByName("parameters");
        chooseRun(form, "src#1");
        TypedParameterFixtures.setValue(form, "P", "typed-p");
        wc.getOptions().setRedirectEnabled(false);
        Page answer = j.submit(form);
        assertEquals(303, answer.getWebResponse().getStatusCode(), "a requester's refused build must answer 303 (D-60)");
        String location = answer.getWebResponse().getResponseHeaderValue("Location");
        assertNotNull(location, "the 303 must carry a Location");
        URL target = new URL(answer.getUrl(), location);
        assertEquals(new URL(j.getURL(), job.getUrl() + "batch-control/").getPath(), target.getPath(),
                "the refusal must lead to the job's Request Run form");
        j.waitUntilNoActivity();
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "nothing may be stored before the form is submitted");
        assertTrue(j.jenkins.getQueue().isEmpty() && job.getBuilds().isEmpty(), "nothing may be queued or built");

        Page followed = UsabilityFixtures.clientNoJs(j, "u1").getPage(target);
        assertEquals(200, followed.getWebResponse().getStatusCode(), "the prefilled Request Run form must open");
        HtmlForm request = UsabilityFixtures.formsEndingWith((HtmlPage) followed, job.getUrl() + "batch-control/submit").get(0);
        assertEquals("typed-p", TypedParameterFixtures.valueOf(request, "P"), "guard: the string value is filled in");
        assertEquals("src#1", selectedRun(request), "the submitted run value must be filled in (D-60 carries every value that is"
                + " neither sensitive nor a file); Location was " + location);
    }

    /**
     * T-11-25 (G-M2): build #1 of an activated, uncontrolled job ran with SRC=src#1 and failed; the
     * job is then approval-required. u1's "Request rerun" on the incident creates the request
     * directly (a run value is recoverable): one PENDING request linked to the incident whose SRC
     * names {@code src #1}; once a1 approves, build #2 receives the run id {@code src#1}.
     */
    @Test
    public void t_11_25_incidentRerunCarriesTheRunParameterValue() throws Exception {
        FreeStyleProject job = runJob("run-rerun", true);
        uncontrolled(job);
        BatchControlFixtures.activateAsAdmin(job);
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, (Cause) null,
                new ParametersAction(new RunParameterValue("SRC", "src#1"), new StringParameterValue("P", "from-the-failed-run"))));
        j.waitUntilNoActivity();
        setBatchControl(job, new BatchControlJobProperty(true));
        Incident incident = RerunFallbackFixtures.incidentFor("run-rerun#1");
        Set<String> before = ApproverFormFixtures.runRequestIds();

        WebResponse rerun = RerunFallbackFixtures.postRerun(j, "u1", incident);
        assertTrue(rerun.getStatusCode() < 400, "the rerun must be accepted, got HTTP " + rerun.getStatusCode());
        String location = rerun.getResponseHeaderValue("Location");
        assertTrue(location == null || !location.contains(job.getUrl() + "batch-control/?"),
                "a recoverable run value must not fall back to the Request Run form: " + location);
        Set<String> after = ApproverFormFixtures.runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "the rerun must create exactly one request, got " + after);
        String id = after.iterator().next();
        RunRequest created = RunRequestService.get().load(id);
        assertEquals(incident.getId(), created.getIncidentId(), "the rerun request must be linked to the incident");
        assertTrue(RerunFallbackFixtures.rerunIds(incident.getId()).contains(id), "the incident must list the rerun request");
        String shown = created.getParameters().get("SRC");
        assertTrue(shown != null && SHOWN.matcher(shown).matches(), "the rerun request must carry src #1, was " + shown);

        DialogTypedParameterTest.approve(id);
        j.waitUntilNoActivity();
        FreeStyleBuild build = job.getBuildByNumber(2);
        assertNotNull(build, "the approved rerun must have run as #2");
        j.assertBuildStatusSuccess(build);
        assertReceivesSrc1(build);
    }

    // ---------------------------------------------------------------- helpers

    private FreeStyleProject runJob(String name, boolean failFirst) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        job.addProperty(new ParametersDefinitionProperty(
                new RunParameterDefinition("SRC", "src", "the build whose report to use", RunParameterDefinition.RunParameterFilter.ALL),
                new StringParameterDefinition("P", "p-default", "a plain value")));
        job.getBuildersList().add(new TypedParameterFixtures.CaptureEnv(failFirst, "SRC_JOBNAME", "SRC_NUMBER"));
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    private void assertReceivesSrc1(FreeStyleBuild build) {
        ParameterValue src = build.getAction(ParametersAction.class).getParameter("SRC");
        assertTrue(src instanceof RunParameterValue, "the build must receive a run value, got " + src);
        assertEquals("src#1", ((RunParameterValue) src).getRunId(), "the build must receive the chosen run, not the newest");
        String job = build.getParent().getFullName();
        assertEquals("src", TypedParameterFixtures.CaptureEnv.seen(job, build.getNumber(), "SRC_JOBNAME"));
        assertEquals("1", TypedParameterFixtures.CaptureEnv.seen(job, build.getNumber(), "SRC_NUMBER"));
    }

    private static HtmlSelect runSelect(HtmlForm form) {
        List<HtmlSelect> selects = TypedParameterFixtures.parameterBlock(form, "SRC").getByXPath(".//select");
        assertEquals(1, selects.size(), "fixture: SRC must offer one list of runs: "
                + UsabilityFixtures.excerpt(TypedParameterFixtures.parameterBlock(form, "SRC").asXml()));
        return selects.get(0);
    }

    private static void chooseRun(HtmlForm form, String runId) {
        HtmlSelect select = runSelect(form);
        select.setSelectedAttribute(runId, true);
        assertEquals(runId, select.getSelectedOptions().get(0).getValueAttribute(), "fixture: " + runId + " must be selected");
    }

    private static String selectedRun(HtmlForm form) {
        HtmlSelect select = runSelect(form);
        return select.getSelectedOptions().isEmpty() ? "" : select.getSelectedOptions().get(0).getValueAttribute();
    }
}
