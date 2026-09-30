package io.jenkins.plugins.batchcontrol.config;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.listeners.SaveableListener;
import hudson.security.Permission;
import hudson.util.FormValidation;
import io.jenkins.plugins.batchcontrol.Messages;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

/**
 * Global plugin configuration (SPEC section 5 keys and defaults). Nothing is active until an
 * administrator turns a switch on; toggling either switch writes a
 * {@code ChangeRecord(CONFIG_TOGGLE)} to the store (SPEC item 1).
 *
 * <p>e2e-03 DEF-08: the web form is validated before anything is bound. Each field has a
 * {@code doCheck*} method, so the message appears next to the field while typing, and
 * {@link #configure} refuses a submission holding an invalid value with a
 * {@link hudson.model.Descriptor.FormException} naming that field; nothing is saved then. The
 * setters (JCasC, scripts) still ignore non-positive values, so an invalid value can never be
 * persisted on any path.
 *
 * <p>e2e-03 DEF-10: {@code BatchControl/Manage} is enough to open and save this configuration
 * ({@link #getRequiredGlobalConfigPagePermission()}, and {@link BatchControlConfigurationLink} for
 * a holder who cannot open core's system configuration page).
 */
@Extension
@Symbol("batchControl")
@Restricted(NoExternalUse.class) // configured via the global form / JCasC, not a code-level API
public class BatchControlGlobalConfiguration extends GlobalConfiguration {

    private static final Logger LOGGER = Logger.getLogger(BatchControlGlobalConfiguration.class.getName());

    // volatile: read lock-free by the queue gate and the ACL on other threads; written under
    // this instance's monitor (#19).
    private volatile boolean runControlEnabled;
    private volatile boolean changeControlEnabled;
    private List<String> approvers = new ArrayList<>();
    private boolean allowAdminSelfApproval = true;
    private int pendingTimeoutHours = 72;
    private int approvedRunTimeoutMinutes = 60;
    private List<Integer> grantDurationOptions = new ArrayList<>(Arrays.asList(15, 30, 60));
    private int maxGrantMinutes = 240;
    private List<String> incidentResults = new ArrayList<>(Arrays.asList("FAILURE", "UNSTABLE"));
    private int retentionMonths = 24;
    /** D-36: send notification e-mail through Mailer (off by default, so an upgrade changes nothing). */
    private boolean emailNotifications;
    /** D-36: how long before an expiry the EXPIRING / GRANT_EXPIRING notification fires. */
    private int notifyBeforeExpiryMinutes = 10;

    /**
     * #19: set only on the throw-away copy {@link #configure} binds the form into. On a candidate
     * the switch setters are plain assignments: no toggle record and no side effect happen until
     * the bound state has been written to disk.
     */
    private transient boolean candidate;

    public BatchControlGlobalConfiguration() {
        load();
    }

    /** Detached copy for transactional binding (#19); never registered, never loaded from disk. */
    private BatchControlGlobalConfiguration(BatchControlGlobalConfiguration source) {
        this.candidate = true;
        copyFrom(source);
    }

    /**
     * A detached copy of this configuration with {@code json} bound onto it, for re-showing a
     * refused form with what the user typed (D-53). Never registered, saved or used for any
     * decision; {@code null} when the submission cannot even be bound (then the plain refusal
     * stands).
     */
    @edu.umd.cs.findbugs.annotations.CheckForNull
    static BatchControlGlobalConfiguration draftOf(StaplerRequest2 req, JSONObject json) {
        try {
            BatchControlGlobalConfiguration draft = new BatchControlGlobalConfiguration(get());
            req.bindJSON(draft, normalizeListFields(json));
            return draft;
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not bind the refused configuration form for re-display", e);
            return null;
        }
    }

    /** S-25-06: a detached draft or candidate is never written to {@code config.xml}. */
    @Override
    public synchronized void save() {
        if (candidate) {
            return;
        }
        super.save();
    }

    public static BatchControlGlobalConfiguration get() {
        return ExtensionList.lookupSingleton(BatchControlGlobalConfiguration.class);
    }

    @Override
    public String getDisplayName() {
        return Messages.BatchControlGlobalConfiguration_DisplayName();
    }

    /**
     * #19: the form submission is a transaction. The form is bound into a detached copy (the
     * setters still validate), the copy is written to {@code config.xml}, and only after that
     * write succeeded is the new state applied to this instance, followed by the CONFIG_TOGGLE
     * records and the side effects (cache invalidation, revoking active grants when change control
     * goes off). If the write fails nothing in memory has changed and the error is surfaced.
     */
    /**
     * e2e-03 DEF-10: this section is shown and saved for {@code BatchControl/Manage} holders
     * (implied by {@code Overall/Administer}) rather than for administrators only.
     */
    @Override
    public Permission getRequiredGlobalConfigPagePermission() {
        return BatchControlPermissions.MANAGE;
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        // e2e-03 DEF-08: an invalid value refuses the whole submission, before anything is bound.
        // S-25-02: validation (which may ask a slow security realm) runs before the monitor is
        // taken, so it never holds up the switch setters; binding, write and apply stay one
        // transaction under the monitor (#19).
        JSONObject form = normalizeListFields(json);
        validate(form);
        validateApprovers(form);
        synchronized (this) {
            return bindWriteApply(req, form);
        }
    }

    private boolean bindWriteApply(StaplerRequest2 req, JSONObject form) throws FormException {
        BatchControlGlobalConfiguration bound = new BatchControlGlobalConfiguration(this);
        req.bindJSON(bound, form);
        // S-26-02: the empty-list rule again on the bound state, under the monitor (no realm call),
        // so a switch turned on meanwhile cannot leave the instance without approvers.
        if (bound.approvers.isEmpty() && (bound.runControlEnabled || bound.changeControlEnabled)) {
            throw new FormException(LABEL_APPROVERS + ": at least one approver is required while run control"
                    + " or change control is on.", "approversText");
        }
        try {
            bound.writeConfigFile();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to save the Batch Control configuration; nothing was applied", e);
            throw new FormException("The Batch Control configuration could not be saved, so no "
                    + "change was applied: " + e.getMessage(), e, "");
        }
        boolean previousRun = runControlEnabled;
        boolean previousChange = changeControlEnabled;
        String changes = describeChanges(this, bound);
        copyFrom(bound);
        SaveableListener.fireOnChange(this, getConfigFile());
        afterSwitchesChanged(previousRun, previousChange);
        if (!changes.isEmpty()) {
            recordConfigChange(changes);
        }
        return true;
    }

    // ---------------------------------------------------------------- switches

    public boolean isRunControlEnabled() {
        return runControlEnabled;
    }

    /**
     * Direct switch change (JCasC, script console). D-42 / security-09 S-01: this never fails
     * the caller. The new value is applied in memory, the toggle record and side effects follow,
     * and the configuration is then persisted; a failed write is logged at SEVERE and swallowed,
     * so a boot-time JCasC apply cannot abort startup and a switch JCasC turns on is on. The web
     * form path ({@link #configure}) stays transactional (#19).
     */
    public void setRunControlEnabled(boolean enabled) {
        if (this.runControlEnabled == enabled) {
            return;
        }
        if (this.candidate) {
            this.runControlEnabled = enabled;
            return;
        }
        synchronized (this) {
            boolean previousRun = this.runControlEnabled;
            if (previousRun == enabled) {
                return;
            }
            this.runControlEnabled = enabled;
            afterSwitchesChanged(previousRun, this.changeControlEnabled);
            persistBestEffort();
        }
    }

    public boolean isChangeControlEnabled() {
        return changeControlEnabled;
    }

    /**
     * S-15: turning change control off is a kill switch, not just a stop on new windows. Setting
     * the field false makes {@code security.GrantAwareACL} stop consulting grants immediately, and
     * the revocation in {@link #afterSwitchesChanged} closes the windows that are already open, so
     * they cannot come back to life when the switch is turned on again.
     *
     * <p>The accepted cost, and the owner's deliberate choice: every change that is underway inside
     * a permission window right now is cut off with no warning. A switch that instead left windows
     * quietly conferring {@code Item/Configure} for up to {@code maxGrantMinutes} afterwards was
     * judged the worse of the two, so long as the cut is recorded — which
     * {@code GrantService#revokeAllActive} does, one {@code GRANT_REVOKE} record per closed window.
     *
     * <p>#19 / D-42: on the form path the revocation runs only once the new value is durable; a
     * direct call revokes even if persisting then fails (revocation only removes permissions).
     */
    public void setChangeControlEnabled(boolean enabled) {
        if (this.changeControlEnabled == enabled) {
            return;
        }
        if (this.candidate) {
            this.changeControlEnabled = enabled;
            return;
        }
        synchronized (this) {
            boolean previousChange = this.changeControlEnabled;
            if (previousChange == enabled) {
                return;
            }
            this.changeControlEnabled = enabled;
            afterSwitchesChanged(this.runControlEnabled, previousChange);
            persistBestEffort();
        }
    }

    /**
     * D-42: persists after a direct switch change. A write failure is logged at SEVERE and does
     * not throw; the in-memory value stays applied.
     */
    private void persistBestEffort() {
        try {
            writeConfigFile();
            SaveableListener.fireOnChange(this, getConfigFile());
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to save the Batch Control configuration after a switch "
                    + "change; the change is applied in memory but will not survive a restart", e);
        }
    }

    /**
     * Toggle records and side effects of a switch change (#19: on the form path only after the
     * new state is on disk; D-42: on a direct setter call before the best-effort write). The toggle record is written before the revocations, so the audit
     * history reads in causal order: the switch went off, and then these windows were closed.
     */
    private void afterSwitchesChanged(boolean previousRun, boolean previousChange) {
        if (previousRun != this.runControlEnabled) {
            recordToggle("runControlEnabled", previousRun, this.runControlEnabled);
        }
        if (previousChange != this.changeControlEnabled) {
            recordToggle("changeControlEnabled", previousChange, this.changeControlEnabled);
            // S-05: the standing-permission warning caches its expensive scan; a toggle must show
            // the fresh state on the next admin page render, not after the TTL.
            io.jenkins.plugins.batchcontrol.ops.ConfigureWithoutGrantMonitor.invalidateCache();
            if (!this.changeControlEnabled) {
                io.jenkins.plugins.batchcontrol.security.GrantService.get().revokeAllActive();
            }
        }
    }

    /** Writes this instance to {@code config.xml}, throwing instead of logging on failure. */
    private void writeConfigFile() throws IOException {
        getConfigFile().write(this);
    }

    /** Copies every persisted setting from {@code source} (lists are copied, not shared). */
    private void copyFrom(BatchControlGlobalConfiguration source) {
        this.runControlEnabled = source.runControlEnabled;
        this.changeControlEnabled = source.changeControlEnabled;
        this.approvers = new ArrayList<>(source.approvers);
        this.allowAdminSelfApproval = source.allowAdminSelfApproval;
        this.pendingTimeoutHours = source.pendingTimeoutHours;
        this.approvedRunTimeoutMinutes = source.approvedRunTimeoutMinutes;
        this.grantDurationOptions = new ArrayList<>(source.grantDurationOptions);
        this.maxGrantMinutes = source.maxGrantMinutes;
        this.incidentResults = new ArrayList<>(source.incidentResults);
        this.retentionMonths = source.retentionMonths;
        this.emailNotifications = source.emailNotifications;
        this.notifyBeforeExpiryMinutes = source.notifyBeforeExpiryMinutes;
    }

    /** The {@code target} of a {@link ChangeType#CONFIG_CHANGE} record (D-52). */
    public static final String CONFIG_CHANGE_TARGET = "batch-control-configuration";

    /**
     * D-52: every field besides the two switches that differs between {@code before} and
     * {@code after}, as {@code "label: old -> new"} joined by {@code "; "} (the approver list in
     * full); {@code ""} when nothing changed.
     */
    static String describeChanges(BatchControlGlobalConfiguration before, BatchControlGlobalConfiguration after) {
        List<String> changes = new ArrayList<>();
        diff(changes, "approvers", before.approvers, after.approvers);
        diff(changes, "allowAdminSelfApproval", before.allowAdminSelfApproval, after.allowAdminSelfApproval);
        diff(changes, "pendingTimeoutHours", before.pendingTimeoutHours, after.pendingTimeoutHours);
        diff(changes, "approvedRunTimeoutMinutes", before.approvedRunTimeoutMinutes, after.approvedRunTimeoutMinutes);
        diff(changes, "grantDurationOptions", before.grantDurationOptions, after.grantDurationOptions);
        diff(changes, "maxGrantMinutes", before.maxGrantMinutes, after.maxGrantMinutes);
        diff(changes, "incidentResults", before.incidentResults, after.incidentResults);
        diff(changes, "retentionMonths", before.retentionMonths, after.retentionMonths);
        diff(changes, "emailNotifications", before.emailNotifications, after.emailNotifications);
        diff(changes, "notifyBeforeExpiryMinutes", before.notifyBeforeExpiryMinutes, after.notifyBeforeExpiryMinutes);
        return String.join("; ", changes);
    }

    private static void diff(List<String> changes, String field, Object before, Object after) {
        if (!java.util.Objects.equals(before, after)) {
            changes.add(field + ": " + before + " -> " + after);
        }
    }

    /**
     * D-52: one {@link ChangeType#CONFIG_CHANGE} record for the save. Written after the new state is
     * durable; a store failure is logged and does not undo the save (as for the toggle records).
     */
    private static void recordConfigChange(String changes) {
        try {
            Store.get().appendChangeRecord(ChangeRecord.create(ChangeType.CONFIG_CHANGE, CONFIG_CHANGE_TARGET,
                    Jenkins.getAuthentication2().getName(), "Batch Control configuration changed: " + changes));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not record the Batch Control configuration change: " + changes, e);
        }
    }

    private static void recordToggle(String key, boolean previous, boolean current) {
        String user = Jenkins.getAuthentication2().getName();
        Store.get().appendChangeRecord(
                ChangeRecord.create(ChangeType.CONFIG_TOGGLE, key, user, previous + " -> " + current));
    }

    // ---------------------------------------------------------------- approvers

    public List<String> getApprovers() {
        return new ArrayList<>(approvers);
    }

    public void setApprovers(List<String> approvers) {
        // S-26-03: stored de-duplicated (first occurrence wins), as validated.
        this.approvers = new ArrayList<>(new java.util.LinkedHashSet<>(sanitizeStrings(approvers)));
    }

    public String getApproversText() {
        return String.join("\n", approvers);
    }

    public void setApproversText(String text) {
        this.approvers = new ArrayList<>(new java.util.LinkedHashSet<>(parseStrings(text))); // S-26-03
    }

    public boolean isAllowAdminSelfApproval() {
        return allowAdminSelfApproval;
    }

    public void setAllowAdminSelfApproval(boolean allowAdminSelfApproval) {
        this.allowAdminSelfApproval = allowAdminSelfApproval;
    }

    // ---------------------------------------------------------------- timeouts and limits

    public int getPendingTimeoutHours() {
        return pendingTimeoutHours;
    }

    public void setPendingTimeoutHours(int pendingTimeoutHours) {
        if (pendingTimeoutHours > 0) {
            this.pendingTimeoutHours = pendingTimeoutHours;
        }
    }

    public int getApprovedRunTimeoutMinutes() {
        return approvedRunTimeoutMinutes;
    }

    public void setApprovedRunTimeoutMinutes(int approvedRunTimeoutMinutes) {
        if (approvedRunTimeoutMinutes > 0) {
            this.approvedRunTimeoutMinutes = approvedRunTimeoutMinutes;
        }
    }

    public List<Integer> getGrantDurationOptions() {
        return new ArrayList<>(grantDurationOptions);
    }

    public void setGrantDurationOptions(List<Integer> grantDurationOptions) {
        List<Integer> sanitized = new ArrayList<>();
        if (grantDurationOptions != null) {
            for (Integer option : grantDurationOptions) {
                if (option != null && option > 0) {
                    sanitized.add(option);
                }
            }
        }
        if (!sanitized.isEmpty()) {
            this.grantDurationOptions = sanitized;
        }
    }

    public String getGrantDurationOptionsText() {
        return grantDurationOptions.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    public void setGrantDurationOptionsText(String text) {
        List<Integer> parsed = new ArrayList<>();
        for (String token : parseStrings(text)) {
            try {
                parsed.add(Integer.parseInt(token));
            } catch (NumberFormatException ignored) {
                // Invalid entries are dropped; validation keeps only positive numbers below.
            }
        }
        setGrantDurationOptions(parsed);
    }

    public int getMaxGrantMinutes() {
        return maxGrantMinutes;
    }

    public void setMaxGrantMinutes(int maxGrantMinutes) {
        if (maxGrantMinutes > 0) {
            this.maxGrantMinutes = maxGrantMinutes;
        }
    }

    // ---------------------------------------------------------------- incidents and retention

    public List<String> getIncidentResults() {
        return new ArrayList<>(incidentResults);
    }

    public void setIncidentResults(List<String> incidentResults) {
        List<String> sanitized = new ArrayList<>();
        for (String result : sanitizeStrings(incidentResults)) {
            // Build results are upper case (hudson.model.Result); "failure" means FAILURE.
            sanitized.add(result.toUpperCase(Locale.ROOT));
        }
        if (!sanitized.isEmpty()) {
            this.incidentResults = sanitized;
        }
    }

    public String getIncidentResultsText() {
        return String.join(", ", incidentResults);
    }

    public void setIncidentResultsText(String text) {
        setIncidentResults(parseStrings(text));
    }

    public int getRetentionMonths() {
        return retentionMonths;
    }

    public void setRetentionMonths(int retentionMonths) {
        if (retentionMonths > 0) {
            this.retentionMonths = retentionMonths;
        }
    }

    // ---------------------------------------------------------------- notifications (D-36)

    public boolean isEmailNotifications() {
        return emailNotifications;
    }

    @DataBoundSetter
    public void setEmailNotifications(boolean emailNotifications) {
        this.emailNotifications = emailNotifications;
    }

    public int getNotifyBeforeExpiryMinutes() {
        return notifyBeforeExpiryMinutes;
    }

    @DataBoundSetter
    public void setNotifyBeforeExpiryMinutes(int notifyBeforeExpiryMinutes) {
        if (notifyBeforeExpiryMinutes > 0) {
            this.notifyBeforeExpiryMinutes = notifyBeforeExpiryMinutes;
        }
    }

    /**
     * Whether the Mailer plugin is installed and active; the configuration page shows the
     * {@code emailNotifications} option only then (D-36).
     */
    public boolean isMailerAvailable() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return false;
        }
        hudson.PluginWrapper mailer = jenkins.getPluginManager().getPlugin("mailer");
        return mailer != null && mailer.isActive();
    }

    // ---------------------------------------------------------------- validation (e2e-03 DEF-08)

    /** Field labels as the form shows them, used in every validation message. */
    static final String LABEL_PENDING = "Pending request timeout (hours)";
    static final String LABEL_APPROVED_RUN = "Approved-but-not-run timeout (minutes)";
    static final String LABEL_DURATIONS = "Grant duration options";
    static final String LABEL_MAX_GRANT = "Maximum grant duration (minutes)";
    static final String LABEL_INCIDENT_RESULTS = "Results that open an incident";
    static final String LABEL_RETENTION = "Retention period (months)";
    static final String LABEL_NOTIFY = "Notify before expiry (minutes)";

    /** The build results an incident can be opened for ({@code hudson.model.Result} names). */
    static final List<String> RESULT_NAMES =
            List.of("FAILURE", "UNSTABLE", "ABORTED", "NOT_BUILT", "SUCCESS");

    /**
     * The form may name the two list settings by their SPEC keys ({@code grantDurationOptions},
     * {@code incidentResults}) with the comma-separated text as the value; they are bound through
     * the text setters. A copy is returned; {@code json} is not changed.
     */
    static JSONObject normalizeListFields(JSONObject json) {
        JSONObject form = JSONObject.fromObject(json);
        for (String key : new String[] {"grantDurationOptions", "incidentResults"}) {
            Object value = form.opt(key);
            if (value instanceof String) {
                form.remove(key);
                form.put(key + "Text", value);
            }
        }
        return form;
    }

    /**
     * Refuses a form submission holding an invalid value, naming the field (its JSON key) and
     * saying what is expected. A field the submission does not carry is left alone.
     */
    static void validate(JSONObject json) throws FormException {
        requireValid(json, "pendingTimeoutHours", v -> positiveError(v, LABEL_PENDING));
        requireValid(json, "approvedRunTimeoutMinutes", v -> positiveError(v, LABEL_APPROVED_RUN));
        requireValid(json, "maxGrantMinutes", v -> positiveError(v, LABEL_MAX_GRANT));
        requireValid(json, "retentionMonths", v -> positiveError(v, LABEL_RETENTION));
        requireValid(json, "notifyBeforeExpiryMinutes", v -> positiveError(v, LABEL_NOTIFY));
        String max = json.has("maxGrantMinutes") ? String.valueOf(json.get("maxGrantMinutes")) : null;
        requireValid(json, "grantDurationOptionsText", v -> durationOptionsError(v, max));
        requireValid(json, "incidentResultsText", BatchControlGlobalConfiguration::incidentResultsError);
    }

    private static void requireValid(JSONObject json, String field, UnaryOperator<String> errorOf)
            throws FormException {
        if (!json.has(field)) {
            return;
        }
        Object raw = json.get(field);
        String error = errorOf.apply(raw == null ? null : String.valueOf(raw));
        if (error != null) {
            throw new FormException(error, field);
        }
    }

    private static FormValidation toValidation(String error) {
        return error == null ? FormValidation.ok() : FormValidation.error(error);
    }

    /** A whole number of at least 1; the plain-text error, or {@code null} when valid. */
    static String positiveError(String value, String label) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return label + ": a value is required (a whole number of 1 or more).";
        }
        try {
            if (Integer.parseInt(text) >= 1) {
                return null;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        return label + ": '" + text + "' is not allowed; enter a whole number of 1 or more.";
    }

    /** Comma- or newline-separated whole minutes, each at least 1 and at most {@code maxText}. */
    static String durationOptionsError(String value, String maxText) {
        List<String> tokens = parseStrings(value);
        if (tokens.isEmpty()) {
            return LABEL_DURATIONS + ": at least one duration is required, for example 15, 30, 60.";
        }
        Integer max = null;
        try {
            max = maxText == null ? null : Integer.valueOf(maxText.trim());
        } catch (NumberFormatException e) {
            // the maximum field reports its own error
        }
        for (String token : tokens) {
            int minutes;
            try {
                minutes = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                return LABEL_DURATIONS + ": '" + token + "' is not a whole number of minutes. "
                        + "Enter minutes separated by commas, for example 15, 30, 60.";
            }
            if (minutes < 1) {
                return LABEL_DURATIONS + ": '" + token + "' is not allowed; each duration must be 1 minute or more.";
            }
            if (max != null && max >= 1 && minutes > max) {
                return LABEL_DURATIONS + ": " + minutes + " minutes exceeds the maximum grant duration of "
                        + max + " minutes. Lower the option or raise the maximum.";
            }
        }
        return null;
    }

    /** Comma- or newline-separated build result names. */
    static String incidentResultsError(String value) {
        List<String> tokens = parseStrings(value);
        if (tokens.isEmpty()) {
            return LABEL_INCIDENT_RESULTS + ": at least one result is required, for example FAILURE, UNSTABLE.";
        }
        for (String token : tokens) {
            if (!RESULT_NAMES.contains(token.toUpperCase(Locale.ROOT))) {
                return LABEL_INCIDENT_RESULTS + ": '" + token + "' is not a build result. Use "
                        + String.join(", ", RESULT_NAMES) + ".";
            }
        }
        return null;
    }

    static final String LABEL_APPROVERS = "Approvers";

    /**
     * D-53: refuses the submission when its approver list names an unknown id, or is empty while
     * either switch is (or will be) on. A field the submission does not carry is left alone.
     */
    private void validateApprovers(JSONObject json) throws FormException {
        if (!json.has("approversText")) {
            return;
        }
        boolean switchOn = json.optBoolean("runControlEnabled", runControlEnabled)
                || json.optBoolean("changeControlEnabled", changeControlEnabled);
        ApproversCheck check = approversCheck(String.valueOf(json.get("approversText")), switchOn);
        if (check.kind() == FormValidation.Kind.ERROR) {
            throw new FormException(check.text(), "approversText"); // plain text (FD-14)
        }
    }

    /**
     * D-53: each id must name an existing Jenkins user or one the security realm resolves. An
     * unknown id is an error naming it; an id the realm could not be asked about (an error other
     * than "not found") is accepted with a warning. An empty list is an error while
     * {@code switchOn}. The message is plain text (FormValidation escapes it).
     */
    static FormValidation approversValidation(String text, boolean switchOn) {
        return approversCheck(text, switchOn).toValidation();
    }

    /**
     * The approver check's outcome with a plain-text message (e2e-04 FD-14): a FormException must
     * carry plain text, because the banner and core's error page escape it themselves, while
     * {@code FormValidation#getMessage()} is already HTML-escaped.
     */
    record ApproversCheck(FormValidation.Kind kind, String text) {
        static final ApproversCheck OK = new ApproversCheck(FormValidation.Kind.OK, null);

        static ApproversCheck error(String text) {
            return new ApproversCheck(FormValidation.Kind.ERROR, text);
        }

        static ApproversCheck warning(String text) {
            return new ApproversCheck(FormValidation.Kind.WARNING, text);
        }

        FormValidation toValidation() {
            switch (kind) {
                case ERROR:
                    return FormValidation.error(text);
                case WARNING:
                    return FormValidation.warning(text);
                default:
                    return FormValidation.ok();
            }
        }
    }

    static ApproversCheck approversCheck(String text, boolean switchOn) {
        // S-25-02: de-duplicated and capped before any lookup.
        List<String> ids = new ArrayList<>(new java.util.LinkedHashSet<>(parseStrings(text)));
        if (ids.size() > MAX_APPROVERS) {
            return ApproversCheck.error(LABEL_APPROVERS + ": at most " + MAX_APPROVERS + " approvers can be listed.");
        }
        for (String id : ids) {
            if (id.length() > MAX_ID_LENGTH) {
                // S-26-03: never sent to the realm.
                return ApproversCheck.error(LABEL_APPROVERS + ": an id longer than " + MAX_ID_LENGTH
                        + " characters is not a user id.");
            }
        }
        if (ids.isEmpty()) {
            return switchOn
                    ? ApproversCheck.error(LABEL_APPROVERS + ": at least one approver is required while run control"
                            + " or change control is on.")
                    : ApproversCheck.OK;
        }
        List<String> unknown = new ArrayList<>();
        List<String> unchecked = new ArrayList<>();
        boolean stoppedEarly = false;
        for (String id : ids) {
            if (unknown.size() >= MAX_UNKNOWN_REPORTED || !unchecked.isEmpty()) {
                // S-25-02, S-26-03: after a few unknown ids, or once the realm failed to answer (an
                // unreachable realm will not answer the next id either), the rest is not asked.
                stoppedEarly = true;
                break;
            }
            switch (resolve(id)) {
                case UNKNOWN:
                    unknown.add(id);
                    break;
                case UNCHECKED:
                    unchecked.add(id);
                    break;
                default:
                    break;
            }
        }
        if (!unknown.isEmpty()) {
            return ApproversCheck.error(LABEL_APPROVERS + ": " + quoted(unknown)
                    + (unknown.size() == 1 ? " is not a known Jenkins user" : " are not known Jenkins users")
                    + (stoppedEarly ? " (the remaining ids were not checked)" : "")
                    + ". Enter existing user ids, one per line. Nothing was saved.");
        }
        if (!unchecked.isEmpty()) {
            return ApproversCheck.warning(LABEL_APPROVERS + ": " + quoted(unchecked) + " could not be checked"
                    + " against the security realm right now" + (stoppedEarly ? " (nor the ids after it)" : "")
                    + "; make sure the ids are correct.");
        }
        return ApproversCheck.OK;
    }

    /** S-25-02: the most approver ids a list may hold. */
    static final int MAX_APPROVERS = 100;

    /** S-25-02: after this many unknown ids the realm is not asked any more. */
    static final int MAX_UNKNOWN_REPORTED = 5;

    /** S-26-03: the longest approver id that is looked up. */
    static final int MAX_ID_LENGTH = 256;

    private enum Resolution { KNOWN, UNKNOWN, UNCHECKED }

    private static Resolution resolve(String id) {
        if (hudson.model.User.getById(id, false) != null) {
            return Resolution.KNOWN;
        }
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null || jenkins.getSecurityRealm() == hudson.security.SecurityRealm.NO_AUTHENTICATION) {
            return Resolution.UNCHECKED; // no realm to ask
        }
        try {
            jenkins.getSecurityRealm().loadUserByUsername2(id);
            return Resolution.KNOWN;
        } catch (hudson.security.UserMayOrMayNotExistException2 e) {
            // S-25-05: the realm cannot tell (for example AD without a bind account): D-53 accepts
            // the id with a warning. This subclass of UsernameNotFoundException is caught first.
            return Resolution.UNCHECKED;
        } catch (org.springframework.security.core.userdetails.UsernameNotFoundException e) {
            return Resolution.UNKNOWN;
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "The security realm could not be asked about approver '" + id + "'", e);
            return Resolution.UNCHECKED;
        }
    }

    private static String quoted(List<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            out.add("'" + id + "'");
        }
        return String.join(", ", out);
    }

    /** Stapler form validation of the approver list (read-only, D-53). */
    @POST
    public FormValidation doCheckApproversText(@QueryParameter String value) {
        // S-25-09: the lookup reveals whether an account exists, so a caller without
        // BatchControl/Manage is refused (403), not answered; @POST refuses a GET.
        Jenkins.get().checkPermission(BatchControlPermissions.MANAGE);
        return approversValidation(value, runControlEnabled || changeControlEnabled);
    }

    /** The form's inline checks answer only a user who may save the form. */
    private static boolean mayCheck() {
        return Jenkins.get().hasPermission(BatchControlPermissions.MANAGE);
    }

    /** Stapler form validation (read-only). */
    @POST
    public FormValidation doCheckPendingTimeoutHours(@QueryParameter String value) {
        return mayCheck() ? toValidation(positiveError(value, LABEL_PENDING)) : FormValidation.ok();
    }

    /** Stapler form validation (read-only). */
    @POST
    public FormValidation doCheckApprovedRunTimeoutMinutes(@QueryParameter String value) {
        return mayCheck() ? toValidation(positiveError(value, LABEL_APPROVED_RUN)) : FormValidation.ok();
    }

    /** Stapler form validation (read-only). */
    @POST
    public FormValidation doCheckMaxGrantMinutes(@QueryParameter String value) {
        return mayCheck() ? toValidation(positiveError(value, LABEL_MAX_GRANT)) : FormValidation.ok();
    }

    /** Stapler form validation (read-only). */
    @POST
    public FormValidation doCheckRetentionMonths(@QueryParameter String value) {
        return mayCheck() ? toValidation(positiveError(value, LABEL_RETENTION)) : FormValidation.ok();
    }

    /** Stapler form validation (read-only). */
    @POST
    public FormValidation doCheckNotifyBeforeExpiryMinutes(@QueryParameter String value) {
        return mayCheck() ? toValidation(positiveError(value, LABEL_NOTIFY)) : FormValidation.ok();
    }

    /** Stapler form validation (read-only); re-run when the maximum changes. */
    @POST
    public FormValidation doCheckGrantDurationOptionsText(@QueryParameter String value,
                                                          @QueryParameter String maxGrantMinutes) {
        return mayCheck() ? toValidation(durationOptionsError(value, maxGrantMinutes)) : FormValidation.ok();
    }

    /** Stapler form validation (read-only). */
    @POST
    public FormValidation doCheckIncidentResultsText(@QueryParameter String value) {
        return mayCheck() ? toValidation(incidentResultsError(value)) : FormValidation.ok();
    }

    /** Stapler form validation of the field named by its SPEC key (read-only). */
    @POST
    public FormValidation doCheckGrantDurationOptions(@QueryParameter String value,
                                                      @QueryParameter String maxGrantMinutes) {
        return doCheckGrantDurationOptionsText(value, maxGrantMinutes);
    }

    /** Stapler form validation of the field named by its SPEC key (read-only). */
    @POST
    public FormValidation doCheckIncidentResults(@QueryParameter String value) {
        return doCheckIncidentResultsText(value);
    }

    // ---------------------------------------------------------------- helpers

    /** Splits a newline- or comma-separated string into trimmed, non-empty entries. */
    static List<String> parseStrings(String text) {
        List<String> values = new ArrayList<>();
        if (text != null) {
            for (String token : text.split("[,\\r\\n]+")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    values.add(trimmed);
                }
            }
        }
        return values;
    }

    private static List<String> sanitizeStrings(List<String> values) {
        List<String> sanitized = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    sanitized.add(value.trim());
                }
            }
        }
        return sanitized;
    }
}
