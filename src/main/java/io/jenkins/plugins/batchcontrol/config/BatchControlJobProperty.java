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
     * These settings with {@code approvalRequired} set as given, every other setting carried over.
     *
     * <p>{@code approvalRequired} is final, so the D-31 default has to rebuild the property rather
     * than flip a field; rebuilding must not drop the trigger policy ({@code blockTimer},
     * {@code blockUpstream}, {@code allowedUpstreamJobs}) or the job-level approver list that the
     * creator supplied in the same config.xml.
     */
    public BatchControlJobProperty withApprovalRequired(boolean required) {
        if (required == approvalRequired) {
            return this;
        }
        BatchControlJobProperty copy = new BatchControlJobProperty(required);
        copy.blockTimer = blockTimer;
        copy.blockUpstream = blockUpstream;
        copy.allowedUpstreamJobs = getAllowedUpstreamJobs();
        copy.jobApprovers = getJobApprovers();
        return copy;
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
