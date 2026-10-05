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
import hudson.model.PasswordParameterDefinition;
import hudson.security.Permission;
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.policy.ParameterFiles;
import io.jenkins.plugins.batchcontrol.policy.RequestBodyLimit;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantLayer;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.ParameterDisplay;
import io.jenkins.plugins.batchcontrol.ui.ApproverInput;
import io.jenkins.plugins.batchcontrol.ui.ApproverOptions;
import io.jenkins.plugins.batchcontrol.ui.Dialogs;
import io.jenkins.plugins.batchcontrol.ui.FormErrors;
import io.jenkins.plugins.batchcontrol.ui.ReplayedRuns;
import io.jenkins.plugins.batchcontrol.ui.RequestRunPrefill;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import jenkins.model.menu.Group;
import jenkins.model.menu.Semantic;
import jenkins.model.menu.event.DialogEvent;
import jenkins.model.menu.event.Event;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
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

    /**
     * D-72a: request attribute holding the incident reference {@link #doSubmit} validated, so a
     * refused submission re-renders the rerun notice and the hidden {@value RequestRunPrefill#FROM_RERUN}
     * field from the validated id, never from the raw field.
     */
    private static final String RERUN_ATTRIBUTE = JobRequestAction.class.getName() + ".rerunIncident";

    private final Job<?, ?> job;

    public JobRequestAction(Job<?, ?> job) {
        this.job = job;
    }

    public Job<?, ?> getJob() {
        return job;
    }

    /**
     * The job, under the name core's {@code l:job-subpage} reads ({@code it.object}), so the
     * request form renders as a sub-page of the job in both the classic and the new job page.
     */
    public Job<?, ?> getObject() {
        return job;
    }

    // ---------------------------------------------------------------- Action

    @Override
    @CheckForNull
    public String getIconFileName() {
        // e2e-03 DEF-12: the entry is offered only to a user who can submit the form
        // (BatchControl/Request and Item/Read on the job; Item/Build is not needed, D-38a).
        if (!isActive() || !isCanRequestRun()) {
            return null;
        }
        return "symbol-paper-plane-outline plugin-ionicons-api";
    }

    /**
     * Whether the current user may use this action at all: {@code BatchControl/Request} on this
     * job, so a Request assigned on the job or a folder above it counts (D-38a).
     */
    private boolean canRequest() {
        return job.hasPermission(BatchControlPermissions.REQUEST);
    }

    /**
     * Whether the current user may submit a run request for this job: {@code BatchControl/Request}
     * and {@code Item/Read} on the job (D-38a; {@code Item/Build} is not required), the checks
     * {@link RunRequestService#create} makes. View gating only; {@link #doSubmit} and the service
     * check for real.
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
     * Hosting review 2026-10-02: on the new job page the run request is the job's build button,
     * so it sits first in the app bar rather than in the overflow menu. Only where the entry is
     * shown at all ({@link #getIconFileName()}); core's own {@code BuildJobAction} is in the same
     * group and reads {@link RequestRunUiDecorator#BLOCKED_BUILD_LABEL} on a controlled job.
     */
    @Override
    public Group getGroup() {
        return Group.FIRST_IN_APP_BAR;
    }

    /** Rendered as a build action (the build colour) on the new job page; see {@link #getGroup()}. */
    @Override
    public Semantic getSemantic() {
        return Semantic.BUILD;
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

    /**
     * D-66: root-relative URL of the dialog form ({@code dialog.jelly}), which the classic sidebar
     * entry ({@code action.jelly}) opens in core's dialog.
     */
    public String getDialogUrl() {
        return job.getUrl() + "batch-control/dialog";
    }

    /**
     * D-66: on the new job page the entry opens the request form in core's dialog, as core's own
     * "Build with Parameters" does ({@link DialogEvent}); relative to the job's URL. The full page
     * stays for the refused direct build (D-60) and direct links.
     */
    @Override
    public Event getEvent() {
        return DialogEvent.of("batch-control/dialog");
    }

    /**
     * D-66: the check of the dialog view ({@code dialog.jelly}, rendered without a layout): the
     * permission of {@link #getViewPermissions()}, on the job.
     */
    public void checkDialogPermission() {
        job.checkPermission(BatchControlPermissions.REQUEST);
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
     * user entered. Sensitive values (password parameters) and file values are never copied back
     * into the page (D-72: the form says to provide them again, {@link #getReenterParameterNames}).
     */
    public List<ParameterDefinition> getParameterDefinitions() {
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (property == null) {
            return Collections.emptyList();
        }
        List<ParameterDefinition> definitions = property.getParameterDefinitions();
        Object submitted = getFormErrors().getAttachment();
        if (!(submitted instanceof List)) {
            // D-60: a refused build submission redirects here with its values as p.<name>.
            return RequestRunPrefill.apply(definitions,
                    org.kohsuke.stapler.Stapler.getCurrentRequest2());
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
            if (value != null && !value.isSensitive() && !ParameterDisplay.isFile(value)
                    && !(value.getValue() instanceof Secret)) {
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

    /** D-60: whether the form shows values carried from a refused build submission. */
    public boolean isPrefilled() {
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        return property != null && RequestRunPrefill.isPrefilled(property.getParameterDefinitions(),
                org.kohsuke.stapler.Stapler.getCurrentRequest2());
    }

    /**
     * The names of the job's parameters whose values the form never fills in, so the user provides
     * them again (D-72, D-60): file parameters ({@link RequestRunPrefill#isFileDefinition}) and
     * password parameters. Shown on a refused submission, on a refused direct build (D-60; files
     * are not carried, issue #115) and on a rerun that continues here.
     */
    public List<String> getReenterParameterNames() {
        List<String> names = new ArrayList<>();
        for (ParameterDefinition definition : definitions()) {
            if (RequestRunPrefill.isFileDefinition(definition)
                    || definition instanceof PasswordParameterDefinition) {
                names.add(definition.getName());
            }
        }
        return names;
    }

    /** The names of the job's file parameters ({@link RequestRunPrefill#isFileDefinition}). */
    public List<String> getFileParameterNames() {
        List<String> names = new ArrayList<>();
        for (ParameterDefinition definition : definitions()) {
            if (RequestRunPrefill.isFileDefinition(definition)) {
                names.add(definition.getName());
            }
        }
        return names;
    }

    private List<ParameterDefinition> definitions() {
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        return property == null ? Collections.emptyList() : property.getParameterDefinitions();
    }

    /**
     * D-72, D-72a (SPEC item 11): the id of the incident whose rerun continues on this form, or
     * {@code null}. On a GET it comes from {@value RequestRunPrefill#FROM_RERUN}{@code =<id>}; on a
     * refused submission it is the reference {@link #doSubmit} validated (the hidden field of the
     * same name), and nothing is read from the body here. Either way the id counts only when
     * {@link IncidentService#linkableIncident} accepts it (an existing incident of this job, and a
     * viewer holding {@code BatchControl/ViewHistory} who may request a run of the job, the rights
     * of the rerun itself), the same rule {@link #doSubmit} links the request by; anything else is
     * ignored, so a crafted link shows no notice and reveals nothing. The form then says which
     * values must be provided again and that the request will be linked to the incident, and
     * carries the id back in the hidden field; the values that could be recovered arrive as
     * {@code p.<name>} like a refused build's (D-60).
     */
    @CheckForNull
    public String getRerunIncidentId() {
        StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        if (req == null) {
            return null;
        }
        String reference;
        if (getFormErrors().isPresent()) {
            // A refusal: the reference doSubmit validated (none on a body over the size cap, which
            // is never read). Checked again in case the incident went away in the meantime.
            Object validated = req.getAttribute(RERUN_ATTRIBUTE);
            reference = validated instanceof String ? (String) validated : null;
        } else if ("GET".equals(req.getMethod())) {
            reference = req.getParameter(RequestRunPrefill.FROM_RERUN);
        } else {
            reference = null;
        }
        return IncidentService.get().linkableIncident(reference, job);
    }

    /**
     * The reason the form starts with on a rerun that continues here
     * ({@link #getRerunIncidentId()}): the reason the rerun would have generated, editable;
     * otherwise empty. No argument on purpose: Stapler would bind a one-argument getter to a URL.
     */
    public String getRerunReason() {
        String incidentId = getRerunIncidentId();
        if (incidentId == null) {
            return "";
        }
        Incident incident;
        try {
            incident = IncidentService.get().load(incidentId);
        } catch (RuntimeException e) {
            return ""; // unreadable since it was validated: the user writes the reason
        }
        return incident == null ? "" : "Rerun requested from incident " + incidentId
                + " (failed run " + incident.getRunId() + ")";
    }

    /** The refusal of the last submission on this request, or an empty one (DEF-09). */
    public FormErrors getFormErrors() {
        return FormErrors.current(FORM);
    }

    /** Approver candidates: global approver list ∩ job-level restriction (if configured). */
    public List<String> getApproverOptions() {
        return ApproverOptions.forJob(job);
    }

    // ---------------------------------------------------------------- submission

    /**
     * D-58b (3): whether this page offers "Mark as reviewed": the job is in the "changed under a
     * grant" state (itself or through an ancestor, {@link GrantService#isChangedUnderGrant}) and
     * the viewer holds Item/Configure on it. {@link #doMarkReviewed} re-checks, and
     * {@link GrantService#markReviewed} refuses a user whose Configure comes from a grant.
     */
    public boolean isShowMarkReviewed() {
        // S-29-08: nothing is undone while change control is off, so the block would be untrue.
        // S-29-03: offered only to a user who may review it, Configure (or Administer) held
        // natively, as GrantService#markReviewed demands; a grant-only holder sees no button.
        if (!BatchControlGlobalConfiguration.get().isChangeControlEnabled()) {
            return false;
        }
        org.springframework.security.core.Authentication auth = Jenkins.getAuthentication2();
        boolean mayReview = GrantLayer.hasPermissionWithoutGrants(job, auth, Item.CONFIGURE)
                || GrantLayer.hasPermissionWithoutGrants(Jenkins.get(), auth, Jenkins.ADMINISTER);
        return mayReview && GrantService.get().isChangedUnderGrant(job);
    }

    /** D-58c: the runs of this job replayed under a permission window, for the review section. */
    public List<ReplayedRuns.Row> getMarkedRuns() {
        return ReplayedRuns.of(job);
    }

    /**
     * POST {@code markReviewed} (D-58b (3)): the deliberate review of this job after changes made
     * under a permission window. {@link GrantService#markReviewed} refuses a user whose Configure
     * comes from a grant and writes the {@code GUARD_REVIEWED} record; this page only asks for
     * Item/Configure first. Redirects back to this page with a short confirmation.
     */
    @RequirePOST
    public HttpResponse doMarkReviewed() {
        job.checkPermission(Item.CONFIGURE);
        GrantService.get().markReviewed(job);
        return HttpResponses.redirectTo(".?reviewed=1");
    }

    /** Whether the page shows the "marked as reviewed" confirmation (constant text only). */
    public boolean isReviewedNotice() {
        // S-29-08: the parameter alone is not trusted; the notice only states what is true now.
        // GET only: the redirect of doMarkReviewed. On a refused submission (a POST) reading a
        // parameter would parse a multipart body, which a body over the D-72 cap must not be.
        StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        return req != null && "GET".equals(req.getMethod()) && "1".equals(req.getParameter("reviewed"))
                && !GrantService.get().isChangedUnderGrant(job);
    }

    /**
     * POST {@code submit} — creates the run request and redirects to its detail page at
     * {@code /batch-control/requests/<id>/}. A refused submission re-renders the form with HTTP 400,
     * the message next to the field it concerns and the user's input kept (e2e-03 DEF-09).
     *
     * <p>D-72: the form posts {@code multipart/form-data}, so file parameters are uploaded with it,
     * and the request keeps the submitted values with their types ({@link RunRequestService#create
     * create(Job, List, String, List)}). Because requesting a run does not require
     * {@code Item/Build} (D-38a), the body size is checked right after the permission checks and
     * before this endpoint reads the body ({@link RequestBodyLimit}): a body over the cap is
     * answered with HTTP 413 and the empty form; nothing of it is read here and nothing is created
     * or kept. (Core's own dispatch to this URL may already have parsed a multipart body:
     * {@code Job#getDynamic} builds the job's widgets and {@code HistoryWidget} reads a paging
     * parameter. That happens for every URL under a job, before any plugin code, and is bounded
     * only by Stapler's {@code org.kohsuke.stapler.RequestImpl.FILEUPLOAD_MAX_*} properties.)
     * When the form's own
     * checks refuse a submission, the temporary files of its file values are disposed of here
     * ({@link ParameterFiles}); once the values are handed to the service, it disposes of them on
     * its own refusals. The re-rendered form never shows a password or a file again.
     *
     * <p>D-72a (SPEC item 11): a submission from the form an incident rerun fell back to carries
     * the incident id in the field {@value RequestRunPrefill#FROM_RERUN}. It is read after the
     * permission and size checks, passed through {@link IncidentService#linkableIncident} and only
     * the id that check returns goes to {@link RunRequestService#create(Job, List, String, List,
     * String) create}, which links the request so that a successful run records
     * {@code resolvedByRunId}; an invalid reference is ignored. A refused submission keeps the
     * validated reference on the re-rendered form.
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
     * stored values are complete either way.
     */
    @RequirePOST
    public void doSubmit(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        job.checkPermission(Item.READ);
        // D-38a: Request is checked on the requested job (assigned there, on a folder or globally).
        job.checkPermission(BatchControlPermissions.REQUEST);
        // D-38a: Item/Build is not required to request a run; the approval decides.

        // D-72: the body size cap, from the headers alone, before anything reads the body
        // (getParameter and getSubmittedForm parse a multipart body, file uploads included).
        if (RequestBodyLimit.exceeds(req)) {
            refuseOversizedBody(req, rsp);
            return;
        }

        // The rendered form posts a json blob (f:form) plus the raw fields; a script may post
        // the raw fields only. Both carry the same contract: reason, repeated approvers (D-37).
        JSONObject formData = req.getParameter("json") != null ? req.getSubmittedForm() : null;
        String reason = Util.fixEmptyAndTrim(formData != null
                ? formData.optString("reason", "") : Util.fixNull(req.getParameter("reason")));
        // D-72a (SPEC item 11): the incident reference the rerun fallback form carries back. The
        // request is linked only to what IncidentService#linkableIncident accepts; a missing,
        // malformed, unknown or foreign reference makes an unlinked request, never an error.
        String rerunIncident = IncidentService.get().linkableIncident(rerunReference(req, formData), job);

        // e2e-03 DEF-09: every refusal of the user's input is shown on the form, next to the
        // field, with the input kept (FormErrors), instead of a bare "Error" page.
        FormErrors errors = new FormErrors(FORM);
        List<ParameterValue> submitted = new ArrayList<>();
        boolean handedOver = false;
        try {
            List<String> approvers = List.of();
            try {
                approvers = ApproverInput.read(req, formData);
            } catch (Failure e) {
                errors.field("approvers", e.getMessage());
            }
            try {
                if (formData == null) {
                    parseRawParameters(req, submitted);
                } else {
                    parseParameters(req, formData, submitted);
                }
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
                // From here the service owns the values' temporary files, on a refusal too.
                handedOver = true;
                try {
                    RunRequest request = RunRequestService.get().create(job, submitted, reason, approvers,
                            rerunIncident);
                    rsp.sendRedirect2(req.getContextPath() + "/batch-control/requests/"
                            + Util.rawEncode(request.getId()) + "/");
                    return;
                } catch (IllegalArgumentException | IllegalStateException e) {
                    errors.fromService(e.getMessage(), "reason", "reason", "approver", "approvers",
                            "parameter", "parameters");
                }
            }
        } finally {
            if (!handedOver) {
                // D-72: refused by this form before the service saw the values (or parsing
                // failed half-way): their uploaded files are not kept.
                ParameterFiles.dispose(submitted, "a refused run request form for job '" + job.getFullName() + "'");
            }
        }
        if (rerunIncident != null) {
            // D-72a: the re-rendered form keeps the validated reference (getRerunIncidentId).
            req.setAttribute(RERUN_ATTRIBUTE, rerunIncident);
        }
        // D-66: a refusal is shown where the form was, in the dialog or on this page.
        errors.attach(submitted).render(req, rsp, this, Dialogs.refusalView(req, "index.jelly"));
    }

    /**
     * D-72a: the raw {@value RequestRunPrefill#FROM_RERUN} field of a submission, unvalidated: the
     * plain field (the rendered form's hidden input, or a script's field), else the same name in
     * the {@code json} blob. Only ever passed to {@link IncidentService#linkableIncident}.
     */
    @CheckForNull
    private static String rerunReference(StaplerRequest2 req, @CheckForNull JSONObject formData) {
        String raw = req.getParameter(RequestRunPrefill.FROM_RERUN);
        if (raw == null && formData != null) {
            Object value = formData.opt(RequestRunPrefill.FROM_RERUN);
            raw = value instanceof String ? (String) value : null;
        }
        return raw;
    }

    /**
     * D-72: answers a submission whose body is over the cap with HTTP 413 and the request form,
     * empty, with a message saying why: nothing of the body is read (the view never reads the
     * submitted fields, {@link FormErrors#withoutInput()}), so no request is created and no
     * uploaded file is stored. The dialog is recognised by its action's query string
     * ({@link Dialogs#fromDialogQuery}), never by a body field.
     */
    private void refuseOversizedBody(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        FormErrors errors = new FormErrors(FORM).withoutInput().message(
                "The request was not submitted: it is larger than the limit of "
                + sizeText(RequestBodyLimit.maxRequestBodyBytes())
                + " for a run request (or does not declare its size). Nothing was saved, and what"
                + " you entered could not be kept. Fill in the form again with smaller files, or"
                + " ask a Jenkins administrator to raise the limit.");
        errors.render(req, rsp, this,
                Dialogs.fromDialogQuery(req) ? Dialogs.DIALOG_VIEW : "index.jelly",
                HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
    }

    /** {@code bytes} as text for the size cap message: whole MB, KB or bytes, rounded down. */
    static String sizeText(long bytes) {
        long mega = 1024L * 1024L;
        if (bytes >= mega && bytes % mega == 0) {
            return bytes / mega + " MB";
        }
        if (bytes >= 1024L && bytes % 1024L == 0) {
            return bytes / 1024L + " KB";
        }
        return String.format(java.util.Locale.ROOT, "%,d bytes", bytes);
    }

    /**
     * Parses the {@code parameter} JSON array the same way core's
     * {@code ParametersDefinitionProperty._doBuild} does (each entry goes through the parameter
     * definition's {@code createValue}, file parameters reading their upload from the multipart
     * body) and adds the typed values to {@code submitted} (D-72: kept as they are, not flattened).
     *
     * <p>Throws {@link Failure} for a parameter name the job does not define, and lets a
     * definition's own {@link IllegalArgumentException} propagate for a value it refuses; the
     * caller turns the latter into the same 400 as every other rejected submission (N-01).
     */
    private void parseParameters(StaplerRequest2 req, JSONObject formData, List<ParameterValue> submitted) {
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (property == null) {
            return;
        }
        Object parameter = formData.opt("parameter");
        if (parameter == null) {
            return;
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
            }
        }
    }

    /**
     * Parses parameter values for a scripted submission that carries no {@code json} form field
     * (security-08 S-10). Each of the job's defined parameters reads its own raw request field
     * through {@link ParameterDefinition#createValue(StaplerRequest2)} — the same call core's
     * {@code buildWithParameters} makes — falling back explicitly to
     * {@link ParameterDefinition#getDefaultParameterValue()} when the definition itself returns
     * {@code null} for a missing field, so the stored values are complete rather than empty. A
     * definition's own {@link IllegalArgumentException} for a bad value propagates unchanged
     * (N-01: caught by the caller's {@code try}).
     */
    private void parseRawParameters(StaplerRequest2 req, List<ParameterValue> submitted) {
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (property == null) {
            return;
        }
        for (ParameterDefinition definition : property.getParameterDefinitions()) {
            ParameterValue value = definition.createValue(req);
            if (value == null) {
                value = definition.getDefaultParameterValue();
            }
            if (value != null) {
                submitted.add(value);
            }
        }
    }
}
