package io.jenkins.plugins.batchcontrol.config;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import io.jenkins.plugins.batchcontrol.Messages;
import java.util.ArrayList;
import java.util.List;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Per-job batch control settings (SPEC items 5 and 6): whether the job requires an approved run
 * request, trigger-blocking policy, and an optional job-level approver restriction.
 *
 * <p>These settings have no effect while the global run-control switch is off.
 */
@Restricted(NoExternalUse.class) // configured via the job form / JCasC, not a code-level API
public class BatchControlJobProperty extends JobProperty<Job<?, ?>> {

    private final boolean approvalRequired;
    private boolean blockTimer;
    private boolean blockUpstream;
    private List<String> allowedUpstreamJobs = new ArrayList<>();
    private List<String> jobApprovers = new ArrayList<>();

    @DataBoundConstructor
    public BatchControlJobProperty(boolean approvalRequired) {
        this.approvalRequired = approvalRequired;
    }

    public boolean isApprovalRequired() {
        return approvalRequired;
    }

    /**
     * The new-job activation lock of D-34: {@code approvalRequired}, {@code blockTimer} and
     * {@code blockUpstream} all on, and no upstream allow list — so no cause at all can start the
     * job until somebody turns a switch off in its configuration.
     *
     * <p>The empty allow list is part of the lock rather than an accident of construction: with
     * {@code blockUpstream} on, {@code allowedUpstreamJobs} is the list of upstream jobs that are
     * <em>exempt</em> from it (D-16), so a non-empty list is an open upstream door (P-14).
     */
    public static BatchControlJobProperty activationLocked() {
        BatchControlJobProperty locked = new BatchControlJobProperty(true);
        locked.blockTimer = true;
        locked.blockUpstream = true;
        locked.allowedUpstreamJobs = new ArrayList<>();
        return locked;
    }

    /**
     * These settings with the D-34 activation lock applied: every setting that decides whether a
     * run may start is forced to its locked value, and the settings that decide nothing about
     * starting a run are carried over.
     *
     * <p>{@code approvalRequired} is final, so the default has to rebuild the property rather than
     * flip a field. {@code jobApprovers} is carried over because it only ever <em>narrows</em> who
     * may approve a request for this job ({@code policy.ApprovalPolicy#checkDesignation} applies it
     * on top of the global approver list), so honouring a creator-supplied value cannot widen
     * anything. The trigger policy is not carried over — see {@link #activationLocked()} and P-14.
     */
    public BatchControlJobProperty withActivationLock() {
        BatchControlJobProperty locked = activationLocked();
        locked.jobApprovers = getJobApprovers();
        return locked;
    }

    /**
     * Whether these settings already <em>are</em> the activation lock, so that applying it again
     * would cost the job a property rebuild (two saves, S-20) and change nothing.
     */
    public boolean isActivationLocked() {
        return approvalRequired && blockTimer && blockUpstream
                && getAllowedUpstreamJobs().isEmpty();
    }

    /**
     * The trigger switches that are refusing unattended runs of this job right now, in the order
     * {@code blockTimer}, {@code blockUpstream} (#21, job-page notice). Empty while run control is
     * off or the job is not approval-required, because the queue gate then lets every cause pass
     * and a switch that is set blocks nothing. The notice tells the reader to clear the switch in
     * the job configuration, which is itself change-controlled.
     */
    public List<String> getBlockingSwitches() {
        if (!approvalRequired || !BatchControlGlobalConfiguration.get().isRunControlEnabled()) {
            return List.of();
        }
        List<String> switches = new ArrayList<>(2);
        if (blockTimer) {
            switches.add("blockTimer");
        }
        if (blockUpstream) {
            switches.add("blockUpstream");
        }
        return switches;
    }

    public boolean isBlockTimer() {
        return blockTimer;
    }

    @DataBoundSetter
    public void setBlockTimer(boolean blockTimer) {
        this.blockTimer = blockTimer;
    }

    public boolean isBlockUpstream() {
        return blockUpstream;
    }

    @DataBoundSetter
    public void setBlockUpstream(boolean blockUpstream) {
        this.blockUpstream = blockUpstream;
    }

    public List<String> getAllowedUpstreamJobs() {
        return allowedUpstreamJobs == null ? new ArrayList<>() : new ArrayList<>(allowedUpstreamJobs);
    }

    @DataBoundSetter
    public void setAllowedUpstreamJobs(List<String> allowedUpstreamJobs) {
        this.allowedUpstreamJobs = copyNonEmpty(allowedUpstreamJobs);
    }

    public String getAllowedUpstreamJobsText() {
        return String.join("\n", getAllowedUpstreamJobs());
    }

    @DataBoundSetter
    public void setAllowedUpstreamJobsText(String text) {
        this.allowedUpstreamJobs = BatchControlGlobalConfiguration.parseStrings(text);
    }

    public List<String> getJobApprovers() {
        return jobApprovers == null ? new ArrayList<>() : new ArrayList<>(jobApprovers);
    }

    @DataBoundSetter
    public void setJobApprovers(List<String> jobApprovers) {
        this.jobApprovers = copyNonEmpty(jobApprovers);
    }

    public String getJobApproversText() {
        return String.join("\n", getJobApprovers());
    }

    @DataBoundSetter
    public void setJobApproversText(String text) {
        this.jobApprovers = BatchControlGlobalConfiguration.parseStrings(text);
    }

    private static List<String> copyNonEmpty(List<String> values) {
        List<String> copy = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    copy.add(value.trim());
                }
            }
        }
        return copy;
    }

    @Extension
    @Symbol("batchControl")
    public static class DescriptorImpl extends JobPropertyDescriptor {

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            // Freestyle, Pipeline (WorkflowJob) and any other Job type.
            return true;
        }

        @Override
        public String getDisplayName() {
            return Messages.BatchControlJobProperty_DisplayName();
        }
    }
}
