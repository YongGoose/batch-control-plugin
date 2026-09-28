package io.jenkins.plugins.batchcontrol;

import hudson.model.ChoiceParameterDefinition;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matrix rows T-UI-07 and T-UI-08 — finding N-01: a parameter value the job's own definition
 * refuses reached the user as HTTP 500 and a generic "Oops!" page, because the parse call sat
 * outside the {@code try} that turns rejected input into 400 two lines later.
 *
 * <p>Both halves are user input and both belong in the same 400 channel, so these rows pin the
 * status code, the message, and the absence of a created record together: a 400 that had already
 * written the request would be a different defect with the same status line.
 *
 * <p>Screen-contract rows (matrix note 47): docs/SPEC.md names no status code for a rejected
 * submission, so the numbers here are the implementation's contract rather than an acceptance
 * criterion — the same footing as the other {@code T-UI-*} rows (note 40).
 */
@WithJenkins
public class RequestParameterValidationTest {

    private static final String JOB = "param-x";

    private JenkinsRule j;

    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to("a1"));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        job = j.createFreeStyleProject(JOB);
        job.addProperty(new ParametersDefinitionProperty(
                new ChoiceParameterDefinition("MODE", new String[] {"full", "partial"},
                        "how much of the batch to run")));
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
        assertTrue(job.getProperty(BatchControlJobProperty.class).isApprovalRequired(), "fixture: the job must require approval, or nothing validates a submission");
    }

    /**
     * T-UI-07: a choice parameter given a value outside its choices is refused with HTTP 400 that
     * names the parameter, no run request is written, and nothing is logged as an uncaught
     * exception. 500 is called out explicitly because that is exactly what this used to be.
     */
    @Test
    public void t_ui_07_outOfRangeParameterValueIs400AndNot500() throws Exception {
        int before = RunRequestService.get().list().size();

        List<String> uncaught = new ArrayList<>();
        WebResponse response;
        try (LogRecorder log = new LogRecorder()
                .record("", Level.WARNING).capture(1000).quiet()) {
            response = submit("u1", "month-end batch run", "a1", "MODE", "normal");
            for (LogRecord record : log.getRecords()) {
                if (looksLikeRejectedInput(record)) {
                    uncaught.add(record.getLevel() + " " + record.getMessage() + " / "
                            + (record.getThrown() == null ? "no throwable"
                                    : record.getThrown().toString()));
                }
            }
        }

        assertFalse(response.getStatusCode() == 500, "a value the parameter definition refuses is user input, not a server fault:"
                + " HTTP 500 is the N-01 defect itself");
        assertEquals(400, response.getStatusCode(), "an out-of-range parameter value must be refused with HTTP 400");

        String message = errorMessage(response);
        assertTrue(message.contains("MODE"), "the refusal must name the rejected parameter, but said: " + message);
        assertTrue(message.contains("normal"), "the refusal must quote the value it rejected, but said: " + message);

        assertEquals(before, RunRequestService.get().list().size(), "a refused submission must create no run request");
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "a refused submission must start no build");
        assertEquals(1, job.getNextBuildNumber(), "the job's next build number must be unchanged");
        assertTrue(uncaught.isEmpty(), "rejected user input must not be logged as an uncaught failure, but "
                + uncaught.size() + " record(s) were: " + uncaught);
    }

    /**
     * T-UI-08: the guards that must keep working around the N-01 fix — moving the parse inside the
     * try must not have swallowed the refusals that were already correct, nor broken the happy
     * path. An empty reason and an unknown parameter name both still answer 400 with their own
     * message, and a valid submission still redirects to the request it created.
     */
    @Test
    public void t_ui_08_emptyReasonUnknownParameterAndValidSubmissionKeepTheirOutcomes()
            throws Exception {
        int before = RunRequestService.get().list().size();

        // 1. Empty reason: SPEC item 5 makes the reason mandatory.
        WebResponse noReason = submit("u1", "", "a1", "MODE", "full");
        assertEquals(400, noReason.getStatusCode(), "an empty reason must still be refused with HTTP 400");
        assertTrue(errorMessage(noReason).toLowerCase(Locale.ROOT).contains("reason"), "the refusal must be about the reason, but said: " + errorMessage(noReason));
        assertEquals(before, RunRequestService.get().list().size(), "the reason-less submission must create nothing");

        // 2. A parameter the job does not define.
        WebResponse unknown = submit("u1", "month-end batch run", "a1", "NOT_A_PARAM", "full");
        assertEquals(400, unknown.getStatusCode(), "an unknown parameter name must still be refused with HTTP 400");
        assertTrue(errorMessage(unknown).contains("NOT_A_PARAM"), "the refusal must name the parameter it does not know, but said: "
                + errorMessage(unknown));
        assertEquals(before, RunRequestService.get().list().size(), "the unknown-parameter submission must create nothing");

        // 3. The happy path, which is what makes the two refusals above meaningful.
        WebResponse valid = submit("u1", "month-end batch run", "a1", "MODE", "partial");
        assertEquals(302, valid.getStatusCode(), "a valid submission must redirect to the new request");
        assertEquals(before + 1, RunRequestService.get().list().size(), "a valid submission must create exactly one run request");

        RunRequest created = RunRequestService.get().list().stream()
                .filter(r -> JOB.equals(r.getJobFullName()))
                .findFirst().orElse(null);
        assertNotNull(created, "the created request must belong to the job that was submitted");
        assertEquals("partial", created.getParameters().get("MODE"), "the accepted choice must be stored as submitted");
        String location = valid.getResponseHeaderValue("Location");
        assertNotNull(location, "the redirect must carry a Location header");
        assertTrue(location.endsWith("/batch-control/requests/" + created.getId() + "/"), "the redirect must point at the new request's own page, but was " + location);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * POSTs the per-job request form the way the rendered form does — a {@code json} blob, which
     * is what {@code StaplerRequest#getSubmittedForm} reads — with exactly one job parameter.
     */
    private WebResponse submit(String userId, String reason, String approver,
                               String parameterName, String parameterValue) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false).login(userId);
        wc.getOptions().setRedirectEnabled(false); // read the 302 itself, not its target

        JSONObject parameter = new JSONObject();
        parameter.put("name", parameterName);
        parameter.put("value", parameterValue);
        JSONObject form = new JSONObject();
        form.put("reason", reason);
        form.put("approvers", approver); // SPEC 3 (D-37): the field is `approvers`
        form.put("parameter", parameter);

        WebRequest request = new WebRequest(
                wc.createCrumbedUrl(job.getUrl() + "batch-control/submit"), HttpMethod.POST);
        request.setRequestParameters(Arrays.asList(new NameValuePair("json", form.toString())));
        Page page = wc.getPage(request);
        return page.getWebResponse();
    }

    /**
     * The refusal message. Core's error view puts it both in the page body and in an
     * {@code X-Error} header; the header is read first because it is not subject to HTML escaping.
     */
    private static String errorMessage(WebResponse response) {
        String header = response.getResponseHeaderValue("X-Error");
        return header != null ? header : response.getContentAsString();
    }

    /**
     * A log record that reports the submission as a failure rather than as refused input. The
     * filter is on the exception type the parameter definition throws, so ordinary unrelated
     * warnings from the harness do not make this row flaky.
     */
    private static boolean looksLikeRejectedInput(LogRecord record) {
        for (Throwable t = record.getThrown(); t != null; t = t.getCause()) {
            if (t instanceof IllegalArgumentException) {
                return true;
            }
        }
        String message = record.getMessage();
        return message != null && message.contains("Illegal choice for parameter");
    }
}
