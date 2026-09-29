package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.model.Action;
import hudson.model.Failure;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.security.Permission;
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.SecretMasker;
import io.jenkins.plugins.batchcontrol.ui.ApproverInput;
import io.jenkins.plugins.batchcontrol.ui.ApproverOptions;
import io.jenkins.plugins.batchcontrol.ui.FormErrors;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Per-job "Request Run" page at {@code /job/<name>/batch-control/} (attached by
 * {@link JobRequestActionFactory}). Renders the request form — reason, approver set and the
 * job's parameter definitions exactly like the core build page — and submits it to
 * {@link RunRequestService#create}.
 *
 * <p>The sidebar link is only visible when run control is on, the job requires approval and the
 * user holds {@code BatchControl/Request}. Without {@code BatchControl/Request} the action is
 * absent altogether ({@link #getUrlName()} returns {@code null}, SPEC item 2, #31, S-07): the
 * form exposes the eligible approver user-id list, which is not for plain {@code Item/Read}
 * holders, so {@code /job/<name>/batch-control/} and every URL beneath it answer 404.
 */
@Restricted(NoExternalUse.class)
public class JobRequestAction implements Action {

    /** {@link FormErrors} name of the request form. */
    static final String FORM = "request";

    private final Job<?, ?> job;

    public JobRequestAction(Job<?, ?> job) {
        this.job = job;
    }

    public Job<?, ?> getJob() {
        return job;
    }

    // ---------------------------------------------------------------- Action

    @Override
    @CheckForNull
    public String getIconFileName() {
        // e2e-03 DEF-12: the entry is offered only to a user who can submit the form, which
        // also needs Job/Build on the job (D-38). The URL space stays for Request holders
        // because the activation form lives under it; the form page explains the missing
        // permission to anyone who opens it without Job/Build.
        if (!isActive() || !isCanRequestRun()) {
            return null;
        }
        return "symbol-paper-plane-outline plugin-ionicons-api";
    }

    /** Whether the current user may use this action at all ({@code BatchControl/Request}). */
    private static boolean canRequest() {
        return Jenkins.get().hasPermission(BatchControlPermissions.REQUEST);
    }

    /**
     * Whether the current user may submit a run request for this job: {@code BatchControl/Request}
     * plus {@code Item/Build} on the job (D-38, #24), the checks {@link RunRequestService#create}
     * makes. View gating only; {@link #doSubmit} and the service check for real.
     */
    public boolean isCanRequestRun() {
        return RunRequestService.get().canRequest(job);
    }

    /**
     * The one sidebar entry on a controlled job that reads "Request Run" (U-02): core's relabeled
     * build link is named {@link RequestRunUiDecorator#BLOCKED_BUILD_LABEL} so the two cannot be
     * confused.
     */
    @Override
    public String getDisplayName() {
        return RequestRunUiDecorator.REQUEST_RUN_LABEL;
    }

    /**
     * {@code null} without {@code BatchControl/Request}: per {@link Action#getUrlName()} that makes
     * the action unreachable, so its whole URL space answers 404 (absent, not refused).
     * {@link #doSubmit} re-checks the permission on top of this.
     */
    @Override
    @CheckForNull
    public String getUrlName() {
        return canRequest() ? "batch-control" : null;
    }

    /** Permissions for the request form's {@code l:layout}. */
    public Permission[] getViewPermissions() {
        return new Permission[] {BatchControlPermissions.REQUEST};
    }

    // ---------------------------------------------------------------- view model

    /** True when run control is on and this job requires approved runs. */
    public boolean isActive() {
        return RunRequestService.requiresApprovalToRun(job);
    }

    /**
     * The job's parameter definitions, rendered by each definition's own {@code index.jelly}.
     *
     * <p>e2e-03 DEF-09: when a submission is being refused, each definition whose value was
     * parsed is replaced by a copy defaulting to that value
     * ({@link ParameterDefinition#copyWithDefaultValue}), so the re-rendered form shows what the
     * user entered. Sensitive values (password parameters) are never copied back into the page.
     */
    public List<ParameterDefinition> getParameterDefinitions() {
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (property == null) {
            return Collections.emptyList();
        }
        List<ParameterDefinition> definitions = property.getParameterDefinitions();
        Object submitted = getFormErrors().getAttachment();
        if (!(submitted instanceof List)) {
            return definitions;
        }
        Map<String, ParameterValue> byName = new LinkedHashMap<>();
        for (Object value : (List<?>) submitted) {
            if (value instanceof ParameterValue) {
                byName.put(((ParameterValue) value).getName(), (ParameterValue) value);
            }
        }
        List<ParameterDefinition> refilled = new ArrayList<>(definitions.size());
        for (ParameterDefinition definition : definitions) {
            ParameterValue value = byName.get(definition.getName());
            ParameterDefinition shown = definition;
            if (value != null && !value.isSensitive() && !(value.getValue() instanceof Secret)) {
                try {
                    shown = definition.copyWithDefaultValue(value);
                } catch (RuntimeException e) {
                    shown = definition; // a definition that cannot copy keeps its own default
                }
            }
            refilled.add(shown == null ? definition : shown);
        }
        return refilled;
    }

    /** The refusal of the last submission on this request, or an empty one (DEF-09). */
    public FormErrors getFormErrors() {
        return FormErrors.current(FORM);
    }

    /** Approver candidates: global approver list ∩ job-level restriction (if configured). */
    public List<String> getApproverOptions() {
        return ApproverOptions.forJob(job);
    }

    /**
     * Stapler: serves {@code /job/<name>/batch-control/activation} (SPEC item 6a). Reachable only
     * through this action, so it is absent (404) without {@code BatchControl/Request} like the
     * rest of this URL space; {@link JobActivationForm#doSubmit} re-checks the permissions.
     */
    public JobActivationForm getActivation() {
        return new JobActivationForm(job);
    }

    // ---------------------------------------------------------------- submission

    /**
     * POST {@code submit} — creates the run request and redirects to its detail page at
     * {@code /batch-control/requests/<id>/}. A refused submission re-renders the form with HTTP 400,
     * the message next to the field it concerns and the user's input kept (e2e-03 DEF-09).
     *
     * <p>N-01: {@link #parseParameters} is inside the {@code try} on purpose. A parameter
     * definition rejects a bad value by throwing {@link IllegalArgumentException} — a choice
     * parameter given a value outside its choices, for instance — and that is the same class the
     * service's own validation throws. Parsing outside the {@code try} turned user-supplied
     * input into an uncaught exception and an HTTP 500 "Oops!" page while an empty reason
     * correctly answered 400; both are user input and both belong in the same 400 channel, now
     * the re-rendered form.
     *
     * <p>security-08 S-10: a scripted submission with no {@code json} field used to store an
     * empty parameter map, so the later approved build ran with whatever defaults the job had at
     * build time rather than the values the approver saw. {@link #parseRawParameters} now reads
     * each defined parameter straight from the request the way core's
     * {@code ParametersDefinitionProperty#_doBuild} / {@code buildWithParameters} do, so the
     * stored map is complete either way.
     */
    @RequirePOST
    public void doSubmit(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        job.checkPermission(Item.READ);
        Jenkins.get().checkPermission(BatchControlPermissions.REQUEST);
        // D-38: the requester needs Job/Build. The service checks it too; checking it here first
        // keeps the answer a 403 whatever else is wrong with the submission.
        job.checkPermission(Item.BUILD);

        // The rendered form posts a json blob (f:form) plus the raw fields; a script may post
        // the raw fields only. Both carry the same contract: reason, repeated approvers (D-37).
        JSONObject formData = req.getParameter("json") != null ? req.getSubmittedForm() : null;
        String reason = Util.fixEmptyAndTrim(formData != null
                ? formData.optString("reason", "") : Util.fixNull(req.getParameter("reason")));

        // e2e-03 DEF-09: every refusal of the user's input is shown on the form, next to the
        // field, with the input kept (FormErrors), instead of a bare "Error" page.
        FormErrors errors = new FormErrors(FORM);
        List<String> approvers = List.of();
        try {
            approvers = ApproverInput.read(req, formData);
        } catch (Failure e) {
            errors.field("approvers", e.getMessage());
        }
        List<ParameterValue> submitted = new ArrayList<>();
        Map<String, String> parameters = null;
        try {
            parameters = formData == null
                    ? parseRawParameters(req, submitted) : parseParameters(req, formData, submitted);
        } catch (IllegalArgumentException | Failure e) {
            errors.field("parameters", "A parameter value was refused"
                    + (e.getMessage() == null ? "." : ": " + e.getMessage()));
        }
        if (reason == null) {
            errors.field("reason", "Enter a reason: the approvers decide on it.");
        }
        if (approvers.isEmpty()) {
            errors.field("approvers", "Check at least one approver.");
        }
        if (errors.isEmpty()) {
            try {
                RunRequest request = RunRequestService.get().create(job, parameters, reason, approvers);
                rsp.sendRedirect2(req.getContextPath() + "/batch-control/requests/"
                        + Util.rawEncode(request.getId()) + "/");
                return;
            } catch (IllegalArgumentException | IllegalStateException e) {
                errors.fromService(e.getMessage(), "reason", "reason", "approver", "approvers",
                        "parameter", "parameters");
            }
        }
        errors.attach(submitted).render(req, rsp, this);
    }

    /**
     * Parses the {@code parameter} JSON array the same way core's
     * {@code ParametersDefinitionProperty._doBuild} does (each entry goes through the parameter
     * definition's {@code createValue}), then flattens the values to strings.
     *
     * <p>Throws {@link Failure} for a parameter name the job does not define, and lets a
     * definition's own {@link IllegalArgumentException} propagate for a value it refuses; the
     * caller turns the latter into the same 400 as every other rejected submission (N-01).
     */
    private Map<String, String> parseParameters(StaplerRequest2 req, JSONObject formData,
                                                List<ParameterValue> submitted) {
        Map<String, String> parameters = new LinkedHashMap<>();
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (property == null) {
            return parameters;
        }
        Object parameter = formData.opt("parameter");
        if (parameter == null) {
            return parameters;
        }
        for (Object entry : JSONArray.fromObject(parameter)) {
            if (!(entry instanceof JSONObject)) {
                continue;
            }
            JSONObject jsonEntry = (JSONObject) entry;
            String name = jsonEntry.optString("name", null);
            if (name == null) {
                continue;
            }
            ParameterDefinition definition = property.getParameterDefinition(name);
            if (definition == null) {
                throw new Failure("No such parameter definition: " + name);
            }
            ParameterValue value = definition.createValue(req, jsonEntry);
            if (value != null) {
                submitted.add(value);
                parameters.put(value.getName(), flatten(value));
            }
        }
        return parameters;
    }

    /**
     * Parses parameter values for a scripted submission that carries no {@code json} form field
     * (security-08 S-10). Each of the job's defined parameters reads its own raw request field
     * through {@link ParameterDefinition#createValue(StaplerRequest2)} — the same call core's
     * {@code buildWithParameters} makes — falling back explicitly to
     * {@link ParameterDefinition#getDefaultParameterValue()} when the definition itself returns
     * {@code null} for a missing field, so the stored map is complete rather than empty. Values
     * still go through {@link #flatten}, so secrets are masked exactly as for the JSON path, and a
     * definition's own {@link IllegalArgumentException} for a bad value propagates unchanged
     * (N-01: caught by the caller's {@code try}).
     */
    private Map<String, String> parseRawParameters(StaplerRequest2 req, List<ParameterValue> submitted) {
        Map<String, String> parameters = new LinkedHashMap<>();
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (property == null) {
            return parameters;
        }
        for (ParameterDefinition definition : property.getParameterDefinitions()) {
            ParameterValue value = definition.createValue(req);
            if (value == null) {
                value = definition.getDefaultParameterValue();
            }
            if (value != null) {
                submitted.add(value);
                parameters.put(value.getName(), flatten(value));
            }
        }
        return parameters;
    }

    /**
     * Flattens a {@link ParameterValue} to the string that is stored on the request. Sensitive
     * values (password parameters, {@link Secret}s) are masked and never stored in plaintext —
     * see the slice report for the resulting limitation on reproducing password parameters.
     */
    private static String flatten(ParameterValue value) {
        if (value.isSensitive()) {
            return SecretMasker.MASK;
        }
        Object raw = value.getValue();
        if (raw instanceof Secret) {
            return SecretMasker.MASK;
        }
        return raw == null ? "" : String.valueOf(raw);
    }
}
