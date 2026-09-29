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
import io.jenkins.plugins.batchcontrol.ui.ActivationView;
import io.jenkins.plugins.batchcontrol.ui.ApproverOptions;
import io.jenkins.plugins.batchcontrol.ui.FormErrors;
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
 * The activation request form at {@code <item>/batch-control/activation} (SPEC item 6a, D-39)
 * for a job ({@link JobActivationRoute#getActivation()}) or a computed folder, which carries the
 * activation of its children (D-46c, {@link ComputedFolderActivationAction#getActivation()}). It
 * inherits the owning action's absence rule: without {@code BatchControl/Request} the whole
 * {@code <item>/batch-control/} space answers 404, so this form is never shown to a user who
 * could not submit it.
 *
 * <p>The form offers the one action that can change anything: {@code ACTIVATE} for a job that is
 * not activated, {@code HOLD} for one that is. {@link #doSubmit} passes the posted value to
 * {@link ActivationService#create}, which is the only place a request is validated and stored;
 * this class holds no state logic.
 */
@Restricted(NoExternalUse.class)
public class JobActivationForm implements ModelObject {

    /** {@link FormErrors} name of the activation form. */
    static final String FORM = "activation";

    /** A job, or a computed folder (D-46c). */
    private final Item item;

    JobActivationForm(Item item) {
        this.item = item;
    }

    public Item getItem() {
        return item;
    }

    /** Whether the target is a computed folder rather than a job (the view words it accordingly). */
    public boolean isFolder() {
        return !(item instanceof Job);
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
        return ActivationView.isActivated(item);
    }

    /** Whether an approved hold took the job out of service (as opposed to never activated). */
    public boolean isHeld() {
        return ActivationView.isHeld(item);
    }

    /**
     * A computed child carries no activation of its own: the item returned here does (D-46c).
     * {@code null} for an item that carries its own.
     */
    @CheckForNull
    public Item getCarrier() {
        return ActivationView.carrierOf(item);
    }

    /**
     * S-14-02: whether the viewer holds {@code Item/Read} on the carrier, so its name may be
     * shown; otherwise the view renders a neutral line.
     */
    public boolean isCarrierReadable() {
        Item carrier = getCarrier();
        return carrier != null && carrier.hasPermission(Item.READ);
    }

    /** Whether run control is on, i.e. the gate applies (D-46a: whatever approvalRequired says). */
    public boolean isRunControlled() {
        return JobActivationNoticeAction.isRunControlEnabled();
    }

    @CheckForNull
    public ActivationState getState() {
        return ActivationView.getState(item);
    }

    /** The action a new request would ask for: {@code HOLD} when activated, else {@code ACTIVATE}. */
    public String getNextAction() {
        return isActivated() ? ActivationRequest.Action.HOLD.name() : ActivationRequest.Action.ACTIVATE.name();
    }

    /** The PENDING requests of this job the viewer may see (P-09), oldest first. */
    public List<ActivationRequest> getPendingRequests() {
        List<ActivationRequest> visible = new ArrayList<>();
        for (ActivationRequest request : ActivationService.get().listPendingForJob(item.getFullName())) {
            if (Visibility.canSeeActivationRequest(request)) {
                visible.add(request);
            }
        }
        return visible;
    }

    /** Approver candidates: global approver list ∩ job-level restriction (if configured). */
    public List<String> getApproverOptions() {
        return ApproverOptions.forJob(item instanceof Job ? (Job<?, ?>) item : null);
    }

    /** S-13-08: the request kind as the screens word it. */
    public String actionLabel(ActivationRequest.Action action) {
        return ActivationView.actionLabel(action);
    }

    /** Jelly helper: human-readable timestamp. */
    public String format(@CheckForNull Instant instant) {
        return Dates.format(instant);
    }

    // ---------------------------------------------------------------- submission

    /**
     * POST {@code submit} with {@code action} ({@code ACTIVATE}|{@code HOLD}), {@code reason} and
     * the repeated {@code approvers} field; redirects to the request at
     * {@code /batch-control/activations/<id>/}. A refused submission re-renders the form (HTTP 400)
     * with the message next to its field and the input kept (e2e-03 DEF-09).
     */
    @RequirePOST
    public void doSubmit(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(BatchControlPermissions.REQUEST);
        item.checkPermission(Item.READ);

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
        ActivationRequest.Action action = parseAction(actionName);

        // e2e-03 DEF-09: refusals of the user's input come back on the form (FormErrors).
        FormErrors errors = new FormErrors(FORM);
        List<String> approvers = List.of();
        try {
            approvers = ApproverInput.read(req, formData);
        } catch (Failure e) {
            errors.field("approvers", e.getMessage());
        }
        if (reason == null) {
            errors.field("reason", "Enter a reason: the approvers decide on it.");
        }
        if (approvers.isEmpty()) {
            errors.field("approvers", "Check at least one approver.");
        }
        if (errors.isEmpty()) {
            try {
                ActivationRequest request = ActivationService.get().create(item, action, reason, approvers);
                rsp.sendRedirect2(req.getContextPath() + "/batch-control/activations/"
                        + Util.rawEncode(request.getId()) + "/");
                return;
            } catch (IllegalArgumentException | IllegalStateException e) {
                errors.fromService(e.getMessage(), "reason", "reason", "approver", "approvers");
            }
        }
        errors.render(req, rsp, this);
    }

    /** The refusal of the last submission on this request, or an empty one (DEF-09). */
    public FormErrors getFormErrors() {
        return FormErrors.current(FORM);
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
