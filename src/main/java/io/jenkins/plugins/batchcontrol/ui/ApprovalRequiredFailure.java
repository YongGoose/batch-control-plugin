package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Failure;
import hudson.model.Item;
import hudson.model.Job;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
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
 */
@Restricted(NoExternalUse.class)
public class ApprovalRequiredFailure extends Failure {

    private static final long serialVersionUID = 1L;

    private final String jobFullName;

    /**
     * @param job the approval-required job whose run was refused
     * @param message the plain-text guidance returned by {@link #getMessage()}
     */
    public ApprovalRequiredFailure(Job<?, ?> job, String message) {
        super(message);
        this.jobFullName = job.getFullName();
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
     * (without it {@code <job>/batch-control/} answers 404) and may read the job.
     */
    public boolean isCanRequest() {
        Job<?, ?> job = getJob();
        return job != null && job.hasPermission(Item.READ)
                && Jenkins.get().hasPermission(BatchControlPermissions.REQUEST);
    }

    @Override
    public void generateResponse(StaplerRequest2 req, StaplerResponse2 rsp, Object node,
                                 @CheckForNull Throwable throwable)
            throws IOException, ServletException {
        RequestDispatcher view = req.getView(this, "index.jelly");
        if (view == null) {
            super.generateResponse(req, rsp, node, throwable);
            return;
        }
        rsp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        view.forward(req, rsp);
    }

    @Override
    public void generateResponse(StaplerRequest2 req, StaplerResponse2 rsp, Object node)
            throws IOException, ServletException {
        generateResponse(req, rsp, node, null);
    }
}
