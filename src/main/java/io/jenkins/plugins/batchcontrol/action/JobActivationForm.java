package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.model.Failure;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ModelObject;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.ActivationState;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.ui.ApproverInput;
import io.jenkins.plugins.batchcontrol.ui.ApproverOptions;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.Visibility;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * The activation request form of one job at {@code /job/<name>/batch-control/activation}
 * (SPEC item 6a, D-39), served by {@link JobRequestAction#getActivation()}. It inherits that
 * action's absence rule: without {@code BatchControl/Request} the whole
 * {@code /job/<name>/batch-control/} space answers 404, so this form is never shown to a user
 * who could not submit it.
 *
 * <p>The form offers the one action that can change anything: {@code ACTIVATE} for a job that is
 * not activated, {@code HOLD} for one that is. {@link #doSubmit} passes the posted value to
 * {@link ActivationService#create}, which is the only place a request is validated and stored;
 * this class holds no state logic.
 */
@Restricted(NoExternalUse.class)
public class JobActivationForm implements ModelObject {

    private final Job<?, ?> job;

    JobActivationForm(Job<?, ?> job) {
        this.job = job;
    }

    public Job<?, ?> getJob() {
        return job;
    }

    @Override
    public String getDisplayName() {
        return "Activation";
    }

    /** Permissions for the form's {@code l:layout}. */
    public Permission[] getViewPermissions() {
        return new Permission[] {BatchControlPermissions.REQUEST};
    }

    // ---------------------------------------------------------------- view model

    /** Whether the job may run on timer and upstream triggers as far as activation is concerned. */
    public boolean isActivated() {
        return ActivationService.get().isActivated(job);
    }

    /** Whether an approved hold took the job out of service (as opposed to never activated). */
    public boolean isHeld() {
        ActivationState state = getState();
        return !isActivated() && state != null && state.getDeactivatedBy() != null;
    }

    /** A child its folder computes for itself is not controlled and needs no activation (D-32). */
    public boolean isComputedChild() {
        return ActivationService.isComputedChild(job);
    }

    /** Whether run control is on and the job requires approval, i.e. the gate applies to it. */
    public boolean isRunControlled() {
        return JobActivationNoticeAction.isRunControlled(job);
    }

    @CheckForNull
    public ActivationState getState() {
        return ActivationService.get().getState(job);
    }

    /** The action a new request would ask for: {@code HOLD} when activated, else {@code ACTIVATE}. */
    public String getNextAction() {
        return isActivated() ? ActivationRequest.Action.HOLD.name() : ActivationRequest.Action.ACTIVATE.name();
    }

    /** The PENDING requests of this job the viewer may see (P-09), oldest first. */
    public List<ActivationRequest> getPendingRequests() {
        List<ActivationRequest> visible = new ArrayList<>();
        for (ActivationRequest request : ActivationService.get().listPendingForJob(job.getFullName())) {
            if (Visibility.canSeeActivationRequest(request)) {
                visible.add(request);
            }
        }
        return visible;
    }

    /** Approver candidates: global approver list ∩ job-level restriction (if configured). */
    public List<String> getApproverOptions() {
        return ApproverOptions.forJob(job);
    }

    /** Jelly helper: human-readable timestamp. */
    public String format(@CheckForNull Instant instant) {
        return Dates.format(instant);
    }

    // ---------------------------------------------------------------- submission

    /**
     * POST {@code submit} with {@code action} ({@code ACTIVATE}|{@code HOLD}), {@code reason} and
     * the repeated {@code approvers} field; redirects to the request at
     * {@code /batch-control/activations/<id>/}. Validation failures from the service render as a
     * {@link Failure} page (HTTP 400) with the message.
     */
    @RequirePOST
    public void doSubmit(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(BatchControlPermissions.REQUEST);
        job.checkPermission(Item.READ);

        // The rendered form posts the raw fields plus a json blob (f:form); a script may post the
        // raw fields only. The raw fields are read first either way.
        JSONObject formData = req.getParameter("json") != null ? req.getSubmittedForm() : null;
        String actionName = Util.fixEmptyAndTrim(req.getParameter("action"));
        if (actionName == null && formData != null) {
            actionName = Util.fixEmptyAndTrim(formData.optString("action", ""));
        }
        String reason = Util.fixEmptyAndTrim(req.getParameter("reason"));
        if (reason == null && formData != null) {
            reason = Util.fixEmptyAndTrim(formData.optString("reason", ""));
        }
        List<String> approvers = ApproverInput.read(req, formData);
        ActivationRequest.Action action = parseAction(actionName);

        ActivationRequest request;
        try {
            request = ActivationService.get().create(job, action, reason, approvers);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new Failure(e.getMessage() == null ? "The request was rejected" : e.getMessage());
        }
        rsp.sendRedirect2(req.getContextPath() + "/batch-control/activations/"
                + Util.rawEncode(request.getId()) + "/");
    }

    /** Exactly {@code ACTIVATE} or {@code HOLD}; anything else is refused before the service is called. */
    private static ActivationRequest.Action parseAction(@CheckForNull String value) {
        if (ActivationRequest.Action.ACTIVATE.name().equals(value)) {
            return ActivationRequest.Action.ACTIVATE;
        }
        if (ActivationRequest.Action.HOLD.name().equals(value)) {
            return ActivationRequest.Action.HOLD;
        }
        throw new Failure("The action must be ACTIVATE or HOLD.");
    }
}
