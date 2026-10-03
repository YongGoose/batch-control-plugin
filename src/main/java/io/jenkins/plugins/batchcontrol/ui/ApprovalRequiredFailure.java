package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Failure;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.Ancestor;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * The refusal of a user-originated manual run of an approval-required job (SPEC item 6, e2e-03
 * DEF-02), thrown by the queue gate in place of a plain {@link Failure}.
 *
 * <p>{@link #getMessage()} is the gate's plain-text guidance, unchanged for the CLI and for any
 * caller that only reads the message. In a browser (the parameterized build form posts to core's
 * {@code build} and gets this page back) {@link #generateResponse} renders
 * {@code index.jelly} instead of core's generic error page, which printed the request URL as
 * text: the page links the request form only for a {@code BatchControl/Request} holder, the only
 * user for whom that URL is not a 404 ({@code JobRequestAction#getUrlName()}), and tells anyone
 * else whom to ask. It answers HTTP 400 like the {@link Failure} it replaces and never changes
 * state.
 *
 * <p>Only the job's full name is kept (the exception is {@link java.io.Serializable}); the job is
 * looked up again for the page as the viewing user, so a job the viewer cannot read is simply
 * not linked.
 *
 * <h2>D-60: a refused parameterized build leads to the Request Run form</h2>
 * When a person's submission to the {@code build} or {@code buildWithParameters} endpoint of a
 * parameterized job is refused and the viewer may request runs ({@link #isCanRequest()}), the
 * answer is a 303 redirect to the job's Request Run form with the submitted values carried as
 * {@code p.<name>} query parameters ({@link RequestRunPrefill}; sensitive values never). This
 * covers the classic build form, {@code buildWithParameters} from a browser and the parameters
 * dialog of the new job page: that dialog submits with {@code fetch} and, given a page without a
 * form, re-opened the response URL with GET — core's classic parameters form, which asked for
 * the values a second time. A followed redirect is opened as it is, so the dialog now leads to
 * the request form. Nothing is queued or stored by the redirect. Re-runs (Rebuild, Retry,
 * Replay) post elsewhere and keep the page below.
 *
 * <p>Any other viewer gets this page. Its content sits inside a {@code <form>} with no action and
 * no controls, never submitted, only so that the new job page's dialog shows the refusal in
 * place (it renders the first form of a response) instead of re-opening the classic form.
 */
@Restricted(NoExternalUse.class)
public class ApprovalRequiredFailure extends Failure {

    private static final long serialVersionUID = 1L;

    private final String jobFullName;

    /** D-60: the values carried to the request form, already stripped of sensitive ones. */
    private final LinkedHashMap<String, String> carried;

    /**
     * @param job the approval-required job whose run was refused
     * @param message the plain-text guidance returned by {@link #getMessage()}
     */
    public ApprovalRequiredFailure(Job<?, ?> job, String message) {
        this(job, message, null);
    }

    /**
     * @param job the approval-required job whose run was refused
     * @param message the plain-text guidance returned by {@link #getMessage()}
     * @param submitted the parameter values of the refused submission (its {@code ParametersAction}),
     *     or {@code null}; only those {@link RequestRunPrefill#carriedValues} allows are kept
     */
    public ApprovalRequiredFailure(Job<?, ?> job, String message,
                                   @CheckForNull List<ParameterValue> submitted) {
        super(message);
        this.jobFullName = job.getFullName();
        this.carried = new LinkedHashMap<>(RequestRunPrefill.carriedValues(job, submitted));
    }

    public String getJobFullName() {
        return jobFullName;
    }

    /** The refused job as the viewer sees it, or {@code null} when the viewer cannot read it. */
    @CheckForNull
    public Job<?, ?> getJob() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        return jenkins == null ? null : jenkins.getItemByFullName(jobFullName, Job.class);
    }

    /**
     * Whether the page links the request form: the viewer holds {@code BatchControl/Request}
     * (without it {@code <job>/batch-control/} answers 404), may read the job and holds
     * {@code Item/Build} on it (without it the submission is refused, D-38).
     */
    public boolean isCanRequest() {
        Job<?, ?> job = getJob();
        // e2e-03 DEF-12: the form is only useful with Job/Build as well (D-38).
        return job != null && job.hasPermission(Item.READ) && job.hasPermission(Item.BUILD)
                && Jenkins.get().hasPermission(BatchControlPermissions.REQUEST);
    }

    @Override
    public void generateResponse(StaplerRequest2 req, StaplerResponse2 rsp, Object node,
                                 @CheckForNull Throwable throwable)
            throws IOException, ServletException {
        Job<?, ?> job = getJob();
        if (job != null && isCanRequest() && isBuildSubmission(req, job)) {
            // D-60: to the request form, with the submitted values; never to core's build form.
            rsp.sendRedirect(HttpServletResponse.SC_SEE_OTHER, req.getContextPath() + "/" + job.getUrl()
                    + "batch-control/" + RequestRunPrefill.toQuery(carried == null ? Map.of() : carried));
            return;
        }
        rsp.setHeader("X-Dialog-Title", "Approval required");
        RequestDispatcher view = req.getView(this, "index.jelly");
        if (view == null) {
            super.generateResponse(req, rsp, node, throwable);
            return;
        }
        rsp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        view.forward(req, rsp);
    }

    /**
     * Whether this request is a submission to the parameterized job's own {@code build} or
     * {@code buildWithParameters} endpoint: the job is the last object the URL resolved to and
     * that endpoint is the last path segment.
     */
    private static boolean isBuildSubmission(StaplerRequest2 req, Job<?, ?> job) {
        if (job.getProperty(ParametersDefinitionProperty.class) == null) {
            return false;
        }
        List<Ancestor> ancestors = req.getAncestors();
        if (ancestors.isEmpty()) {
            return false;
        }
        Object last = ancestors.get(ancestors.size() - 1).getObject();
        if (!(last instanceof Job) || !((Job<?, ?>) last).getFullName().equals(job.getFullName())) {
            return false;
        }
        String uri = req.getRequestURI();
        String segment = uri.substring(uri.lastIndexOf('/') + 1);
        return "build".equals(segment) || "buildWithParameters".equals(segment);
    }

    @Override
    public void generateResponse(StaplerRequest2 req, StaplerResponse2 rsp, Object node)
            throws IOException, ServletException {
        generateResponse(req, rsp, node, null);
    }
}
