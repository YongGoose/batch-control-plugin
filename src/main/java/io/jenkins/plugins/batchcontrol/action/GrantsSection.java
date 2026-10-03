package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.model.Failure;
import hudson.model.Item;
import hudson.model.ModelObject;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.CreateNamePattern;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.Store;
import io.jenkins.plugins.batchcontrol.ui.ApproverInput;
import io.jenkins.plugins.batchcontrol.ui.ApproverOptions;
import io.jenkins.plugins.batchcontrol.ui.Dates;
import io.jenkins.plugins.batchcontrol.ui.FormErrors;
import io.jenkins.plugins.batchcontrol.ui.Visibility;
import io.jenkins.plugins.batchcontrol.ui.HttpVerbs;
import io.jenkins.plugins.batchcontrol.ui.Paging;
import io.jenkins.plugins.batchcontrol.ui.SectionAccess;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerProxy;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * The grant screens at {@code /batch-control/grants/} (SPEC item 8, JIT permission requests).
 *
 * <p>URL space (fixed contract, asserted by tests):
 * <ul>
 *   <li>{@code /batch-control/grants/} — grant request list + active grant list + new request
 *       form (served from {@code index.jelly}; PUT/DELETE/PATCH get 405)</li>
 *   <li>{@code POST /batch-control/grants/create} — submit a new grant request</li>
 *   <li>{@code /batch-control/grants/<id>/} — request detail; POST {@code approve} /
 *       {@code reject} / {@code cancel} (see {@link GrantRequestItem})</li>
 *   <li>{@code POST /batch-control/grants/active/<grantId>/revoke} — revoke an active grant
 *       (see {@link ActiveGrantsSection})</li>
 * </ul>
 *
 * <p>No state transition logic lives here; everything is delegated to
 * {@link GrantRequestService} and {@link GrantService}. Viewing requires one of the plugin
 * permissions (requesters see their requests, approvers their inbox, managers the active
 * grants), and the whole subtree additionally closes while change control is off — both enforced
 * by {@link #getTarget()}.
 *
 * <p>The switch closes this screen and nothing else. It must never reach the audit trail: the
 * Change Records and History screens keep showing the windows that existed and the changes made
 * under them, because a switch flip that retroactively hid audit records would be a worse defect
 * than the one the gate fixes.
 */
@Restricted(NoExternalUse.class)
public class GrantsSection implements ModelObject, StaplerProxy {

    /** {@link FormErrors} name of the new-request form. */
    static final String CREATE_FORM = "create";

    /** Page size for both the request list and the active grant list. */
    public static final int PAGE_SIZE = Paging.PAGE_SIZE;

    /**
     * What a caller is told when they reach this screen while change control is off (P-15).
     *
     * <p>It names the switch and where it lives on purpose. The alternative — 404 or a bare 403 —
     * is the U-01 failure recreated one screen along: a user following a link they were given
     * yesterday would have no way to tell which of "I lost a permission", "the screen moved" and
     * "an administrator turned something off" had happened. The closing sentence exists because
     * the switch going off revokes every open window, so the natural next question is whether the
     * record of them survived; it does.
     */
    private static final String CHANGE_CONTROL_OFF_MESSAGE =
            "Change control is off, so this instance is not using permission windows and the "
            + "Grants screen is closed. While the switch is off a window confers nothing, and a "
            + "window approved now would take effect unreviewed the moment it was turned back on, "
            + "so no window can be requested or approved either. An administrator can turn it on "
            + "with \"Enable change control\" in the Batch Control section of Manage Jenkins > "
            + "System. Nothing has been deleted from the audit trail: windows that existed, and "
            + "the changes made under them, are still on the Change Records and History screens.";

    /** Lazily computed, per-request cached sorted snapshot of grant requests. */
    private List<GrantRequest> sortedRequests;

    /** Lazily computed, per-request cached sorted snapshot of active grants. */
    private List<Grant> sortedActive;

    /** Lazily computed, per-request cached sorted snapshot of ended grants (D-33). */
    private List<Grant> sortedPast;

    /** Per-request cache of every stored grant by id. */
    private Map<String, Grant> grantsById;

    @Override
    public Object getTarget() {
        // Gate the entire /batch-control/grants/** subtree (list, details, POST endpoints go
        // through their own additional checks below and in GrantRequestItem/ActiveGrantsSection).
        Jenkins.get().checkAnyPermission(SectionAccess.grants());
        HttpVerbs.refuseUnsupported();
        // P-15 / SPEC item 1: with change control off, no change-control UI may appear. This whole
        // subtree is change-control UI, so it closes with the switch. GrantRequestService refuses
        // create and approve independently — this is the screen half of the same gate, not a
        // replacement for it, and the permission check above stays first so only a caller who
        // would otherwise be let in learns which switch is off.
        if (!BatchControlGlobalConfiguration.get().isChangeControlEnabled()) {
            recordRefusedCreate();
            throw new Failure(CHANGE_CONTROL_OFF_MESSAGE);
        }
        return this;
    }

    /**
     * e2e-03 DEF-27: a grant request POSTed while change control is off never reaches
     * {@link #doCreate}, because this whole subtree is closed. It is still a refused request, so
     * the policy layer records it ({@link GrantRequestService#refuseRequestWhileChangeControlOff});
     * the caller then gets the screen's explanation as before. Only a POST to {@code create}
     * counts: opening the closed screen is not a request. The permission check of
     * {@link #getTarget()} has already passed, and the CSRF crumb filter runs before Stapler.
     */
    private static void recordRefusedCreate() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null || !"POST".equals(req.getMethod())) {
            return;
        }
        String rest = req.getRestOfPath();
        if (rest == null || !(rest.equals("/create") || rest.equals("/create/"))) {
            return;
        }
        String scope = Util.fixEmptyAndTrim(req.getParameter("scopeFullName"));
        if (scope != null && scope.length() > MAX_RECORDED_SCOPE_LENGTH) {
            scope = scope.substring(0, MAX_RECORDED_SCOPE_LENGTH);
        }
        try {
            GrantRequestService.get().refuseRequestWhileChangeControlOff(scope);
        } catch (IllegalStateException expected) {
            // Recorded; the caller gets CHANGE_CONTROL_OFF_MESSAGE from getTarget().
        }
    }

    /** Bound on the user-supplied scope name written into the refusal record. */
    private static final int MAX_RECORDED_SCOPE_LENGTH = 1000;

    @Override
    public String getDisplayName() {
        return "Grants";
    }


    /** Permissions for this screen's {@code l:layout} (the same set its section gate checks). */
    public Permission[] getViewPermissions() {
        return SectionAccess.grants();
    }

    /** Link predicates: a link to another screen is rendered only if the user may open it. */
    public SectionAccess getLinks() {
        return new SectionAccess();
    }

    // ---------------------------------------------------------------- routing

    /**
     * Stapler: serves {@code /batch-control/grants/<id>/}; {@code null} renders a 404.
     * A grant request the caller may not see (P-09, S-01) renders exactly like a nonexistent
     * one so its existence is not disclosed.
     */
    @CheckForNull
    public GrantRequestItem getDynamic(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        GrantRequest request;
        try {
            request = GrantRequestService.get().load(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (request == null || !Visibility.canSeeGrantRequest(request)) {
            return null;
        }
        return new GrantRequestItem(request);
    }

    /** Stapler: serves {@code /batch-control/grants/active/...} (revoke endpoints). */
    public ActiveGrantsSection getActive() {
        return new ActiveGrantsSection();
    }

    // ---------------------------------------------------------------- create endpoint

    /**
     * POST {@code /batch-control/grants/create} — submits a new grant request. This method only
     * parses HTTP input; all business validation (action set non-empty, duration cap, approver
     * eligibility, reason required) is enforced by {@link GrantRequestService#create}.
     *
     * <p>Form fields: {@code scopeType} (JOB/FOLDER), {@code scopeFullName}, {@code actions}
     * (multi-valued checkboxes), {@code durationMinutes} (preset select),
     * {@code customDurationMinutes} (optional free number overriding the preset, capped
     * client-side at {@code maxGrantMinutes} and re-checked by the service), {@code reason},
     * {@code approvers} (repeated, one user id each; D-37) and the optional
     * {@code createNamePattern} (exact name or {@code /regex/}, CREATE only; D-40).
     */
    @RequirePOST
    public void doCreate(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(BatchControlPermissions.REQUEST_GRANT);

        // e2e-03 DEF-09: every refusal of the user's input is shown on this screen next to the
        // field it concerns, with the input kept (FormErrors), instead of a bare "Error" page.
        FormErrors errors = new FormErrors(CREATE_FORM);
        GrantScope scope = parseScope(req.getParameter("scopeType"), req.getParameter("scopeFullName"),
                errors);
        List<GrantAction> actions = parseActions(req.getParameterValues("actions"), errors);
        int durationMinutes = parseDuration(
                req.getParameter("durationMinutes"), req.getParameter("customDurationMinutes"), errors);
        String reason = req.getParameter("reason");
        if (reason == null || reason.trim().isEmpty()) {
            errors.field("reason", "Enter a reason: the approvers decide on it.");
        }
        List<String> approvers = List.of();
        try {
            approvers = ApproverInput.read(req, null);
        } catch (Failure e) {
            errors.field("approvers", e.getMessage());
        }
        if (approvers.isEmpty()) {
            errors.field("approvers", "Check at least one approver.");
        }
        String createNamePattern = parseCreateNamePattern(req.getParameter("createNamePattern"), errors);

        if (errors.isEmpty()) {
            try {
                GrantRequest created = GrantRequestService.get().create(scope, actions, durationMinutes,
                        reason, approvers, createNamePattern);
                // Backlog #89: land on the new request's detail page, like run and activation
                // requests (the requester may always see their own request, P-09).
                rsp.sendRedirect2(req.getContextPath() + "/batch-control/grants/"
                        + Util.rawEncode(created.getId()) + "/");
                return;
            } catch (IllegalArgumentException | IllegalStateException e) {
                errors.fromService(e.getMessage(), "name restriction", "createNamePattern",
                        "duration", "customDurationMinutes", "reason", "reason",
                        "approver", "approvers", "action", "actions", "no such", "scopeFullName",
                        "scope", "scopeFullName");
            }
        }
        errors.render(req, rsp, this);
    }

    /** The refusal of the new-request form on this request, or an empty one (DEF-09, Jelly). */
    public FormErrors getFormErrors() {
        return FormErrors.current(CREATE_FORM);
    }

    // ---------------------------------------------------------------- form input parsing

    @CheckForNull
    private static GrantScope parseScope(@CheckForNull String rawType, @CheckForNull String rawFullName,
                                         FormErrors errors) {
        GrantScope.Type type = null;
        try {
            type = rawType == null ? null : GrantScope.Type.valueOf(rawType.trim());
        } catch (IllegalArgumentException e) {
            type = null;
        }
        if (type == null) {
            errors.field("scopeType", "Choose Job or Folder.");
        }
        String fullName = rawFullName == null ? "" : rawFullName.trim();
        if (fullName.isEmpty()) {
            errors.field("scopeFullName",
                    "Enter the full name of the job or folder, for example team/nightly-report.");
        }
        return type == null || fullName.isEmpty() ? null : new GrantScope(type, fullName);
    }

    private static List<GrantAction> parseActions(@CheckForNull String[] raw, FormErrors errors) {
        List<GrantAction> actions = new ArrayList<>();
        if (raw != null) {
            for (String value : raw) {
                try {
                    actions.add(GrantAction.valueOf(value.trim()));
                } catch (IllegalArgumentException e) {
                    errors.field("actions", "Only Create, Configure and Delete can be requested.");
                }
            }
        }
        if (actions.isEmpty()) {
            errors.field("actions", "Check at least one action.");
        }
        return actions;
    }

    /**
     * Blank means no restriction. Only the length is bounded here, before the text reaches the
     * regex compiler; syntax, item-name validity and "CREATE only" are the service's to refuse.
     */
    @CheckForNull
    private static String parseCreateNamePattern(@CheckForNull String raw, FormErrors errors) {
        String pattern = CreateNamePattern.normalize(raw);
        if (pattern != null && pattern.length() > CreateNamePattern.MAX_LENGTH) {
            errors.field("createNamePattern", "The name restriction must not exceed "
                    + CreateNamePattern.MAX_LENGTH + " characters.");
        }
        return pattern;
    }

    /**
     * The requested window length. The upper bound is checked here too so the message names the
     * limit in the user's terms (e2e-03 DEF-09 quoted the service's internal setting name); the
     * service re-checks it.
     */
    private static int parseDuration(@CheckForNull String preset, @CheckForNull String custom,
                                     FormErrors errors) {
        boolean customSet = custom != null && !custom.trim().isEmpty();
        String field = customSet ? "customDurationMinutes" : "durationMinutes";
        String raw = customSet ? custom : preset;
        if (raw == null || raw.trim().isEmpty()) {
            errors.field(field, "Choose a duration.");
            return 0;
        }
        int minutes;
        try {
            minutes = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            errors.field(field, "Enter the duration as a whole number of minutes.");
            return 0;
        }
        int max = BatchControlGlobalConfiguration.get().getMaxGrantMinutes();
        if (minutes < 1) {
            errors.field(field, "The duration must be at least 1 minute.");
        } else if (minutes > max) {
            errors.field(field, "At most " + max + " minutes can be requested. Ask for a shorter "
                    + "window, and request a new one when it ends if you need more time.");
        }
        return minutes;
    }

    // ---------------------------------------------------------------- view model (used from Jelly)

    /** View gating for the new-request form; {@link #doCreate} re-checks for real. */
    public boolean isCanRequest() {
        return Jenkins.get().hasPermission(BatchControlPermissions.REQUEST_GRANT);
    }

    /** View gating for the Revoke buttons; the revoke endpoint re-checks for real. */
    public boolean isCanRevoke() {
        return Jenkins.get().hasPermission(BatchControlPermissions.MANAGE);
    }

    /** Preset duration choices from the global configuration. */
    public List<Integer> getDurationOptions() {
        return BatchControlGlobalConfiguration.get().getGrantDurationOptions();
    }

    /** Maximum length of the CREATE name restriction, for the form's {@code maxlength}. */
    public int getCreateNamePatternMaxLength() {
        return CreateNamePattern.MAX_LENGTH;
    }

    /** Upper bound in minutes for a grant request (client-side cap; the service re-checks). */
    public int getMaxGrantMinutes() {
        return BatchControlGlobalConfiguration.get().getMaxGrantMinutes();
    }

    /** Approver candidates (global list minus the current user, per the self-approval policy). */
    public List<String> getApproverOptions() {
        return ApproverOptions.forJob(null);
    }

    /** Jelly helper: human-readable timestamp. */
    public String format(Instant instant) {
        return Dates.format(instant);
    }

    /**
     * Jelly helper: how much of a grant window is left ({@code 12 min 30 sec}), so the user does
     * not have to subtract the absolute expiry time from the current time (UX-11).
     *
     * @return the remaining span, or {@code "expired"} once the window has closed (a grant can
     *         still be listed for the moment between expiry and the sweeper run)
     */
    public String remaining(@CheckForNull Instant expiresAt) {
        return Dates.until(expiresAt);
    }

    /** Jelly helper: {@code 1 minute} / {@code 15 minutes} for the duration select (UX-12). */
    public String minutesLabel(int minutes) {
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }

    // ------------------------------------------------------- new-request prefill (U-01)

    /**
     * Scope type the new-request form starts on: the renewed grant's (D-33, {@code ?from=}),
     * else {@code ?scopeType=}, else {@code JOB}.
     *
     * <p>The value is matched against {@link GrantScope.Type} and anything else falls back to
     * {@code JOB}, so a hand-edited query string cannot put an unknown string into the form.
     */
    public String getPrefillScopeType() {
        Grant source = getRenewSource();
        if (source != null) {
            return source.getScope().getType().name();
        }
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        String raw = req == null ? null : req.getParameter("scopeType");
        if (raw != null) {
            try {
                return GrantScope.Type.valueOf(raw.trim()).name();
            } catch (IllegalArgumentException ignored) {
                // Fall through to the default.
            }
        }
        return GrantScope.Type.JOB.name();
    }

    /**
     * Full name the new-request form starts with: the renewed grant's scope (D-33), else
     * {@code ?scopeFullName=} — how {@link JobGrantRequestAction} hands the job over, so a user
     * who arrives from a job page does not have to retype its path (U-01).
     *
     * <p>The parameter is <em>resolved</em>, never echoed: the name is looked up through
     * {@link Visibility#findVisibleItem} and what the form receives is the model object's own
     * {@code getFullName()}. An item that does not exist, or that the caller cannot see, yields
     * an empty field, so this cannot be used to reflect arbitrary text into the page or to probe
     * for names — it renders exactly what a caller could already read from the item's own URL.
     * A renewed grant is likewise resolved from the store, and only the caller's own.
     *
     * @return the canonical full name, or an empty string when there is nothing to prefill
     */
    public String getPrefillScopeFullName() {
        Grant source = getRenewSource();
        if (source != null) {
            return source.getScope().getFullName();
        }
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        String raw = req == null ? null : req.getParameter("scopeFullName");
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        Item item = Visibility.findVisibleItem(raw.trim());
        return item == null ? "" : item.getFullName();
    }

    /**
     * Whether the form was opened for a specific item: from a job's "Request Change Permission"
     * entry (CONFIGURE then starts checked, the user is trying to change that job) or from the
     * re-request link of an ended grant (its actions start checked, D-33).
     */
    public boolean isPrefilled() {
        return !getPrefillScopeFullName().isEmpty();
    }

    /** The ended grant the form renews ({@code ?from=<grantId>}), or {@code null}. */
    @CheckForNull
    public Grant getRenewSource() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        String raw = req == null ? null : Util.fixEmptyAndTrim(req.getParameter("from"));
        if (raw == null) {
            return null;
        }
        Grant grant = grantsById().get(raw);
        // Only the caller's own grant: a window is requested for oneself.
        return grant != null && isOwn(grant) ? grant : null;
    }

    /**
     * Whether the action checkbox starts checked: the renewed grant's actions; else the
     * {@code ?actions=} values (matched against {@link GrantAction}, how a refusal such as the
     * delete veto hands over the action it needs, DEF-26); else CONFIGURE when the form was
     * opened for an item.
     */
    public boolean isPrefillAction(String action) {
        Grant source = getRenewSource();
        if (source != null) {
            return source.getActions().stream().anyMatch(a -> a.name().equals(action));
        }
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        String[] requested = req == null ? null : req.getParameterValues("actions");
        if (requested != null && isPrefilled()) {
            for (String value : requested) {
                if (value != null && value.trim().equals(action)) {
                    return true;
                }
            }
            return false;
        }
        return isPrefilled() && GrantAction.CONFIGURE.name().equals(action);
    }

    /** The renewed grant's CREATE name restriction, or empty. */
    public String getPrefillCreateNamePattern() {
        Grant source = getRenewSource();
        return source == null ? "" : Util.fixNull(source.getCreateNamePattern());
    }

    /**
     * Whether the preset {@code minutes} starts selected: the renewed grant's length when it is
     * one of the presets. Otherwise none is marked and the browser shows the first option.
     */
    public boolean isPrefillDuration(int minutes) {
        Grant source = getRenewSource();
        return source != null && minutesOf(source) == minutes;
    }

    // ------------------------------------------------------- ended grants (D-33, DEF-18)

    /** Current 1-based page of the ended-grant table, from {@code pastPage}. */
    public int getPastPage() {
        return Paging.currentPage("pastPage");
    }

    /**
     * The ended (expired or revoked) grants shown on the current page, most recently ended
     * first. SPEC item 8 (D-33): the permission screen gives the history of expired windows and a
     * re-request link. Same visibility as the active table (own grants, or all with Manage).
     */
    public List<Grant> getPastPageItems() {
        return Paging.slice(allPastSorted(), getPastPage());
    }

    public int getPastTotal() {
        return allPastSorted().size();
    }

    public boolean isHasPastPrevious() {
        return Paging.hasPrevious(getPastPage());
    }

    public boolean isHasPastNext() {
        return Paging.hasNext(getPastPage(), getPastTotal());
    }

    /** How a grant ended, in words: "Expired" or "Revoked by admin". */
    public String endedLabel(Grant grant) {
        if (grant.getRevokedAt() != null) {
            return grant.getRevokedBy() == null ? "Revoked" : "Revoked by " + grant.getRevokedBy();
        }
        return "Expired";
    }

    /** When a grant ended: its revocation time, else its expiry. */
    public Instant endedAt(Grant grant) {
        return grant.getRevokedAt() != null ? grant.getRevokedAt() : grant.getExpiresAt();
    }

    /** Whether the re-request link is shown: the caller's own grant and may request windows. */
    public boolean isCanRenew(Grant grant) {
        return isCanRequest() && isOwn(grant);
    }

    /**
     * The status column of the request table. An APPROVED request whose window has ended says so
     * (DEF-18: a 1-minute window was listed as APPROVED long after it expired).
     */
    public String statusLabel(GrantRequest request) {
        String detail = statusDetail(request);
        return detail.isEmpty() ? String.valueOf(request.getStatus())
                : request.getStatus() + " (" + detail + ")";
    }

    /**
     * Backlog #89: the window state of an approved request, without the status word
     * ("window open, 2h left", "window revoked", "window expired"), or an empty string. The list
     * shows it below the status so the status column stays narrow at 1280 px.
     */
    public String statusDetail(GrantRequest request) {
        if (request.getStatus() != RequestStatus.APPROVED) {
            return "";
        }
        Grant grant = grantsById().get(request.getId());
        if (grant == null) {
            return "";
        }
        if (grant.getRevokedAt() != null) {
            return "window revoked";
        }
        return grant.isActiveAt(BatchClock.now())
                ? "window open, " + Dates.until(grant.getExpiresAt()) + " left"
                : "window expired";
    }

    /**
     * The CREATE name restriction of a grant in words (DEF-19): the pattern, "any name" for an
     * unrestricted CREATE, empty when the grant does not include CREATE.
     */
    public String createNameLabel(Grant grant) {
        if (!grant.getActions().contains(GrantAction.CREATE)) {
            return "";
        }
        String pattern = grant.getCreateNamePattern();
        return pattern == null ? "any name" : pattern;
    }

    /** Jelly helper: comma-joined action list ("CREATE, CONFIGURE"). */
    public String join(Collection<?> items) {
        return items == null
                ? ""
                : items.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    // ---------------------------------------------------------------- paging: grant requests

    /** Current 1-based page of the request table, from the {@code page} query parameter. */
    public int getPage() {
        return Paging.currentPage();
    }

    /** The grant requests shown on the current page, newest first. */
    public List<GrantRequest> getPageItems() {
        return Paging.slice(allRequestsSorted(), getPage());
    }

    public int getTotal() {
        return allRequestsSorted().size();
    }

    public boolean isHasPrevious() {
        return Paging.hasPrevious(getPage());
    }

    public boolean isHasNext() {
        return Paging.hasNext(getPage(), getTotal());
    }

    // ---------------------------------------------------------------- paging: active grants

    /** Current 1-based page of the active grant table, from {@code activePage}. */
    public int getActivePage() {
        return Paging.currentPage("activePage");
    }

    /** The active grants shown on the current page, newest first. */
    public List<Grant> getActivePageItems() {
        return Paging.slice(allActiveSorted(), getActivePage());
    }

    public int getActiveTotal() {
        return allActiveSorted().size();
    }

    public boolean isHasActivePrevious() {
        return Paging.hasPrevious(getActivePage());
    }

    public boolean isHasActiveNext() {
        return Paging.hasNext(getActivePage(), getActiveTotal());
    }

    // ---------------------------------------------------------------- helpers

    private List<GrantRequest> allRequestsSorted() {
        if (sortedRequests == null) {
            // P-09 visibility (S-01): only Manage, the requester or the designated approver see
            // a grant request; paging runs over the filtered list. Same predicate as the detail.
            List<GrantRequest> all = new ArrayList<>();
            for (GrantRequest request : GrantRequestService.get().list()) {
                if (Visibility.canSeeGrantRequest(request)) {
                    all.add(request);
                }
            }
            all.sort(Comparator.comparing(GrantRequest::getCreatedAt)
                    .thenComparing(GrantRequest::getId)
                    .reversed());
            sortedRequests = all;
        }
        return sortedRequests;
    }

    private static boolean isOwn(Grant grant) {
        return Jenkins.getAuthentication2().getName().equals(grant.getUser());
    }

    private static long minutesOf(Grant grant) {
        return java.time.Duration.between(grant.getGrantedAt(), grant.getExpiresAt()).toMinutes();
    }

    /** Every stored grant by id, once per rendering (the store reads one file per grant). */
    private Map<String, Grant> grantsById() {
        if (grantsById == null) {
            Map<String, Grant> byId = new LinkedHashMap<>();
            for (Grant grant : Store.get().listGrants()) {
                byId.put(grant.getId(), grant);
            }
            grantsById = byId;
        }
        return grantsById;
    }

    private List<Grant> allPastSorted() {
        if (sortedPast == null) {
            Instant now = BatchClock.now();
            List<Grant> past = new ArrayList<>();
            for (Grant grant : grantsById().values()) {
                if (!grant.isActiveAt(now) && !grant.getGrantedAt().isAfter(now)
                        && Visibility.canSeeGrant(grant)) {
                    past.add(grant);
                }
            }
            past.sort(Comparator.comparing(this::endedAt).thenComparing(Grant::getId).reversed());
            sortedPast = past;
        }
        return sortedPast;
    }

    private List<Grant> allActiveSorted() {
        if (sortedActive == null) {
            // P-09 visibility (S-01): the active grant table shows only the caller's own grants
            // unless the caller has Manage (who holds what where is recon data).
            List<Grant> all = new ArrayList<>();
            for (Grant grant : GrantService.get().listActive()) {
                if (Visibility.canSeeGrant(grant)) {
                    all.add(grant);
                }
            }
            all.sort(Comparator.comparing(Grant::getGrantedAt)
                    .thenComparing(Grant::getId)
                    .reversed());
            sortedActive = all;
        }
        return sortedActive;
    }
}
