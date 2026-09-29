package io.jenkins.plugins.batchcontrol.config;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.listeners.SaveableListener;
import io.jenkins.plugins.batchcontrol.Messages;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Global plugin configuration (SPEC section 5 keys and defaults). Nothing is active until an
 * administrator turns a switch on; toggling either switch writes a
 * {@code ChangeRecord(CONFIG_TOGGLE)} to the store (SPEC item 1).
 *
 * <p>Numeric setters silently ignore non-positive values so an invalid form value can never be
 * persisted (the previous value is kept).
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
    @Override
    public synchronized boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        BatchControlGlobalConfiguration bound = new BatchControlGlobalConfiguration(this);
        req.bindJSON(bound, json);
        try {
            bound.writeConfigFile();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to save the Batch Control configuration; nothing was applied", e);
            throw new FormException("The Batch Control configuration could not be saved, so no "
                    + "change was applied: " + e.getMessage(), e, "");
        }
        boolean previousRun = runControlEnabled;
        boolean previousChange = changeControlEnabled;
        copyFrom(bound);
        SaveableListener.fireOnChange(this, getConfigFile());
        afterSwitchesChanged(previousRun, previousChange);
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
        this.approvers = sanitizeStrings(approvers);
    }

    public String getApproversText() {
        return String.join("\n", approvers);
    }

    public void setApproversText(String text) {
        this.approvers = parseStrings(text);
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
        List<String> sanitized = sanitizeStrings(incidentResults);
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
