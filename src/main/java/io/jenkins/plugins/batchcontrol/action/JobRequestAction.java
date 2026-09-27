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
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.SecretMasker;
import io.jenkins.plugins.batchcontrol.ui.ApproverOptions;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerProxy;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Per-job "Request Run" page at {@code /job/<name>/batch-control/} (attached by
 * {@link JobRequestActionFactory}). Renders the request form — reason, approver choice and the
 * job's parameter definitions exactly like the core build page — and submits it to
 * {@link RunRequestService#create}.
 *
 * <p>The sidebar link is only visible when run control is on, the job requires approval and the
 * user holds {@code BatchControl/Request}. The URL space itself is gated on
 * {@code BatchControl/Request} too ({@link #getTarget()}, S-07): the form exposes the eligible
 * approver user-id list, which is not for plain {@code Item/Read} holders.
 */
@Restricted(NoExternalUse.class)
public class JobRequestAction implements Action, StaplerProxy {

    private final Job<?, ?> job;

    public JobRequestAction(Job<?, ?> job) {
        this.job = job;
    }

    public Job<?, ?> getJob() {
        return job;
    }

    @Override
    public Object getTarget() {
        // Gate the whole /job/<name>/batch-control/** subtree (S-07): the request form and the
        // data it exposes (approver ids) require BatchControl/Request, not just Item/Read.
        // doSubmit re-checks on top of this.
        Jenkins.get().checkPermission(BatchControlPermissions.REQUEST);
        return this;
    }

    // ---------------------------------------------------------------- Action

    @Override
    @CheckForNull
    public String getIconFileName() {
        if (!isActive() || !Jenkins.get().hasPermission(BatchControlPermissions.REQUEST)) {
            return null;
        }
        return "symbol-paper-plane-outline";
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

    @Override
    public String getUrlName() {
        return "batch-control";
    }

    // ---------------------------------------------------------------- view model

    /** True when run control is on and this job requires approved runs. */
    public boolean isActive() {
        if (!BatchControlGlobalConfiguration.get().isRunControlEnabled()) {
            return false;
        }
        BatchControlJobProperty property = job.getProperty(BatchControlJobProperty.class);
        return property != null && property.isApprovalRequired();
    }

    /** The job's parameter definitions, rendered by each definition's own {@code index.jelly}. */
    public List<ParameterDefinition> getParameterDefinitions() {
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        return property == null ? Collections.emptyList() : property.getParameterDefinitions();
    }

    /** Approver candidates: global approver list ∩ job-level restriction (if configured). */
    public List<String> getApproverOptions() {
        return ApproverOptions.forJob(job);
    }

    // ---------------------------------------------------------------- submission

    /**
     * POST {@code submit} — creates the run request and redirects to its detail page at
     * {@code /batch-control/requests/<id>/}. Validation failures from the service render as a
     * {@link Failure} page with the message.
     *
     * <p>N-01: {@link #parseParameters} is inside the {@code try} on purpose. A parameter
     * definition rejects a bad value by throwing {@link IllegalArgumentException} — a choice
     * parameter given a value outside its choices, for instance — and that is the same class the
     * service's own validation throws. Parsing outside the {@code try} turned user-supplied
     * input into an uncaught exception and an HTTP 500 "Oops!" page while an empty reason
     * correctly answered 400; both are user input and both belong in the same 400 channel.
     */
    @RequirePOST
    public void doSubmit(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        job.checkPermission(Item.READ);
        Jenkins.get().checkPermission(BatchControlPermissions.REQUEST);

        JSONObject formData = req.getSubmittedForm();
        String reason = Util.fixEmptyAndTrim(formData.optString("reason", ""));
        String approver = Util.fixEmptyAndTrim(formData.optString("approver", ""));

        RunRequest request;
        try {
            Map<String, String> parameters = parseParameters(req, formData);
            request = RunRequestService.get().create(job, parameters, reason, approver);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new Failure(e.getMessage() == null ? "The request was rejected" : e.getMessage());
        }
        rsp.sendRedirect2(req.getContextPath() + "/batch-control/requests/"
                + Util.rawEncode(request.getId()) + "/");
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
    private Map<String, String> parseParameters(StaplerRequest2 req, JSONObject formData) {
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
