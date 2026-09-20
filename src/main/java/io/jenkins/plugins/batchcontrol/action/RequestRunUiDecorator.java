package io.jenkins.plugins.batchcontrol.action;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.util.AlternativeUiTextProvider;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import jenkins.model.ParameterizedJobMixIn;

/**
 * Replaces the "Build Now" sidebar label with "Request Run" for approval-protected jobs (SPEC
 * item 6: the manual build entry point must read "Request Run" while run control is on).
 *
 * <p>Both message keys are handled because Freestyle uses
 * {@link AbstractProject#BUILD_NOW_TEXT} while Pipeline (and other {@code ParameterizedJob}s)
 * uses {@link ParameterizedJobMixIn#BUILD_NOW_TEXT} (POC-RESULTS assumption D). The actual
 * request form link is provided by {@link JobRequestAction}; direct build attempts are blocked
 * at the queue by core-dev's decision handler.
 */
@Extension
public class RequestRunUiDecorator extends AlternativeUiTextProvider {

    /** The replacement label, also used by {@link JobRequestAction#getDisplayName()}. */
    public static final String REQUEST_RUN_LABEL = "Request Run";

    @Override
    public <T> String getText(Message<T> text, T context) {
        if (text != AbstractProject.BUILD_NOW_TEXT && text != ParameterizedJobMixIn.BUILD_NOW_TEXT) {
            return null;
        }
        if (!(context instanceof Job)) {
            return null;
        }
        Job<?, ?> job = (Job<?, ?>) context;
        if (!BatchControlGlobalConfiguration.get().isRunControlEnabled()) {
            return null;
        }
        BatchControlJobProperty property = job.getProperty(BatchControlJobProperty.class);
        if (property == null || !property.isApprovalRequired()) {
            return null;
        }
        return REQUEST_RUN_LABEL;
    }
}
