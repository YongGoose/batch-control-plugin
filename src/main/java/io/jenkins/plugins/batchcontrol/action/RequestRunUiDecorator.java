package io.jenkins.plugins.batchcontrol.action;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.util.AlternativeUiTextProvider;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import jenkins.model.ParameterizedJobMixIn;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Relabels core's "Build Now" sidebar entry on approval-protected jobs (SPEC item 6: the manual
 * build entry point must not read "Build Now" while run control is on).
 *
 * <p>Both message keys are handled because Freestyle uses
 * {@link AbstractProject#BUILD_NOW_TEXT} while Pipeline (and other {@code ParameterizedJob}s)
 * uses {@link ParameterizedJobMixIn#BUILD_NOW_TEXT} (POC-RESULTS assumption D). Core's
 * {@code BUILD_WITH_PARAMETERS_TEXT} needs no separate handling: when no provider answers it,
 * core falls back to {@code BUILD_NOW_TEXT}, which is this one.
 *
 * <p><b>U-02.</b> This label used to be "Request Run" — the same words
 * {@link JobRequestAction} puts on the sidebar — so a controlled job showed two identical
 * entries going to different places, and the core one is the wrong one: its href is
 * {@code /job/<name>/build?delay=0sec}, so following it reaches the queue without an approved
 * marker and is refused (e2e-01 UX-1 recorded it as a dead end that looks like the right
 * button). Core's entry cannot be hidden or re-pointed from a plugin — its href and its
 * {@code it.buildable}/{@code Item/Build} rendering condition are core's — so the two are told
 * apart by their labels instead: exactly one sidebar entry now reads
 * {@value #REQUEST_RUN_LABEL} and it is the one that opens the request form, while this one
 * says what it is and that it needs approval first.
 */
@Extension
@Restricted(NoExternalUse.class)
public class RequestRunUiDecorator extends AlternativeUiTextProvider {

    /**
     * The sidebar label of the plugin's own request form, used by
     * {@link JobRequestAction#getDisplayName()}. Kept here so that the two sidebar entries of a
     * controlled job are named in one place and cannot drift back into being identical.
     */
    public static final String REQUEST_RUN_LABEL = "Request Run";

    /**
     * The replacement for core's "Build Now" caption. It deliberately does not contain the
     * words "Build Now" (SPEC item 6) and is not a synonym of {@value #REQUEST_RUN_LABEL}.
     */
    public static final String BLOCKED_BUILD_LABEL = "Direct Build (needs approval)";

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
        // D-82: a job's sub-item (a matrix configuration) is judged by its parent job's property at
        // the queue gate, so its build entry is labelled by that property too.
        BatchControlJobProperty property = ActivationService.governingJob(job)
                .getProperty(BatchControlJobProperty.class);
        if (property == null || !property.isApprovalRequired()) {
            return null;
        }
        return BLOCKED_BUILD_LABEL;
    }
}
